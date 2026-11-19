// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ip

import com.fearmikey.takwave.core.ax25.Crc16
import com.fearmikey.takwave.core.io.KissLink
import java.io.OutputStream

/** What [P25Probe] found: the line settings that got an answer, and the radio's address when it showed. */
data class P25ProbeResult(val framing: IpFraming, val baud: Int, val radioAddress: Int?)

/**
 * Finds the baud rate and framing of a radio's serial data port by asking and listening.
 *
 * At each rate in [bauds] (the first is tried first, so put the last known one there) the line is
 * opened, and two requests are written: a PPP LCP Configure-Request, and for SLIP an ICMP echo
 * request with TTL 1 to each of [radioAddresses]. A reply that passes its check (PPP FCS-16 plus a
 * PPP control or IP protocol number, or an IPv4 header checksum for SLIP) identifies the framing;
 * line noise at a wrong rate cannot pass them. Nothing is sent that a radio should put on the air:
 * LCP is link-local, and a TTL-1 ping is dropped rather than forwarded if it is not for the radio
 * itself. Our own bytes echoed back by a cable are ignored.
 *
 * A PPP radio's address is learned later, during negotiation; for SLIP it is the source of the
 * ping reply. [open] throws when the port cannot be opened at all; that ends the probe at once,
 * because another rate will not help.
 */
class P25Probe(
    private val bauds: List<Int>,
    private val localAddress: Int,
    private val radioAddresses: List<Int>,
    private val listenMs: Long = 1_500,
    private val onProgress: (String) -> Unit = {},
    private val open: (baud: Int) -> KissLink,
) {
    init {
        require(bauds.isNotEmpty())
    }

    /** Returns the settings that got an answer; throws [IllegalStateException] with a readable reason when none did. */
    fun run(): P25ProbeResult {
        for (baud in bauds) {
            onProgress("Trying $baud baud (PPP and SLIP)")
            val link = open(baud)
            try {
                listen(link, baud)?.let { return it }
            } finally {
                try { link.close() } catch (e: Exception) { /* already closed */ }
            }
        }
        error(
            "no answer from the radio at ${bauds.joinToString("/")} baud with PPP or SLIP. Check the data cable, that the " +
                "radio has the packet data option, and that its data port is enabled",
        )
    }

    private fun listen(link: KissLink, baud: Int): P25ProbeResult? {
        val detector = Detector(localAddress)
        val reader = Thread({
            val buf = ByteArray(1024)
            try {
                while (true) {
                    val n = link.input.read(buf)
                    if (n < 0) break
                    detector.feed(buf, 0, n)
                }
            } catch (e: Exception) {
                // The line was closed when this rate was given up.
            }
        }, "takwave-p25-probe").apply {
            isDaemon = true
            start()
        }
        try {
            val start = System.currentTimeMillis()
            var sent = 0
            while (true) {
                val elapsed = System.currentTimeMillis() - start
                if (elapsed >= listenMs) break
                // Ask at once, and again halfway in case the first request met a sleeping port.
                if (sent == 0 || (sent == 1 && elapsed >= listenMs / 2)) {
                    sendRequests(link.output, detector, sent)
                    sent++
                }
                val found = detector.found
                // PPP is certain at once; for SLIP wait (until the end) for a reply that names the radio.
                if (found != null && (found.framing == IpFraming.PPP || found.radio != null)) {
                    return P25ProbeResult(found.framing, baud, found.radio)
                }
                Thread.sleep(POLL_MS)
            }
            return detector.found?.let { P25ProbeResult(it.framing, baud, it.radio) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            reader.interrupt()
        }
    }

    private fun sendRequests(out: OutputStream, detector: Detector, round: Int) {
        val ppp = java.io.ByteArrayOutputStream()
        PppLink(localAddress, 0, output = { ppp.write(it) }, onPacket = {}).start()
        detector.rememberOwnPpp(ppp.toByteArray())
        out.write(ppp.toByteArray())
        for ((i, address) in radioAddresses.withIndex()) {
            out.write(Slip.encode(Ipv4.buildEchoRequest(localAddress, address, PING_ID, round * 16 + i, ttl = 1)))
        }
        out.flush()
    }

    private class Found(val framing: IpFraming, val radio: Int?)

    /** Watches the incoming bytes for a valid PPP frame or SLIP packet. */
    private class Detector(private val localAddress: Int) {
        @Volatile var found: Found? = null
            private set
        private val own = ArrayList<ByteArray>()
        private val incoming = PppDeframer { onPpp(it) }
        private val slip = SlipDecoder { onSlip(it) }

        /** Frames we sent, so that a cable echoing them back is not taken for a PPP radio. */
        @Synchronized
        fun rememberOwnPpp(bytes: ByteArray) {
            PppDeframer { own.add(it) }.feed(bytes, 0, bytes.size)
        }

        @Synchronized
        fun feed(data: ByteArray, offset: Int, length: Int) {
            incoming.feed(data, offset, length)
            slip.feed(data, offset, length)
        }

        private fun onPpp(frame: ByteArray) {
            if (own.any { it.contentEquals(frame) }) return
            var i = 0
            if (frame.size >= 2 && (frame[0].toInt() and 0xFF) == 0xFF && frame[1].toInt() == 0x03) i = 2
            if (i >= frame.size) return
            var protocol = frame[i].toInt() and 0xFF
            if (protocol and 1 == 0) {
                if (i + 1 >= frame.size) return
                protocol = (protocol shl 8) or (frame[i + 1].toInt() and 0xFF)
            }
            if (protocol in PPP_PROTOCOLS) found = Found(IpFraming.PPP, null)
        }

        private fun onSlip(packet: ByteArray) {
            val ip = Ipv4.parsePacket(packet) ?: return
            if (ip.source == localAddress) return // Our own request echoed back.
            val type = if (ip.protocol == Ipv4.PROTO_ICMP && ip.payload.isNotEmpty()) ip.payload[0].toInt() and 0xFF else -1
            if (type == 8) return
            // An echo reply, time exceeded or unreachable comes from the radio itself.
            val radio = if (type == 0 || type == 3 || type == 11) ip.source else null
            val current = found
            if (current == null || (current.framing == IpFraming.SLIP && current.radio == null)) {
                found = Found(IpFraming.SLIP, radio)
            }
        }
    }

    /** Splits an async-HDLC byte stream into PPP frames with a valid FCS (FCS removed). */
    private class PppDeframer(private val onFrame: (ByteArray) -> Unit) {
        private val buf = ByteArray(2048)
        private var len = 0
        private var escaped = false
        private var overflow = false

        fun feed(data: ByteArray, offset: Int, length: Int) {
            for (i in offset until offset + length) {
                var v = data[i].toInt() and 0xFF
                if (v == PppLink.FLAG) {
                    if (!overflow && len >= 4 && Crc16.check(buf, len)) onFrame(buf.copyOf(len - 2))
                    len = 0
                    escaped = false
                    overflow = false
                    continue
                }
                if (v == PppLink.ESCAPE) {
                    escaped = true
                    continue
                }
                if (escaped) {
                    v = v xor 0x20
                    escaped = false
                }
                if (len == buf.size) overflow = true else if (!overflow) buf[len++] = v.toByte()
            }
        }
    }

    private companion object {
        const val POLL_MS = 20L
        const val PING_ID = 0x7457 // "Tw"
        val PPP_PROTOCOLS = setOf(PppLink.PROTO_LCP, PppLink.PROTO_IPCP, PppLink.PROTO_IP)
    }
}
