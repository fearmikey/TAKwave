// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.io

import com.fearmikey.takwave.core.ip.EthernetIpLink
import com.fearmikey.takwave.core.ip.IpFraming
import com.fearmikey.takwave.core.ip.UsbNetFraming
import com.fearmikey.takwave.core.ip.IpPacketLink
import com.fearmikey.takwave.core.ip.MulticastDelivery
import com.fearmikey.takwave.core.ip.Ipv4
import java.io.IOException
import java.io.OutputStream

/**
 * One open connection to the radio and how to speak IP on it. [description] names the link for
 * status messages. [readBufferSize] must hold the largest single read: for a USB network link one
 * read is one USB transfer, and [openIp] builds the IP layer that makes sense of what is read.
 *
 * Use [serial] for PPP or SLIP on a serial data cable, [usbNetwork] for a radio whose USB data
 * link is a virtual network adapter (RNDIS or CDC-ECM, as on APX portables and MOTOTRBO radios). Addresses are IPv4
 * addresses as Ints (see [Ipv4]): the local one is this end (the radio calls it the mobile
 * computer), the radio one the radio's end. With PPP or DHCP both are only starting points.
 */
class P25Line(
    val link: KissLink,
    val description: String,
    val readBufferSize: Int = 4096,
    val openIp: (output: (ByteArray) -> Unit, onPacket: (ByteArray) -> Unit, nowMs: () -> Long) -> IpPacketLink,
) {
    companion object {
        fun serial(link: KissLink, framing: IpFraming, localAddress: Int, radioAddress: Int) =
            P25Line(link, framing.name) { output, onPacket, nowMs ->
                IpPacketLink.create(framing, localAddress, radioAddress, output, onPacket, nowMs)
            }

        /**
         * A USB network link. Each read from [link] must return exactly one USB transfer of at most
         * [maxTransferSize] bytes, and each write is sent as one transfer. With [useDhcp] the radio
         * is asked for the addresses first. [multicastDelivery] decides where multicast destinations
         * (DMR talkgroup addresses) go on the link.
         */
        fun usbNetwork(
            link: KissLink,
            framing: UsbNetFraming,
            mac: ByteArray,
            localAddress: Int,
            radioAddress: Int,
            useDhcp: Boolean,
            maxTransferSize: Int,
            multicastDelivery: MulticastDelivery = MulticastDelivery.BROADCAST,
        ) = P25Line(link, "USB network (${framing.label})", readBufferSize = maxOf(maxTransferSize, 2048)) { output, onPacket, nowMs ->
            EthernetIpLink(
                framing, mac, localAddress, radioAddress, useDhcp, output, onPacket, nowMs,
                multicastDelivery = multicastDelivery,
            )
        }
    }
}

/**
 * Settings for [P25DataTransport]. Each frame is sent as one UDP datagram from and to [port], to
 * every address in [destinations]: the limited broadcast 255.255.255.255, or the air-interface
 * addresses of the other radios. [throughputBps] and [keyUpMs] only feed the air time estimate
 * that paces sending.
 */
data class P25DataConfig(
    val destinations: List<Int>,
    val port: Int = DEFAULT_PORT,
    val throughputBps: Int = P25_THROUGHPUT_BPS,
    val keyUpMs: Int = P25_KEY_UP_MS,
) {
    init {
        require(destinations.isNotEmpty()) { "at least one destination address is needed" }
        require(port in 1..65535) { "port out of range" }
        require(throughputBps > 0 && keyUpMs >= 0)
    }

    companion object {
        /** UDP port for TAKwave frames, clear of the ports Motorola radios use for their own services (4001-4012, 8675). */
        const val DEFAULT_PORT = 50125

        /** Pacing for P25 packet data (a few kbit/s at best on a 9.6 kbit/s channel). */
        const val P25_THROUGHPUT_BPS = 4_000
        const val P25_KEY_UP_MS = 300

        /**
         * Pacing for a DMR (MOTOTRBO) data call: one timeslot carries roughly 1.5 kbit/s of user
         * data at rate 3/4 once the data header is counted, and channel access plus the data
         * header and any preamble take about half a second. Kept on the slow side so the radio's
         * queue never backs up.
         */
        const val DMR_THROUGHPUT_BPS = 1_200
        const val DMR_KEY_UP_MS = 500

        /** Settings for a MOTOTRBO radio: [DMR_THROUGHPUT_BPS] and [DMR_KEY_UP_MS] pacing. */
        fun dmr(destinations: List<Int>, port: Int = DEFAULT_PORT) =
            P25DataConfig(destinations, port, throughputBps = DMR_THROUGHPUT_BPS, keyUpMs = DMR_KEY_UP_MS)
    }
}

/**
 * Carries TAKwave's AX.25 frames over the packet data service of a digital radio: a P25
 * (Motorola ASTRO 25 or APX) radio with packet data, on a serial data cable or a USB network
 * link, or a DMR (MOTOTRBO) radio on its USB network link (see
 * [com.fearmikey.takwave.core.ip.DmrIp] for its addresses). The radio does the modulation, channel access and keying with its own modem; TAKwave
 * speaks IP to it (PPP, SLIP, or Ethernet over RNDIS / CDC-ECM) and sends each frame (no FCS) as
 * the payload of one UDP datagram.
 *
 * [connect] opens the serial line, possibly after detecting its settings (see
 * [com.fearmikey.takwave.core.ip.P25Probe]), and may block; it throws with a user-readable
 * message. The line is reopened after [reconnectDelayMs] when it fails, drops, or PPP gives up.
 * [onStatus] gets connect, link-up and failure messages, never the same one twice in a row.
 * [onLinkUp] is told the addresses in use each time the IP link comes up (with PPP, as negotiated).
 *
 * The radio has only a small queue and the channel is slow, so [send] waits for the estimated
 * air time after handing a frame over (unless [pace] is false). TAKwave's own priority queue
 * therefore decides what goes next, not the radio's buffer.
 *
 * Received UDP datagrams to [P25DataConfig.port] are handed to the listener as frames; pings are
 * answered so the link can be checked from the radio side. Nothing else is forwarded, but
 * [onIgnored] is told about everything else that arrives (protocol, addresses, ports and the
 * first bytes), so the radio's own traffic can be inspected while setting up.
 */
class P25DataTransport(
    private val name: String,
    private val config: P25DataConfig,
    private val reconnectDelayMs: Long = 5_000,
    private val onStatus: (String) -> Unit = {},
    private val pace: Boolean = true,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onLinkUp: (localAddress: Int, radioAddress: Int) -> Unit = { _, _ -> },
    private val onIgnored: ((String) -> Unit)? = null,
    private val connect: () -> P25Line,
) : FrameTransport {
    @Volatile private var running = false
    @Volatile private var line: KissLink? = null
    @Volatile private var link: IpPacketLink? = null
    @Volatile private var upReported = false
    private var reader: Thread? = null
    private var ticker: Thread? = null
    private val writeLock = Any()
    private val statusLock = Any()
    private var lastStatus: String? = null
    private var nextId = 1

    @Volatile
    override var lastError: String? = null
        private set

    @Volatile
    override var lastAirtimeMs: Long = 0L
        private set

    /** True while IP packets can be exchanged with the radio. */
    val connected: Boolean get() = link?.up == true

    override val state: RfState get() = RfState.IDLE
    override val inputLevel: Float get() = 0f

    override fun start(listener: FrameListener) {
        running = true
        reader = Thread({ run(listener) }, "takwave-p25").apply {
            isDaemon = true
            start()
        }
        ticker = Thread({ tickLoop() }, "takwave-p25-tick").apply {
            isDaemon = true
            start()
        }
    }

    private fun report(message: String) {
        synchronized(statusLock) {
            if (message == lastStatus) return
            lastStatus = message
        }
        try {
            onStatus(message)
        } catch (e: Exception) {
            // A failing observer must not take the link down.
        }
    }

    private fun run(listener: FrameListener) {
        while (running) {
            var opened = false
            try {
                val target = connect()
                val l = target.link
                line = l
                // close() may have run while connect() was blocking and found no line to close.
                if (!running) return
                val out = l.output
                upReported = false
                val ip = target.openIp(
                    { bytes -> write(out, bytes) },
                    { packet -> onIpPacket(packet, listener) },
                    nowMs,
                )
                link = ip
                opened = true
                lastError = null
                report("$name: connected (${target.description})" + if (target.description == IpFraming.PPP.name) ", negotiating" else "")
                ip.start()
                checkUp(ip)
                val input = l.input
                val buf = ByteArray(target.readBufferSize)
                while (running) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) ip.feed(buf, 0, n)
                    checkUp(ip)
                    ip.failure?.let { throw IOException(it) }
                }
                if (running) report("$name: link closed")
            } catch (e: Exception) {
                if (running) {
                    val reason = link?.failure ?: e.message ?: e.javaClass.simpleName
                    lastError = "$name: $reason"
                    report(if (opened) "$name: link lost ($reason)" else "$name: $reason")
                }
            } finally {
                link = null
                try { line?.close() } catch (e: Exception) { /* already closed */ }
                line = null
            }
            if (running) {
                try {
                    Thread.sleep(reconnectDelayMs)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun tickLoop() {
        while (running) {
            try {
                Thread.sleep(TICK_MS)
            } catch (e: InterruptedException) {
                return
            }
            val ip = link ?: continue
            try {
                ip.tick()
                checkUp(ip)
                if (ip.failure != null) {
                    // Closing the line ends the reader's blocking read; it then reports and reconnects.
                    try { line?.close() } catch (e: Exception) { /* already closed */ }
                }
            } catch (e: Exception) {
                try { line?.close() } catch (ignored: Exception) { /* already closed */ }
            }
        }
    }

    private fun checkUp(ip: IpPacketLink) {
        if (ip.up && !upReported) {
            upReported = true
            report("$name: IP link up, this end ${Ipv4.format(ip.localAddress)}, radio ${Ipv4.format(ip.peerAddress)}")
            try {
                onLinkUp(ip.localAddress, ip.peerAddress)
            } catch (e: Exception) {
                // An observer failing must not take the link down.
            }
        }
    }

    private fun write(out: OutputStream, bytes: ByteArray) {
        synchronized(writeLock) {
            out.write(bytes)
            out.flush()
        }
    }

    private fun onIpPacket(packet: ByteArray, listener: FrameListener) {
        val ip = Ipv4.parsePacket(packet) ?: return ignored { "unreadable IP packet, ${packet.size} bytes: ${hex(packet)}" }
        when (ip.protocol) {
            Ipv4.PROTO_UDP -> {
                val udp = Ipv4.parseUdp(ip) ?: return ignored { "bad UDP datagram from ${Ipv4.format(ip.source)}" }
                if (udp.destinationPort == config.port && udp.payload.isNotEmpty()) {
                    listener.onFrame(udp.payload)
                } else {
                    ignored {
                        "UDP ${Ipv4.format(udp.source)}:${udp.sourcePort} -> ${Ipv4.format(udp.destination)}:${udp.destinationPort}, " +
                            "${udp.payload.size} bytes: ${hex(udp.payload)}"
                    }
                }
            }
            Ipv4.PROTO_ICMP -> {
                val l = link ?: return
                Ipv4.echoReply(ip, l.localAddress, nextId())?.let { l.send(it) }
            }
            else -> ignored { "IP protocol ${ip.protocol} ${Ipv4.format(ip.source)} -> ${Ipv4.format(ip.destination)}, ${ip.payload.size} bytes" }
        }
    }

    private inline fun ignored(describe: () -> String) {
        val observer = onIgnored ?: return
        try {
            observer("$name: ignored " + describe())
        } catch (e: Exception) {
            // An observer failing must not take the link down.
        }
    }

    @Synchronized
    private fun nextId(): Int {
        val id = nextId
        nextId = (nextId + 1) and 0xFFFF
        return id
    }

    override fun send(frame: ByteArray): Boolean {
        val l = link
        if (l == null || !l.up) {
            lastError = if (l == null) "$name is not connected" else "$name: IP link to the radio is not up yet"
            return false
        }
        try {
            for (destination in config.destinations) {
                val packet = Ipv4.buildUdp(l.localAddress, destination, config.port, config.port, frame, nextId())
                if (!l.send(packet)) {
                    lastError = "$name: IP link to the radio went down"
                    return false
                }
            }
        } catch (e: Exception) {
            lastError = "$name: write failed: ${e.message ?: e.javaClass.simpleName}"
            try { line?.close() } catch (ignored: Exception) { /* already closed */ }
            return false
        }
        lastAirtimeMs = estimateAirtimeMs(frame.size) * config.destinations.size
        if (pace) {
            try {
                Thread.sleep(lastAirtimeMs)
            } catch (e: InterruptedException) {
                // Already handed to the radio; keep the interrupt for the caller.
                Thread.currentThread().interrupt()
            }
        }
        return true
    }

    /** Key-up plus the datagram (frame, UDP and IP headers) at the configured throughput. */
    fun estimateAirtimeMs(frameSize: Int): Long =
        config.keyUpMs + (frameSize + Ipv4.HEADER_SIZE + Ipv4.UDP_HEADER_SIZE) * 8L * 1000L / config.throughputBps

    override fun close() {
        running = false
        // Refuse further sends at once, before the reader thread has noticed.
        link = null
        try { line?.close() } catch (e: Exception) { /* already closed */ }
        reader?.interrupt()
        ticker?.interrupt()
    }

    companion object {
        private const val TICK_MS = 500L

        /** Up to [max] bytes as hex, with "..." when there are more. */
        fun hex(data: ByteArray, max: Int = 48): String =
            data.take(max).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) } + if (data.size > max) " ..." else ""
    }
}
