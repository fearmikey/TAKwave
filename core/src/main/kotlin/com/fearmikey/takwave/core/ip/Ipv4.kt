// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ip

/** A parsed IPv4 datagram. [payload] is everything after the IP header. */
class IpPacket(val protocol: Int, val source: Int, val destination: Int, val payload: ByteArray)

/** A parsed UDP datagram with the addresses of the IP packet that carried it. */
class UdpDatagram(
    val source: Int,
    val destination: Int,
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray,
)

/**
 * Just enough IPv4 for a point-to-point link to a radio: build and check UDP datagrams and answer
 * pings. Addresses are Ints holding the four bytes in network order (a.b.c.d is a shl 24 or ...).
 * Fragments are not reassembled; TAKwave's datagrams are far below any link MTU.
 */
object Ipv4 {
    const val PROTO_ICMP = 1
    const val PROTO_UDP = 17
    const val HEADER_SIZE = 20
    const val UDP_HEADER_SIZE = 8

    /** 255.255.255.255, the limited broadcast address. */
    const val BROADCAST: Int = -1

    /** Parses dotted-quad text, or returns null. */
    fun parse(text: String): Int? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        var value = 0
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val n = p.toInt()
            if (n > 255) return null
            value = (value shl 8) or n
        }
        return value
    }

    fun format(address: Int): String =
        "${address ushr 24}.${(address ushr 16) and 0xFF}.${(address ushr 8) and 0xFF}.${address and 0xFF}"

    /**
     * Parses a list of addresses separated by commas, semicolons or spaces. Returns null when the
     * list is empty or any entry is invalid. Duplicates are removed.
     */
    fun parseList(text: String): List<Int>? {
        val items = text.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return null
        return items.map { parse(it) ?: return null }.distinct()
    }

    /** The Internet checksum (RFC 1071) of [length] bytes, continuing from a partial [initial] sum. */
    fun checksum(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, initial: Long = 0): Int {
        var sum = initial
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** Builds an IPv4 packet with a UDP datagram inside, both checksums filled in. */
    fun buildUdp(
        source: Int,
        destination: Int,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
        id: Int,
        ttl: Int = 64,
    ): ByteArray {
        val udpLength = UDP_HEADER_SIZE + payload.size
        val total = HEADER_SIZE + udpLength
        require(total <= 0xFFFF) { "datagram too long" }
        val p = ByteArray(total)
        writeHeader(p, total, id, ttl, PROTO_UDP, source, destination)
        val u = HEADER_SIZE
        put16(p, u, sourcePort)
        put16(p, u + 2, destinationPort)
        put16(p, u + 4, udpLength)
        payload.copyInto(p, u + UDP_HEADER_SIZE)
        val sum = pseudoHeaderSum(source, destination, PROTO_UDP, udpLength)
        val c = checksum(p, u, udpLength, sum)
        // A computed zero is sent as all ones; zero means "no checksum".
        put16(p, u + 6, if (c == 0) 0xFFFF else c)
        return p
    }

    /**
     * Parses and checks an IPv4 packet. Returns null for anything that is not a complete,
     * unfragmented IPv4 packet with a valid header checksum.
     */
    fun parsePacket(p: ByteArray): IpPacket? {
        if (p.size < HEADER_SIZE) return null
        val b0 = p[0].toInt() and 0xFF
        if (b0 ushr 4 != 4) return null
        val ihl = (b0 and 0x0F) * 4
        if (ihl < HEADER_SIZE || ihl > p.size) return null
        val total = get16(p, 2)
        if (total < ihl || total > p.size) return null
        if (checksum(p, 0, ihl) != 0) return null
        val flagsFragment = get16(p, 6)
        // More fragments set, or a fragment offset: not reassembled.
        if (flagsFragment and 0x2000 != 0 || flagsFragment and 0x1FFF != 0) return null
        return IpPacket(
            protocol = p[9].toInt() and 0xFF,
            source = get32(p, 12),
            destination = get32(p, 16),
            payload = p.copyOfRange(ihl, total),
        )
    }

    /** Extracts the UDP datagram from [ip], or null when it is not UDP or fails its checks. */
    fun parseUdp(ip: IpPacket): UdpDatagram? {
        if (ip.protocol != PROTO_UDP) return null
        val u = ip.payload
        if (u.size < UDP_HEADER_SIZE) return null
        val length = get16(u, 4)
        if (length < UDP_HEADER_SIZE || length > u.size) return null
        if (get16(u, 6) != 0) {
            val sum = pseudoHeaderSum(ip.source, ip.destination, PROTO_UDP, length)
            if (checksum(u, 0, length, sum) != 0) return null
        }
        return UdpDatagram(
            source = ip.source,
            destination = ip.destination,
            sourcePort = get16(u, 0),
            destinationPort = get16(u, 2),
            payload = u.copyOfRange(UDP_HEADER_SIZE, length),
        )
    }

    /**
     * The reply to an ICMP echo request (ping), or null when [ip] is not one. A ping to a
     * broadcast address is answered from [localAddress].
     */
    fun echoReply(ip: IpPacket, localAddress: Int, id: Int): ByteArray? {
        if (ip.protocol != PROTO_ICMP) return null
        val icmp = ip.payload
        if (icmp.size < 8 || icmp[0].toInt() != 8 || icmp[1].toInt() != 0) return null
        if (checksum(icmp) != 0) return null
        val reply = icmp.copyOf()
        reply[0] = 0
        put16(reply, 2, 0)
        put16(reply, 2, checksum(reply))
        val from = if (ip.destination == BROADCAST || ip.destination == 0) localAddress else ip.destination
        val p = ByteArray(HEADER_SIZE + reply.size)
        writeHeader(p, p.size, id, 64, PROTO_ICMP, from, ip.source)
        reply.copyInto(p, HEADER_SIZE)
        return p
    }

    /**
     * An ICMP echo request (ping). With [ttl] 1 a router drops it instead of forwarding it, which
     * keeps a probe of the radio's own address from going out over the air.
     */
    fun buildEchoRequest(source: Int, destination: Int, id: Int, sequence: Int, ttl: Int = 64, data: ByteArray = ByteArray(0)): ByteArray {
        val icmp = ByteArray(8 + data.size)
        icmp[0] = 8
        put16(icmp, 4, id and 0xFFFF)
        put16(icmp, 6, sequence and 0xFFFF)
        data.copyInto(icmp, 8)
        put16(icmp, 2, checksum(icmp))
        val p = ByteArray(HEADER_SIZE + icmp.size)
        writeHeader(p, p.size, id, ttl, PROTO_ICMP, source, destination)
        icmp.copyInto(p, HEADER_SIZE)
        return p
    }

    private fun writeHeader(p: ByteArray, total: Int, id: Int, ttl: Int, protocol: Int, source: Int, destination: Int) {
        p[0] = 0x45
        p[1] = 0
        put16(p, 2, total)
        put16(p, 4, id and 0xFFFF)
        put16(p, 6, 0)
        p[8] = ttl.toByte()
        p[9] = protocol.toByte()
        put32(p, 12, source)
        put32(p, 16, destination)
        put16(p, 10, checksum(p, 0, HEADER_SIZE))
    }

    private fun pseudoHeaderSum(source: Int, destination: Int, protocol: Int, length: Int): Long =
        (source ushr 16).toLong() + (source and 0xFFFF) + (destination ushr 16) + (destination and 0xFFFF) + protocol + length

    internal fun get16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    internal fun get32(b: ByteArray, i: Int) = (get16(b, i) shl 16) or get16(b, i + 2)

    internal fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 8).toByte()
        b[i + 1] = v.toByte()
    }

    internal fun put32(b: ByteArray, i: Int, v: Int) {
        put16(b, i, v ushr 16)
        put16(b, i + 2, v)
    }
}
