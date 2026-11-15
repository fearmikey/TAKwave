// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.io

import com.fearmikey.takwave.core.modem.AfskDemodulator
import com.fearmikey.takwave.core.modem.AfskModulator
import com.fearmikey.takwave.core.modem.Bell202
import com.fearmikey.takwave.core.sched.ChannelAccess
import com.fearmikey.takwave.core.sched.Clock
import com.fearmikey.takwave.core.sched.SlotConfig
import com.fearmikey.takwave.core.sched.SlotSchedule
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class ModemConfig(
    val txDelayMs: Int = 300,
    val txTailMs: Int = 30,
    /** Output level, 0.0-1.0 of full scale. */
    val amplitude: Double = 0.5,
    val slotTimeMs: Int = 100,
    val persistence: Int = 63,
    /** Treat the channel as busy above this RMS input level (0.0-1.0). 0 disables the check. */
    val energyThreshold: Float = 0f,
    /** Hard limit on a single key-down. The transmitter is force-unkeyed after this long. */
    val txTimeoutMs: Int = 10_000,
    /** How long to wait for a clear channel before giving up on a frame. */
    val maxChannelWaitMs: Long = 30_000,
    /** Time-slotted transmit; null for plain CSMA. */
    val slots: SlotConfig? = null,
    /** Send frames as FX.25 with this many check bytes (16, 32 or 64); 0 sends plain AX.25. FX.25 is always received. */
    val fx25CheckBytes: Int = 0,
)

/**
 * The software TNC: AFSK modem + audio + PTT. Receives continuously on its own thread and
 * transmits half-duplex with p-persistent CSMA.
 *
 * PTT safety: the transmitter is unkeyed in a `finally` block after every transmission, by a
 * watchdog if a transmission outlasts [ModemConfig.txTimeoutMs], and by [close].
 */
class SoftModemTransport(
    private val source: AudioSource,
    private val sink: AudioSink,
    private val ptt: Ptt,
    private val config: ModemConfig = ModemConfig(),
    /** Time source for slot timing; pass a GNSS-disciplined clock when one is available. */
    clock: Clock = Clock.SYSTEM,
) : FrameTransport {
    private val modulator = AfskModulator()
    @Volatile private var listener: FrameListener? = null
    private val demodulator = AfskDemodulator { frame -> listener?.onFrame(frame) }
    private val access = ChannelAccess(
        slotTimeMs = config.slotTimeMs,
        persistence = config.persistence,
        slots = config.slots?.let { SlotSchedule(it) },
        clock = clock,
    ) { channelBusy() }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "takwave-tx-watchdog").apply { isDaemon = true }
    }
    private val txLock = Any()

    @Volatile private var running = false
    @Volatile private var transmitting = false
    @Volatile private var timedOut = false
    @Volatile private var resetDemod = false
    private var rxThread: Thread? = null

    @Volatile
    override var lastError: String? = null
        private set

    override val state: RfState
        get() = when {
            transmitting -> RfState.TX
            demodulator.dcd -> RfState.RX
            else -> RfState.IDLE
        }

    override val inputLevel: Float get() = demodulator.rmsLevel

    val inputPeak: Float get() = demodulator.peakLevel

    /** Frames received only because FX.25 repaired them, since the transport started. */
    val fecRecovered: Long get() = demodulator.fecRecovered

    @Volatile
    override var lastAirtimeMs: Long = 0L
        private set

    private fun channelBusy(): Boolean =
        demodulator.dcd || (config.energyThreshold > 0f && demodulator.rmsLevel > config.energyThreshold)

    override fun start(listener: FrameListener) {
        this.listener = listener
        running = true
        rxThread = Thread({ receiveLoop() }, "takwave-rx").apply {
            isDaemon = true
            start()
        }
    }

    private fun receiveLoop() {
        val buf = ShortArray(Bell202.SAMPLE_RATE / 50)
        while (running) {
            val n = try {
                source.read(buf, 0, buf.size)
            } catch (e: Exception) {
                -1
            }
            if (n < 0) break
            if (n == 0) continue
            if (resetDemod) {
                resetDemod = false
                demodulator.reset()
            }
            // Half duplex: while keyed, the receiver would only hear our own audio.
            if (!transmitting) demodulator.process(buf, 0, n)
        }
    }

    override fun send(frame: ByteArray): Boolean {
        val audio = modulator.modulate(frame, config.txDelayMs, config.txTailMs, config.amplitude, config.fx25CheckBytes)
        return transmit(audio, useCsma = true)
    }

    /** Keys the transmitter and plays [audio] as-is (calibration tones). No channel access is done. */
    fun sendRaw(audio: ShortArray): Boolean = transmit(audio, useCsma = false)

    private fun transmit(audio: ShortArray, useCsma: Boolean): Boolean {
        val durationMs = audio.size * 1000L / Bell202.SAMPLE_RATE
        if (durationMs > config.txTimeoutMs) {
            lastError = "transmission of $durationMs ms exceeds the TX timeout of ${config.txTimeoutMs} ms"
            return false
        }
        synchronized(txLock) {
            if (!running) {
                lastError = "transport is closed"
                return false
            }
            if (useCsma && !access.await(durationMs, config.maxChannelWaitMs)) {
                lastError = "no clear transmit opportunity within ${config.maxChannelWaitMs} ms"
                return false
            }
            if (!running) {
                // close() was called while this frame was waiting for the channel: do not key.
                lastError = "transport is closed"
                return false
            }
            var timer: ScheduledFuture<*>? = null
            timedOut = false
            transmitting = true
            try {
                timer = watchdog.schedule({
                    // Whatever the audio path is doing, the transmitter comes off the air now.
                    timedOut = true
                    safeUnkey()
                    try { sink.abort() } catch (e: Exception) { /* nothing more to do */ }
                }, config.txTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                ptt.key()
                sink.write(audio, 0, audio.size)
                sink.drain()
            } catch (e: Exception) {
                lastError = "transmit failed: ${e.message ?: e.javaClass.simpleName}"
                return false
            } finally {
                safeUnkey()
                timer?.cancel(false)
                resetDemod = true
                transmitting = false
            }
            if (timedOut) {
                lastError = "TX timeout: transmitter force-unkeyed after ${config.txTimeoutMs} ms"
                return false
            }
            lastAirtimeMs = durationMs
            return true
        }
    }

    private fun safeUnkey() {
        try {
            ptt.unkey()
        } catch (e: Exception) {
            lastError = "PTT release failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    override fun close() {
        running = false
        safeUnkey()
        try { sink.abort() } catch (e: Exception) { /* closing anyway */ }
        watchdog.shutdownNow()
        rxThread?.interrupt()
        listener = null
    }
}
