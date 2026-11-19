// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ip

import com.fearmikey.takwave.core.ax25.Crc16
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * A minimal PPP client for a serial link to a radio: HDLC-like asynchronous framing (RFC 1662),
 * LCP without authentication (RFC 1661) and IPCP for the two addresses (RFC 1332), then IPv4.
 *
 * It asks for [requestedLocal] as its own address and accepts a different one if the radio
 * proposes it. When the radio asks for an address (sends 0.0.0.0) it is offered [defaultPeer].
 * Options it does not need (authentication, compression, DNS) are rejected. Negotiation that
 * does not finish within [NEGOTIATION_TIMEOUT_MS] sets [failure], so the caller reopens the line.
 *
 * The FCS-16 of PPP is the same CRC as the AX.25 frame check sequence, so [Crc16] is reused.
 */
class PppLink(
    requestedLocal: Int,
    private val defaultPeer: Int,
    private val output: (ByteArray) -> Unit,
    private val onPacket: (ByteArray) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: Random = Random(),
) : IpPacketLink {
    @Volatile override var localAddress: Int = requestedLocal
        private set
    @Volatile override var peerAddress: Int = defaultPeer
        private set
    @Volatile override var failure: String? = null
        private set
    @Volatile override var up: Boolean = false
        private set

    /** True once LCP is open (exposed for tests and status). */
    @Volatile var lcpOpen: Boolean = false
        private set

    private var lcpLocalAcked = false
    private var lcpPeerAcked = false
    private var ipcpLocalAcked = false
    private var ipcpPeerAcked = false

    private var magic = newMagic()
    private var sendMagic = true
    private var sendAddress = true
    private var lcpId = 0
    private var ipcpId = 0
    private var lastLcpRequestMs = 0L
    private var lcpTries = 0
    private var lastIpcpRequestMs = 0L
    private var ipcpTries = 0
    private var startedMs = 0L

    // Receive side of the framing.
    private val rx = ByteArray(MAX_FRAME)
    private var rxLen = 0
    private var rxEscaped = false
    private var rxOverflow = false

    @Synchronized
    override fun start() {
        startedMs = nowMs()
        sendLcpRequest()
    }

    @Synchronized
    override fun feed(data: ByteArray, offset: Int, length: Int) {
        for (i in offset until offset + length) {
            var v = data[i].toInt() and 0xFF
            if (v == FLAG) {
                if (!rxOverflow && rxLen >= 4 && Crc16.check(rx, rxLen)) handleFrame(rx.copyOf(rxLen - 2))
                rxLen = 0
                rxEscaped = false
                rxOverflow = false
                continue
            }
            if (v == ESCAPE) {
                rxEscaped = true
                continue
            }
            if (rxEscaped) {
                v = v xor 0x20
                rxEscaped = false
            }
            if (rxLen == MAX_FRAME) rxOverflow = true else if (!rxOverflow) rx[rxLen++] = v.toByte()
        }
    }

    @Synchronized
    override fun tick() {
        if (failure != null) return
        val now = nowMs()
        if (!up && startedMs != 0L && now - startedMs > NEGOTIATION_TIMEOUT_MS) {
            fail(
                if (!lcpOpen) "no PPP answer from the radio; check that its data port is set to PPP and the baud rate matches"
                else "the radio did not agree on IP addresses (IPCP)",
            )
            return
        }
        if (!lcpLocalAcked) {
            if (now - lastLcpRequestMs >= RESTART_MS) {
                if (lcpTries >= MAX_TRIES) fail("no PPP answer from the radio; check that its data port is set to PPP and the baud rate matches")
                else sendLcpRequest()
            }
        } else if (lcpOpen && !ipcpLocalAcked) {
            if (now - lastIpcpRequestMs >= RESTART_MS) {
                if (ipcpTries >= MAX_TRIES) fail("the radio did not accept this end's IP address (IPCP)")
                else sendIpcpRequest()
            }
        }
    }

    override fun send(packet: ByteArray): Boolean {
        if (!up) return false
        output(frame(PROTO_IP, packet))
        return true
    }

    // ---- Frames ----

    private fun handleFrame(f: ByteArray) {
        var i = 0
        // Address and control may be compressed away (ACFC); accept both forms.
        if (f.size >= 2 && (f[0].toInt() and 0xFF) == 0xFF && f[1].toInt() == 0x03) i = 2
        if (i >= f.size) return
        var protocol = f[i].toInt() and 0xFF
        if (protocol and 1 == 1) {
            i += 1 // Compressed one-byte protocol field (PFC).
        } else {
            if (i + 1 >= f.size) return
            protocol = (protocol shl 8) or (f[i + 1].toInt() and 0xFF)
            i += 2
        }
        val info = f.copyOfRange(i, f.size)
        when (protocol) {
            PROTO_LCP -> handleLcp(info)
            PROTO_IPCP -> if (lcpOpen) handleIpcp(info)
            PROTO_IP -> if (up) onPacket(info)
            else -> if (lcpOpen) {
                // Protocol-Reject: the rejected protocol number, then the rejected information.
                val data = ByteArray(2 + info.size)
                data[0] = (protocol ushr 8).toByte()
                data[1] = protocol.toByte()
                info.copyInto(data, 2)
                lcpId = (lcpId + 1) and 0xFF
                sendControl(PROTO_LCP, PROTO_REJ, lcpId, data)
            }
        }
    }

    private fun frame(protocol: Int, info: ByteArray): ByteArray {
        val raw = ByteArray(4 + info.size)
        raw[0] = 0xFF.toByte()
        raw[1] = 0x03
        raw[2] = (protocol ushr 8).toByte()
        raw[3] = protocol.toByte()
        info.copyInto(raw, 4)
        val withFcs = Crc16.appendFcs(raw)
        val out = ByteArrayOutputStream(withFcs.size * 2 + 2)
        out.write(FLAG)
        for (b in withFcs) {
            val v = b.toInt() and 0xFF
            // Every control character is escaped, so no ACCM needs to be agreed.
            if (v < 0x20 || v == FLAG || v == ESCAPE) {
                out.write(ESCAPE)
                out.write(v xor 0x20)
            } else {
                out.write(v)
            }
        }
        out.write(FLAG)
        return out.toByteArray()
    }

    private fun sendControl(protocol: Int, code: Int, id: Int, data: ByteArray) {
        val p = ByteArray(4 + data.size)
        p[0] = code.toByte()
        p[1] = id.toByte()
        Ipv4.put16(p, 2, p.size)
        data.copyInto(p, 4)
        output(frame(protocol, p))
    }

    /** Calls [block] with each option's type and its complete bytes. Returns false when malformed. */
    private inline fun forEachOption(data: ByteArray, block: (Int, ByteArray) -> Unit): Boolean {
        var i = 0
        while (i < data.size) {
            if (i + 1 >= data.size) return false
            val len = data[i + 1].toInt() and 0xFF
            if (len < 2 || i + len > data.size) return false
            block(data[i].toInt() and 0xFF, data.copyOfRange(i, i + len))
            i += len
        }
        return true
    }

    // ---- LCP ----

    private fun sendLcpRequest() {
        lcpId = (lcpId + 1) and 0xFF
        val options = if (sendMagic) option32(OPT_MAGIC, magic) else ByteArray(0)
        sendControl(PROTO_LCP, CONF_REQ, lcpId, options)
        lastLcpRequestMs = nowMs()
        lcpTries++
    }

    private fun handleLcp(info: ByteArray) {
        val (code, id, data) = parseControl(info) ?: return
        when (code) {
            CONF_REQ -> {
                if (lcpOpen) {
                    // The radio renegotiates: everything above LCP goes down and starts again.
                    lcpOpen = false
                    lcpLocalAcked = false
                    resetIpcp()
                    lcpTries = 0
                    startedMs = nowMs()
                    sendLcpRequest()
                }
                val rejected = ByteArrayOutputStream()
                val naked = ByteArrayOutputStream()
                val ok = forEachOption(data) { type, opt ->
                    when (type) {
                        OPT_MRU, OPT_ACCM, OPT_PFC, OPT_ACFC -> {}
                        OPT_MAGIC -> if (opt.size == 6 && Ipv4.get32(opt, 2) == magic) {
                            // Same number as ours: probably a looped-back line. Suggest another.
                            naked.write(option32(OPT_MAGIC, newMagic()))
                        }
                        else -> rejected.write(opt)
                    }
                }
                if (!ok) return
                when {
                    rejected.size() > 0 -> sendControl(PROTO_LCP, CONF_REJ, id, rejected.toByteArray())
                    naked.size() > 0 -> sendControl(PROTO_LCP, CONF_NAK, id, naked.toByteArray())
                    else -> {
                        sendControl(PROTO_LCP, CONF_ACK, id, data)
                        lcpPeerAcked = true
                        checkLcpOpen()
                    }
                }
            }
            CONF_ACK -> if (id == lcpId && !lcpLocalAcked) {
                lcpLocalAcked = true
                checkLcpOpen()
            }
            CONF_NAK -> if (id == lcpId && !lcpLocalAcked) {
                forEachOption(data) { type, _ -> if (type == OPT_MAGIC) magic = newMagic() }
                sendLcpRequest()
            }
            CONF_REJ -> if (id == lcpId && !lcpLocalAcked) {
                forEachOption(data) { type, _ -> if (type == OPT_MAGIC) sendMagic = false }
                sendLcpRequest()
            }
            TERM_REQ -> {
                sendControl(PROTO_LCP, TERM_ACK, id, ByteArray(0))
                fail("the radio closed the PPP link")
            }
            PROTO_REJ -> if (data.size >= 2 && Ipv4.get16(data, 0) == PROTO_IPCP) {
                fail("the radio does not offer IP over PPP on this port")
            }
            ECHO_REQ -> if (lcpOpen) {
                val reply = option32Raw(if (sendMagic) magic else 0) + (if (data.size > 4) data.copyOfRange(4, data.size) else ByteArray(0))
                sendControl(PROTO_LCP, ECHO_REPLY, id, reply)
            }
            TERM_ACK, CODE_REJ, ECHO_REPLY, DISCARD_REQ -> {}
            else -> sendControl(PROTO_LCP, CODE_REJ, id, info)
        }
    }

    private fun checkLcpOpen() {
        if (lcpOpen || !lcpLocalAcked || !lcpPeerAcked) return
        lcpOpen = true
        ipcpTries = 0
        sendIpcpRequest()
    }

    // ---- IPCP ----

    private fun resetIpcp() {
        ipcpLocalAcked = false
        ipcpPeerAcked = false
        updateUp()
    }

    private fun sendIpcpRequest() {
        ipcpId = (ipcpId + 1) and 0xFF
        val options = if (sendAddress) option32(OPT_IP_ADDRESS, localAddress) else ByteArray(0)
        sendControl(PROTO_IPCP, CONF_REQ, ipcpId, options)
        lastIpcpRequestMs = nowMs()
        ipcpTries++
    }

    private fun handleIpcp(info: ByteArray) {
        val (code, id, data) = parseControl(info) ?: return
        when (code) {
            CONF_REQ -> {
                if (ipcpLocalAcked && ipcpPeerAcked) {
                    resetIpcp()
                    ipcpTries = 0
                    sendIpcpRequest()
                }
                val rejected = ByteArrayOutputStream()
                val naked = ByteArrayOutputStream()
                var proposed: Int? = null
                val ok = forEachOption(data) { type, opt ->
                    if (type == OPT_IP_ADDRESS && opt.size == 6) {
                        val address = Ipv4.get32(opt, 2)
                        if (address == 0) naked.write(option32(OPT_IP_ADDRESS, defaultPeer)) else proposed = address
                    } else {
                        // IP compression, DNS and anything else are not needed on this link.
                        rejected.write(opt)
                    }
                }
                if (!ok) return
                when {
                    rejected.size() > 0 -> sendControl(PROTO_IPCP, CONF_REJ, id, rejected.toByteArray())
                    naked.size() > 0 -> sendControl(PROTO_IPCP, CONF_NAK, id, naked.toByteArray())
                    else -> {
                        sendControl(PROTO_IPCP, CONF_ACK, id, data)
                        proposed?.let { peerAddress = it }
                        ipcpPeerAcked = true
                        updateUp()
                    }
                }
            }
            CONF_ACK -> if (id == ipcpId && !ipcpLocalAcked) {
                ipcpLocalAcked = true
                updateUp()
            }
            CONF_NAK -> if (id == ipcpId && !ipcpLocalAcked) {
                forEachOption(data) { type, opt ->
                    if (type == OPT_IP_ADDRESS && opt.size == 6) {
                        val address = Ipv4.get32(opt, 2)
                        if (address != 0) localAddress = address
                    }
                }
                sendIpcpRequest()
            }
            CONF_REJ -> if (id == ipcpId && !ipcpLocalAcked) {
                forEachOption(data) { type, _ -> if (type == OPT_IP_ADDRESS) sendAddress = false }
                sendIpcpRequest()
            }
            TERM_REQ -> {
                sendControl(PROTO_IPCP, TERM_ACK, id, ByteArray(0))
                fail("the radio closed the IP link")
            }
            TERM_ACK, CODE_REJ -> {}
            else -> sendControl(PROTO_IPCP, CODE_REJ, id, info)
        }
    }

    private fun updateUp() {
        val nowUp = lcpOpen && ipcpLocalAcked && ipcpPeerAcked && failure == null
        if (nowUp && localAddress == 0) {
            fail("the radio did not give this end an IP address; set the phone's address")
            return
        }
        up = nowUp
    }

    private fun fail(reason: String) {
        if (failure == null) failure = reason
        up = false
    }

    private fun newMagic(): Int {
        var m: Int
        do { m = random.nextInt() } while (m == 0)
        return m
    }

    private fun parseControl(info: ByteArray): Triple<Int, Int, ByteArray>? {
        if (info.size < 4) return null
        val length = Ipv4.get16(info, 2)
        if (length < 4 || length > info.size) return null
        return Triple(info[0].toInt() and 0xFF, info[1].toInt() and 0xFF, info.copyOfRange(4, length))
    }

    private fun option32(type: Int, value: Int): ByteArray = byteArrayOf(type.toByte(), 6) + option32Raw(value)

    private fun option32Raw(value: Int): ByteArray = ByteArray(4).also { Ipv4.put32(it, 0, value) }

    companion object {
        const val PROTO_IP = 0x0021
        const val PROTO_IPCP = 0x8021
        const val PROTO_LCP = 0xC021

        const val CONF_REQ = 1
        const val CONF_ACK = 2
        const val CONF_NAK = 3
        const val CONF_REJ = 4
        const val TERM_REQ = 5
        const val TERM_ACK = 6
        const val CODE_REJ = 7
        const val PROTO_REJ = 8
        const val ECHO_REQ = 9
        const val ECHO_REPLY = 10
        const val DISCARD_REQ = 11

        const val OPT_MRU = 1
        const val OPT_ACCM = 2
        const val OPT_MAGIC = 5
        const val OPT_PFC = 7
        const val OPT_ACFC = 8
        const val OPT_IP_ADDRESS = 3

        const val FLAG = 0x7E
        const val ESCAPE = 0x7D

        const val RESTART_MS = 3_000L
        const val MAX_TRIES = 10
        const val NEGOTIATION_TIMEOUT_MS = 45_000L
        private const val MAX_FRAME = 4096
    }
}
