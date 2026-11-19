// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ip

import java.util.Random

/** A parsed Ethernet II frame. [payload] may carry padding after the real packet (frames are at least 60 bytes). */
class EthernetFrame(val destination: ByteArray, val source: ByteArray, val type: Int, val payload: ByteArray)

/** Ethernet II framing, for radios whose USB data link is a virtual network adapter (RNDIS, CDC-ECM). */
object Ethernet {
    const val HEADER_SIZE = 14
    const val TYPE_IPV4 = 0x0800
    const val TYPE_ARP = 0x0806

    val BROADCAST: ByteArray get() = ByteArray(6) { 0xFF.toByte() }

    fun build(destination: ByteArray, source: ByteArray, type: Int, payload: ByteArray): ByteArray {
        require(destination.size == 6 && source.size == 6)
        val f = ByteArray(HEADER_SIZE + payload.size)
        destination.copyInto(f, 0)
        source.copyInto(f, 6)
        Ipv4.put16(f, 12, type)
        payload.copyInto(f, HEADER_SIZE)
        return f
    }

    fun parse(frame: ByteArray): EthernetFrame? {
        if (frame.size < HEADER_SIZE) return null
        return EthernetFrame(
            destination = frame.copyOfRange(0, 6),
            source = frame.copyOfRange(6, 12),
            type = Ipv4.get16(frame, 12),
            payload = frame.copyOfRange(HEADER_SIZE, frame.size),
        )
    }

    /** The Ethernet multicast address for IPv4 multicast address [ip] (RFC 1112: 01:00:5e plus its low 23 bits). */
    fun multicastMac(ip: Int): ByteArray =
        byteArrayOf(0x01, 0x00, 0x5E, ((ip ushr 16) and 0x7F).toByte(), (ip ushr 8).toByte(), ip.toByte())

    /** True for broadcast and multicast addresses (group bit set). */
    fun isGroup(mac: ByteArray): Boolean = mac[0].toInt() and 1 == 1

    /** A random, locally administered unicast address, for when the device does not give one. */
    fun randomLocalMac(random: Random = Random()): ByteArray {
        val mac = ByteArray(6).also { random.nextBytes(it) }
        mac[0] = ((mac[0].toInt() and 0xFC) or 0x02).toByte()
        return mac
    }

    fun format(mac: ByteArray): String = mac.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }

    /** Parses "aa:bb:cc:dd:ee:ff", "aa-bb-...", or 12 hex digits; null when it is not a MAC address. */
    fun parseMac(text: String): ByteArray? {
        val hex = text.trim().filter { it != ':' && it != '-' }
        if (hex.length != 12 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/** A parsed ARP packet for IPv4 over Ethernet. */
class ArpPacket(val operation: Int, val senderMac: ByteArray, val senderIp: Int, val targetMac: ByteArray, val targetIp: Int)

/** ARP (RFC 826) for IPv4 over Ethernet: just requests and replies. */
object Arp {
    const val REQUEST = 1
    const val REPLY = 2
    private const val SIZE = 28

    fun build(operation: Int, senderMac: ByteArray, senderIp: Int, targetMac: ByteArray, targetIp: Int): ByteArray {
        val p = ByteArray(SIZE)
        Ipv4.put16(p, 0, 1) // Ethernet
        Ipv4.put16(p, 2, Ethernet.TYPE_IPV4)
        p[4] = 6
        p[5] = 4
        Ipv4.put16(p, 6, operation)
        senderMac.copyInto(p, 8)
        Ipv4.put32(p, 14, senderIp)
        targetMac.copyInto(p, 18)
        Ipv4.put32(p, 24, targetIp)
        return p
    }

    fun parse(p: ByteArray): ArpPacket? {
        if (p.size < SIZE) return null
        if (Ipv4.get16(p, 0) != 1 || Ipv4.get16(p, 2) != Ethernet.TYPE_IPV4 || p[4].toInt() != 6 || p[5].toInt() != 4) return null
        val op = Ipv4.get16(p, 6)
        if (op != REQUEST && op != REPLY) return null
        return ArpPacket(op, p.copyOfRange(8, 14), Ipv4.get32(p, 14), p.copyOfRange(18, 24), Ipv4.get32(p, 24))
    }
}
