// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.fearmikey.takwave.audio.AndroidAudioSink
import com.fearmikey.takwave.audio.AndroidAudioSource
import com.fearmikey.takwave.audio.AudioDevices
import com.fearmikey.takwave.core.codec.PacketCodec
import com.fearmikey.takwave.core.codec.PayloadCipher
import com.fearmikey.takwave.core.codec.PliMessage
import com.fearmikey.takwave.core.codec.Role
import com.fearmikey.takwave.core.codec.Team
import com.fearmikey.takwave.core.gateway.Gateway
import com.fearmikey.takwave.core.gateway.RxKind
import com.fearmikey.takwave.core.gateway.TxKind
import com.fearmikey.takwave.core.sched.RepeatConfig
import com.fearmikey.takwave.core.gateway.summary
import com.fearmikey.takwave.core.io.FrameTransport
import com.fearmikey.takwave.core.io.KissLink
import com.fearmikey.takwave.core.io.KissStreamTransport
import com.fearmikey.takwave.core.io.KissTcpTransport
import com.fearmikey.takwave.core.io.ModemConfig
import com.fearmikey.takwave.core.io.NoPtt
import com.fearmikey.takwave.core.io.Ptt
import com.fearmikey.takwave.core.io.PttCommand
import com.fearmikey.takwave.core.io.SoftModemTransport
import com.fearmikey.takwave.core.modem.AfskModulator
import com.fearmikey.takwave.core.modem.Bell202
import com.fearmikey.takwave.core.sched.Clock
import com.fearmikey.takwave.core.sched.PliConfig
import com.fearmikey.takwave.core.sched.SlotConfig
import com.fearmikey.takwave.core.sched.SlotSchedule
import com.fearmikey.takwave.core.stats.ChannelEvent
import com.fearmikey.takwave.core.stats.ChannelStats
import com.fearmikey.takwave.data.Fx25Mode
import com.fearmikey.takwave.data.KeyFiles
import com.fearmikey.takwave.data.StateFile
import com.fearmikey.takwave.data.TimeSource
import com.fearmikey.takwave.data.EncryptionMode
import com.fearmikey.takwave.data.ModemBackend
import com.fearmikey.takwave.data.PttMethod
import com.fearmikey.takwave.data.Settings
import com.fearmikey.takwave.net.CotServer
import com.fearmikey.takwave.ptt.BluetoothDevices
import com.fearmikey.takwave.ptt.BluetoothPtt
import com.fearmikey.takwave.ptt.SerialDevices
import com.fearmikey.takwave.ptt.SerialPtt
import kotlinx.coroutines.flow.MutableStateFlow

enum class CalibrationTone { MARK, SPACE, ALTERNATING }

/**
 * The running gateway: TAK server + [Gateway] + frame transport, and the threads that move
 * frames between them.
 *
 * ```
 * TAK client --TCP--> CotServer --> Gateway.onClientEvent --> scheduler / queue
 *                                                                  |
 *                              tx thread: Gateway.pollFrame --> FrameTransport.send --> radio
 * radio --> FrameTransport listener --> Gateway.onRfFrame --> CotServer.sendToAll --> TAK clients
 * ```
 *
 * Built by the foreground service with a snapshot of the settings; to apply new settings the
 * service is restarted. Nothing here runs on the main thread except [start] and [stop], which
 * only spawn or signal threads.
 */
class RadioEngine(
    private val context: Context,
    private val settings: Settings,
    private val status: MutableStateFlow<RadioStatus>,
    private val log: PacketLog,
    private val keyFiles: KeyFiles,
    private val stateFile: StateFile,
) {
    /** Correction added to the phone clock to get GNSS time; 0 until a GNSS time is available. */
    @Volatile private var clockOffsetMs = 0L
    @Volatile private var timeInfo = ""
    private var slotInfo = ""
    private var lastGnssCheckMs = 0L
    private val radioClock = Clock { System.currentTimeMillis() + clockOffsetMs }

    @Volatile private var running = false
    @Volatile private var pttLost = false
    private val warnings = ArrayList<String>()

    private var gateway: Gateway? = null
    private var server: CotServer? = null
    private var transport: FrameTransport? = null
    private var softModem: SoftModemTransport? = null
    /** The KISS link (TCP or Bluetooth) when a TNC is the backend, to show whether it is up. */
    @Volatile private var kiss: KissStreamTransport? = null
    private var audioSource: AndroidAudioSource? = null
    private var audioSink: AndroidAudioSink? = null
    private var serialPtt: SerialPtt? = null
    private var bluetoothPtt: BluetoothPtt? = null
    private var txThread: Thread? = null
    private var tickThread: Thread? = null
    private var backendInfo = ""
    private var cipherInfo = ""
    private var repeatInfo = ""

    private val stats = ChannelStats(Clock.SYSTEM)
    private var lastSampleMs = 0L
    private var lastFecRecovered = 0L
    private var lastQueueDropped = 0

    /** Set once the previous run's store-and-forward state has been read, so an empty gateway never overwrites it. */
    @Volatile private var stateRestored = false
    private var lastSavedState: ByteArray? = null
    private var lastSaveMs = 0L

    /** Brings everything up on a worker thread (opening audio and USB can block). */
    fun start() {
        running = true
        status.value = RadioStatus(running = true, txTimeoutS = settings.txTimeoutS)
        Thread({ startBlocking() }, "takwave-start").start()
    }

    private fun startBlocking() {
        try {
            val address = settings.address
            if (address == null) warn("No valid callsign set. Receiving only; nothing will be transmitted.")

            var crypto: PayloadCipher? = null
            var canTransmit = address != null
            if (settings.encryption != EncryptionMode.OFF) {
                try {
                    crypto = keyFiles.buildCipher(settings)
                    info("Payload encryption ON: ${crypto?.description}. This is not permitted on amateur frequencies.")
                    if (crypto?.canDecrypt == false) warn("Send-only encryption: no private key is loaded, so encrypted traffic from others cannot be read.")
                } catch (e: Exception) {
                    // Never fall back to sending in the clear when the operator asked for encryption.
                    warn("Encryption is on but cannot be used: ${e.message}. Transmitting is disabled.")
                    canTransmit = false
                }
            }

            // Hiding needs a working cipher; when the cipher failed, transmitting is already off.
            val hideSource = settings.callsignHidden && crypto != null
            if (hideSource) {
                info(
                    "Callsign hidden: frames go out as ${Gateway.HIDDEN_SOURCE} with $address inside the encrypted payload. " +
                        "Identify your station by other means as your license requires.",
                )
            }
            cipherInfo = (crypto?.description ?: "") + if (hideSource) ", callsign hidden" else ""
            val gw = Gateway(
                clock = Clock.SYSTEM,
                source = if (canTransmit) address else null,
                codec = PacketCodec(crypto),
                pliConfig = PliConfig(
                    baseIntervalS = settings.pliBaseS,
                    minIntervalS = settings.pliMinS,
                    distanceM = settings.pliDistanceM.toDouble(),
                    headingDeg = settings.pliHeadingDeg,
                ),
                repeatConfig = RepeatConfig(
                    resendOwn = settings.resendOwn,
                    relay = settings.relay,
                    holdMs = settings.repeatHoldMin * 60_000L,
                    maxRepeats = settings.repeatMax,
                    minGapMs = settings.repeatGapS * 1000L,
                    budgetPer10Min = settings.repeatBudget,
                ),
                hideSource = hideSource,
            )
            gateway = gw
            restoreState(gw)
            repeatInfo = when {
                settings.relay -> "Store and forward: relay station"
                settings.resendOwn -> "Store and forward: own messages"
                else -> ""
            }
            if (settings.relay) {
                info(
                    "Relay on: chat and markers heard from other stations are passed on, at most " +
                        "${settings.repeatBudget} repeats per 10 minutes.",
                )
            }

            val srv = CotServer(
                port = settings.takPort,
                allowLan = settings.allowLan,
                onEvent = { clientId, xml -> onClientEvent(clientId, xml) },
                onClientsChanged = { },
                onLog = { info(it) },
            )
            try {
                srv.start()
                server = srv
            } catch (e: Exception) {
                warn("TAK server could not listen on port ${settings.takPort}: ${e.message}")
            }

            val tr = try {
                when (settings.backend) {
                    ModemBackend.SOFT -> buildSoftModem()
                    ModemBackend.KISS_TCP -> {
                        backendInfo = "KISS TNC ${settings.kissHost}:${settings.kissPort}"
                        if (settings.fx25 != Fx25Mode.OFF) info("FX.25 is set up in the TNC itself when using KISS; the FX.25 setting is not used.")
                        KissTcpTransport(settings.kissHost, settings.kissPort, onStatus = { info(it) }).also { kiss = it }
                    }
                    ModemBackend.KISS_BLUETOOTH -> buildBluetoothKiss()
                }
            } catch (e: Exception) {
                warn("Radio interface failed to start: ${e.message}")
                null
            }
            transport = tr
            if (!running) {
                // stop() was called while we were still starting.
                releaseAll()
                return
            }
            tr?.start { frame -> onRfFrame(frame) }
            if (tr != null) {
                txThread = Thread({ transmitLoop(gw, tr) }, "takwave-tx").apply { isDaemon = true; start() }
            }
            tickThread = Thread({ tickLoop() }, "takwave-status").apply { isDaemon = true; start() }
            info("Gateway started as ${address ?: "(no callsign)"}; $backendInfo")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            warn("Start failed: ${e.message}")
            publish()
        }
    }

    private fun buildSoftModem(): FrameTransport {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "microphone permission has not been granted"
        }
        val input = AudioDevices.findInput(context, settings.inputDevice)
        val output = AudioDevices.findOutput(context, settings.outputDevice)
        if (settings.inputDevice.isNotEmpty() && input == null) warn("Selected audio input is not connected; using the default input.")
        if (settings.outputDevice.isNotEmpty() && output == null) warn("Selected audio output is not connected; using the default output.")

        val ptt: Ptt = when (settings.pttMethod) {
            PttMethod.SERIAL -> try {
                SerialPtt.open(context, settings.pttDevice, settings.pttLine, settings.pttInverted).also { serialPtt = it }
            } catch (e: Exception) {
                // Without working PTT a transmission would just be audio into an unkeyed radio.
                pttLost = true
                // A USB sound card without any serial adapter is most likely a cable that keys the radio itself.
                val hint = if (SerialDevices.list(context).isEmpty() && AudioDevices.hasUsbAudio(context)) {
                    " A USB sound card is connected but it has no serial port. If it is a cable that keys the radio " +
                        "from the audio, such as the BTECH APRS-K1 PRO, set the PTT method to VOX and leave the radio's own VOX off."
                } else {
                    ""
                }
                warn("Serial PTT unavailable: ${e.message}. Transmitting is disabled.$hint")
                NoPtt
            }
            PttMethod.BLUETOOTH -> try {
                val key = try {
                    PttCommand.parse(settings.btKeyText)
                } catch (e: IllegalArgumentException) {
                    error("the key command is not valid (${e.message})")
                }
                val unkey = try {
                    PttCommand.parse(settings.btUnkeyText)
                } catch (e: IllegalArgumentException) {
                    error("the unkey command is not valid (${e.message})")
                }
                BluetoothPtt.open(context, settings.btPttDevice, key, unkey) { onBluetoothPttLost() }.also { bluetoothPtt = it }
            } catch (e: Exception) {
                // Same rule as serial PTT: never play audio into a radio that cannot be keyed.
                pttLost = true
                warn("Bluetooth PTT unavailable: ${e.message}. Transmitting is disabled.")
                NoPtt
            }
            PttMethod.VOX, PttMethod.NONE -> NoPtt
        }
        val source = AndroidAudioSource(context, input).also { audioSource = it }
        val sink = AndroidAudioSink(output).also { audioSink = it }
        backendInfo = "Soft modem, PTT: ${settings.pttMethod.label}" +
            if (settings.fx25 != Fx25Mode.OFF) ", FX.25 (${settings.fx25.checkBytes} check bytes)" else ""
        val slots = if (settings.slotted) buildSlots() else null
        return SoftModemTransport(
            source, sink, ptt,
            clock = radioClock,
            config = ModemConfig(
                slots = slots,
                txDelayMs = settings.txDelayMs,
                txTailMs = settings.txTailMs,
                amplitude = settings.txLevel / 100.0,
                slotTimeMs = settings.slotTimeMs,
                persistence = settings.persistence,
                energyThreshold = settings.energyThreshold / 100f,
                txTimeoutMs = settings.txTimeoutS * 1000,
                fx25CheckBytes = settings.fx25.checkBytes,
            ),
        ).also { softModem = it }
    }

    /**
     * A radio with a built-in KISS TNC (for example the BTECH UV-PRO) or a Bluetooth TNC, reached
     * over a Bluetooth serial link. The TNC keys the radio itself, so there is no PTT to manage
     * here: a frame cut off by a dropped link is never sent.
     */
    private fun buildBluetoothKiss(): FrameTransport {
        val address = settings.kissBtDevice
        check(address.isNotEmpty()) { "no Bluetooth TNC has been chosen (Settings > Radio & audio)" }
        if (!BluetoothDevices.hasPermission(context)) {
            warn("The Nearby devices (Bluetooth) permission has not been granted, so the Bluetooth TNC cannot be reached.")
        }
        val label = BluetoothDevices.paired(context).firstOrNull { it.address == address }?.label ?: address
        backendInfo = "KISS TNC over Bluetooth: $label"
        if (settings.fx25 != Fx25Mode.OFF) info("FX.25 is up to the TNC when using KISS; the FX.25 setting is not used.")
        return KissStreamTransport("Bluetooth TNC $label", onStatus = { info(it) }) {
            val socket = BluetoothDevices.connectSerial(context, address)
            object : KissLink {
                override val input = socket.inputStream
                override val output = socket.outputStream
                override fun close() = socket.close()
            }
        }.also { kiss = it }
    }

    private fun buildSlots(): SlotConfig? {
        return try {
            val count = settings.slotCount
            val index = if (settings.slotIndex in 0 until count) settings.slotIndex
            else SlotSchedule.autoSlot(settings.address?.toString() ?: "", count)
            val config = SlotConfig(slotMs = settings.slotLengthMs, slotCount = count, slotIndex = index)
            slotInfo = "Time slot ${index + 1} of $count, ${settings.slotLengthMs / 1000.0} s each" +
                if (settings.slotIndex < 0) " (slot chosen from callsign)" else ""
            // The longest packet must fit a slot, or it would always spill into a neighbor's.
            val longestMs = settings.txDelayMs + settings.txTailMs + (272 * 8 * 1000L / 1200) * 7 / 6
            if (longestMs + config.guardMs > settings.slotLengthMs) {
                warn("The slot length is shorter than the longest packet (about ${longestMs / 100 / 10.0} s). Long chat messages will overrun their slot.")
            }
            if (settings.timeSource == TimeSource.GNSS) updateGnssOffset(force = true) else timeInfo = "phone clock"
            config
        } catch (e: IllegalArgumentException) {
            warn("Time slots are misconfigured (${e.message}); using plain carrier sensing.")
            null
        }
    }

    /**
     * Reads GNSS time from the platform (available when any app, typically ATAK, has the GPS
     * running) and records how far the phone clock is from it. Falls back to the phone clock.
     */
    private fun updateGnssOffset(force: Boolean = false) {
        val nowMs = System.currentTimeMillis()
        if (!force && nowMs - lastGnssCheckMs < GNSS_CHECK_PERIOD_MS) return
        lastGnssCheckMs = nowMs
        try {
            val gnss = android.os.SystemClock.currentGnssTimeClock().millis()
            clockOffsetMs = gnss - System.currentTimeMillis()
            timeInfo = "GNSS time (phone clock is off by ${-clockOffsetMs} ms)"
        } catch (e: Exception) {
            // No GNSS time yet. Keep the last offset if there was one.
            timeInfo = if (clockOffsetMs == 0L) "GNSS time unavailable, using phone clock" else "GNSS time lost, holding last correction"
        }
    }

    // ---- Data paths ----

    private fun onClientEvent(clientId: Int, xml: String) {
        val gw = gateway ?: return
        val outcome = gw.onClientEvent(xml)
        if (outcome.relay) server?.sendToAll(xml, exceptClientId = clientId)
        outcome.reply?.let { server?.sendTo(clientId, it) }
        if (outcome.note != null) Log.d(TAG, "client $clientId: ${outcome.note}")
    }

    private fun onRfFrame(frame: ByteArray) {
        val gw = gateway ?: return
        val outcome = gw.onRfFrame(frame)
        val hex = PacketCodec.toHex(frame)
        if (outcome.xml != null && outcome.message != null) {
            val lock = when {
                outcome.hiddenSource -> " [encrypted, callsign hidden]"
                outcome.encrypted -> " [encrypted]"
                else -> ""
            }
            val via = if (outcome.relayed) " [relayed]" else ""
            val age = when {
                outcome.ageS <= 0 -> ""
                outcome.ageS < 120 -> " [sent ${outcome.ageS} s ago]"
                else -> " [sent ${outcome.ageS / 60} min ago]"
            }
            log.add(LogKind.RX, "${outcome.source}: ${outcome.message!!.summary()}$lock$via$age", hex, frame.size)
            server?.sendToAll(outcome.xml!!)
        } else {
            log.add(LogKind.RX, "${outcome.source ?: "?"}: dropped (${outcome.note})", hex, frame.size)
        }
        stats.count(
            when (outcome.kind) {
                RxKind.DELIVERED -> ChannelEvent.RX_DELIVERED
                RxKind.REPEAT -> ChannelEvent.RX_REPEAT
                RxKind.OTHER -> ChannelEvent.RX_OTHER
                RxKind.INVALID -> ChannelEvent.RX_INVALID
            },
        )
    }

    private fun transmitLoop(gw: Gateway, tr: FrameTransport) {
        while (running) {
            try {
                val item = if (pttLost) null else gw.pollFrame()
                if (item == null) {
                    Thread.sleep(IDLE_POLL_MS)
                    continue
                }
                if (tr.send(item.frame)) {
                    stats.recordTx(tr.lastAirtimeMs, repeat = item.kind != TxKind.NEW)
                    val prefix = when (item.kind) {
                        TxKind.NEW -> ""
                        TxKind.RESEND -> "[resend] "
                        TxKind.RELAY -> "[relay] "
                    }
                    log.add(LogKind.TX, prefix + item.message.summary(), PacketCodec.toHex(item.frame), item.frame.size)
                } else {
                    stats.count(ChannelEvent.TX_FAILED)
                    log.add(LogKind.ERROR, "TX failed: ${tr.lastError} (${item.message.summary()})")
                    Thread.sleep(IDLE_POLL_MS)
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.e(TAG, "transmit loop", e)
                log.add(LogKind.ERROR, "TX error: ${e.message}")
            }
        }
    }

    private fun tickLoop() {
        while (running) {
            sampleStats()
            publish()
            val now = System.currentTimeMillis()
            if (now - lastSaveMs >= SAVE_PERIOD_MS) {
                lastSaveMs = now
                saveState()
            }
            try {
                Thread.sleep(STATUS_PERIOD_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /** Feeds the channel statistics: listening time (soft modem only) and counters kept elsewhere. */
    private fun sampleStats() {
        val now = System.currentTimeMillis()
        val elapsed = if (lastSampleMs == 0L) 0L else (now - lastSampleMs).coerceAtMost(1_000L)
        lastSampleMs = now
        val modem = softModem
        if (modem != null) {
            // While transmitting the receiver is deaf, so that time is neither busy nor idle.
            when (modem.state) {
                com.fearmikey.takwave.core.io.RfState.TX -> {}
                com.fearmikey.takwave.core.io.RfState.RX -> stats.sampleChannel(elapsed, busy = true)
                com.fearmikey.takwave.core.io.RfState.IDLE -> stats.sampleChannel(elapsed, busy = false)
            }
            val fec = modem.fecRecovered
            stats.count(ChannelEvent.RX_REPAIRED, (fec - lastFecRecovered).toInt())
            lastFecRecovered = fec
        }
        val dropped = gateway?.queueDropped ?: 0
        stats.count(ChannelEvent.QUEUE_DROPPED, dropped - lastQueueDropped)
        lastQueueDropped = dropped
    }

    // ---- Store-and-forward persistence ----

    private fun restoreState(gw: Gateway) {
        val saved = stateFile.read()
        if (saved != null) {
            val result = gw.restoreState(saved)
            if (result.held > 0) info("Store and forward: ${result.held} held message(s) restored from the last session.")
            result.note?.let { info("Store and forward: $it.") }
        }
        stateRestored = true
    }

    /** Writes held messages to storage when they changed. Called from the status thread and on stop. */
    @Synchronized
    private fun saveState() {
        val gw = gateway ?: return
        if (!stateRestored) return
        try {
            val state = gw.saveState()
            if (lastSavedState?.contentEquals(state) == true) return
            stateFile.write(state)
            lastSavedState = state
        } catch (e: Exception) {
            Log.w(TAG, "saving store-and-forward state", e)
        }
    }

    private fun publish() {
        if (settings.slotted && settings.timeSource == TimeSource.GNSS && softModem != null) updateGnssOffset()
        val tr = transport
        val gw = gateway
        status.value = RadioStatus(
            running = running,
            clients = server?.clientCount ?: 0,
            rf = tr?.state ?: com.fearmikey.takwave.core.io.RfState.IDLE,
            level = tr?.inputLevel ?: 0f,
            peak = softModem?.inputPeak ?: 0f,
            queueDepth = gw?.queueDepth ?: 0,
            heldCount = gw?.heldCount ?: 0,
            repeatInfo = repeatInfo,
            heard = gw?.heardStations() ?: emptyList(),
            localPosition = gw?.localPosition,
            softModem = softModem != null,
            txTimeoutS = settings.txTimeoutS,
            backendInfo = backendInfo + if (kiss?.connected == false) " (not connected)" else "",
            cipherInfo = cipherInfo,
            slotInfo = if (slotInfo.isEmpty()) "" else "$slotInfo; $timeInfo",
            warnings = synchronized(warnings) { warnings.toList() },
            ownAlerts = gw?.ownAlerts() ?: emptyList(),
            heardAlerts = gw?.heardAlerts() ?: emptyList(),
            health = stats.snapshot(),
        )
    }

    // ---- Calibration ----

    /** Keys the radio and sends a steady tone. Returns immediately; the result goes to the log. */
    fun sendTone(tone: CalibrationTone, seconds: Int) {
        val modem = softModem
        if (modem == null || pttLost) {
            log.add(LogKind.ERROR, "Calibration tone needs the soft modem with working PTT")
            return
        }
        Thread({
            val ms = seconds.coerceIn(1, maxOf(1, settings.txTimeoutS - 1)) * 1000
            val amplitude = settings.txLevel / 100.0
            val modulator = AfskModulator()
            val audio = when (tone) {
                CalibrationTone.MARK -> modulator.tone(Bell202.MARK_HZ, ms, amplitude)
                CalibrationTone.SPACE -> modulator.tone(Bell202.SPACE_HZ, ms, amplitude)
                CalibrationTone.ALTERNATING -> modulator.alternatingTones(ms, amplitude)
            }
            if (modem.sendRaw(audio)) log.add(LogKind.TX, "Calibration tone ${tone.name} for ${ms / 1000} s")
            else log.add(LogKind.ERROR, "Calibration tone failed: ${modem.lastError}")
        }, "takwave-tone").start()
    }

    /** Queues one PLI that identifies this station, at the last local position if one is known. */
    fun sendTestPli() {
        val gw = gateway
        val address = settings.address
        if (gw == null || address == null) {
            log.add(LogKind.ERROR, "Test PLI needs a running gateway and a callsign")
            return
        }
        val pos = gw.localPosition
        val pli = PliMessage(
            uid = "TAKWAVE-$address",
            callsign = address.toString(),
            cotType = "a-f-G-U-C",
            latE7 = ((pos?.first ?: 0.0) * 1e7).toLong().toInt(),
            lonE7 = ((pos?.second ?: 0.0) * 1e7).toLong().toInt(),
            team = Team.CYAN,
            role = Role.TEAM_MEMBER,
            intervalS = settings.pliBaseS,
        )
        if (gw.enqueue(pli)) log.add(LogKind.INFO, "Test PLI queued" + if (pos == null) " (no local position yet, sent as 0,0)" else "")
        else log.add(LogKind.ERROR, "Test PLI refused: transmitting is disabled or the queue is full")
    }

    // ---- Safety and shutdown ----

    /** Called when a USB device is unplugged. Stops transmitting if it was the PTT adapter. */
    fun onUsbDetached(deviceKey: String) {
        val ptt = serialPtt ?: return
        if (ptt.deviceKey != deviceKey) return
        pttLost = true
        try {
            ptt.unkey()
        } catch (e: Exception) {
            // The adapter is gone; its control lines dropped with it.
        }
        ptt.close()
        softModem?.let { _ -> audioSink?.abort() }
        warn("PTT adapter unplugged. Transmitting is disabled until the service is restarted.")
    }

    /**
     * Called from the Bluetooth PTT thread when the serial link drops. The unkey command can no
     * longer reach the radio, so all that can be done is to stop sending audio and stop keying.
     */
    private fun onBluetoothPttLost() {
        pttLost = true
        audioSink?.abort()
        if (running) warn("Bluetooth PTT link lost. Transmitting is disabled until the service is restarted.")
    }

    fun stop() {
        if (!running) return
        running = false
        txThread?.interrupt()
        tickThread?.interrupt()
        // Closing the transport unkeys PTT first thing; do it off the main thread.
        Thread({ releaseAll() }, "takwave-stop").start()
        status.value = RadioStatus(running = false)
        log.add(LogKind.INFO, "Gateway stopped")
    }

    private fun releaseAll() {
        try { transport?.close() } catch (e: Exception) { Log.w(TAG, "transport close", e) }
        // After the transmitter is released: keep what store and forward was holding for next time.
        saveState()
        try { serialPtt?.close() } catch (e: Exception) { Log.w(TAG, "ptt close", e) }
        try { bluetoothPtt?.close() } catch (e: Exception) { Log.w(TAG, "bluetooth ptt close", e) }
        try { server?.stop() } catch (e: Exception) { Log.w(TAG, "server stop", e) }
        try { audioSource?.close() } catch (e: Exception) { Log.w(TAG, "audio in close", e) }
        try { audioSink?.close() } catch (e: Exception) { Log.w(TAG, "audio out close", e) }
    }

    private fun info(message: String) = log.add(LogKind.INFO, message)

    private fun warn(message: String) {
        synchronized(warnings) { warnings.add(message) }
        log.add(LogKind.ERROR, message)
    }

    private companion object {
        const val TAG = "TAKwave"
        const val IDLE_POLL_MS = 100L
        const val STATUS_PERIOD_MS = 100L
        const val GNSS_CHECK_PERIOD_MS = 30_000L
        const val SAVE_PERIOD_MS = 30_000L
    }
}
