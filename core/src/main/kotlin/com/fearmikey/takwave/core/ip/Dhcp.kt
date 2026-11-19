// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ip

/** A DHCP offer, acknowledgment or refusal addressed to us. */
class DhcpReply(val type: Int, val yourAddress: Int, val server: Int?, val router: Int?, val subnetMask: Int?)

/**
 * The client half of DHCP (RFC 2131), only as much as is needed to ask a radio which address the
 * phone should use on the USB link and what the radio's own address is. No renewals: the radio's
 * link is private to the two ends, and addresses are refreshed on every reconnect.
 */
object Dhcp {
    const val CLIENT_PORT = 68
    const val SERVER_PORT = 67

    const val DISCOVER = 1
    const val OFFER = 2
    const val REQUEST = 3
    const val ACK = 5
    const val NAK = 6

    private const val FIXED_SIZE = 236
    private val MAGIC = byteArrayOf(99, 130.toByte(), 83, 99)

    private const val OPT_SUBNET = 1
    private const val OPT_ROUTER = 3
    private const val OPT_REQUESTED_IP = 50
    private const val OPT_MESSAGE_TYPE = 53
    private const val OPT_SERVER_ID = 54
    private const val OPT_PARAMETERS = 55
    private const val OPT_END = 255
    private const val OPT_PAD = 0

    fun discover(mac: ByteArray, xid: Int): ByteArray = message(mac, xid, DISCOVER, requested = null, server = null)

    fun request(mac: ByteArray, xid: Int, requested: Int, server: Int?): ByteArray = message(mac, xid, REQUEST, requested, server)

    private fun message(mac: ByteArray, xid: Int, type: Int, requested: Int?, server: Int?): ByteArray {
        val options = java.io.ByteArrayOutputStream()
        options.write(MAGIC)
        options.write(byteArrayOf(OPT_MESSAGE_TYPE.toByte(), 1, type.toByte()))
        if (requested != null) options.write(byteArrayOf(OPT_REQUESTED_IP.toByte(), 4) + int32(requested))
        if (server != null) options.write(byteArrayOf(OPT_SERVER_ID.toByte(), 4) + int32(server))
        options.write(byteArrayOf(OPT_PARAMETERS.toByte(), 2, OPT_SUBNET.toByte(), OPT_ROUTER.toByte()))
        options.write(OPT_END)
        val p = ByteArray(FIXED_SIZE) + options.toByteArray()
        p[0] = 1 // BOOTREQUEST
        p[1] = 1 // Ethernet
        p[2] = 6
        Ipv4.put32(p, 4, xid)
        Ipv4.put16(p, 10, 0x8000) // Ask for a broadcast reply: we have no address yet.
        mac.copyInto(p, 28)
        return p
    }

    /** Parses a server's reply to transaction [xid] for [mac], or returns null when it is not one. */
    fun parse(p: ByteArray, xid: Int, mac: ByteArray): DhcpReply? {
        if (p.size < FIXED_SIZE + 4 || p[0].toInt() != 2) return null
        if (Ipv4.get32(p, 4) != xid) return null
        if (!p.copyOfRange(28, 34).contentEquals(mac)) return null
        if (!p.copyOfRange(FIXED_SIZE, FIXED_SIZE + 4).contentEquals(MAGIC)) return null
        var type = -1
        var server: Int? = null
        var router: Int? = null
        var subnet: Int? = null
        var i = FIXED_SIZE + 4
        while (i < p.size) {
            val code = p[i].toInt() and 0xFF
            if (code == OPT_END) break
            if (code == OPT_PAD) { i++; continue }
            if (i + 1 >= p.size) return null
            val len = p[i + 1].toInt() and 0xFF
            val v = i + 2
            if (v + len > p.size) return null
            when (code) {
                OPT_MESSAGE_TYPE -> if (len >= 1) type = p[v].toInt() and 0xFF
                OPT_SERVER_ID -> if (len >= 4) server = Ipv4.get32(p, v)
                OPT_ROUTER -> if (len >= 4) router = Ipv4.get32(p, v)
                OPT_SUBNET -> if (len >= 4) subnet = Ipv4.get32(p, v)
            }
            i = v + len
        }
        if (type != OFFER && type != ACK && type != NAK) return null
        return DhcpReply(type, Ipv4.get32(p, 16), server, router, subnet)
    }

    private fun int32(v: Int) = ByteArray(4).also { Ipv4.put32(it, 0, v) }
}
