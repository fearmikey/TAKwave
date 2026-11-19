// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ip

import com.fearmikey.takwave.core.ax25.Crc16
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.Random

class IpTest {
    private val phone = Ipv4.parse("192.168.128.2")!!
    private val radio = Ipv4.parse("192.168.128.1")!!

    @Test
    fun addressesParseAndFormat() {
        assertEquals("192.168.128.2", Ipv4.format(phone))
        assertEquals(Ipv4.BROADCAST, Ipv4.parse("255.255.255.255"))
        assertEquals("255.255.255.255", Ipv4.format(Ipv4.BROADCAST))
        assertEquals(0, Ipv4.parse("0.0.0.0"))
        for (bad in listOf("", "1.2.3", "1.2.3.4.5", "256.1.1.1", "1.2.3.-4", "a.b.c.d", "1..2.3", "1.2.3.4 5")) {
            assertNull(bad, Ipv4.parse(bad))
        }
        assertEquals(listOf(Ipv4.BROADCAST, phone), Ipv4.parseList("255.255.255.255, 192.168.128.2;192.168.128.2"))
        assertNull(Ipv4.parseList(" , "))
        assertNull(Ipv4.parseList("10.0.0.1, nope"))
    }

    @Test
    fun headerChecksumMatchesTheTextbookExample() {
        val header = byteArrayOf(
            0x45, 0x00, 0x00, 0x73, 0x00, 0x00, 0x40, 0x00, 0x40, 0x11, 0x00, 0x00,
            0xC0.toByte(), 0xA8.toByte(), 0x00, 0x01, 0xC0.toByte(), 0xA8.toByte(), 0x00, 0xC7.toByte(),
        )
        assertEquals(0xB861, Ipv4.checksum(header))
    }

    @Test
    fun udpRoundTripsAndDamageIsRejected() {
        val payload = ByteArray(201) { (it * 7).toByte() }
        val p = Ipv4.buildUdp(phone, Ipv4.BROADCAST, 50125, 50125, payload, id = 9)
        assertEquals(20 + 8 + 201, p.size)
        val ip = Ipv4.parsePacket(p)!!
        assertEquals(Ipv4.PROTO_UDP, ip.protocol)
        assertEquals(phone, ip.source)
        assertEquals(Ipv4.BROADCAST, ip.destination)
        val udp = Ipv4.parseUdp(ip)!!
        assertEquals(50125, udp.destinationPort)
        assertArrayEquals(payload, udp.payload)

        // A damaged payload byte fails the UDP checksum; a damaged header the IP checksum.
        val badPayload = p.copyOf().also { it[60] = (it[60] + 1).toByte() }
        assertNull(Ipv4.parseUdp(Ipv4.parsePacket(badPayload)!!))
        val badHeader = p.copyOf().also { it[8] = 1 }
        assertNull(Ipv4.parsePacket(badHeader))
        // Truncated and fragmented packets are refused.
        assertNull(Ipv4.parsePacket(p.copyOf(p.size - 1)))
        val fragment = p.copyOf().also { it[6] = 0x20; it[10] = 0; it[11] = 0 }
        Ipv4.put16(fragment, 10, Ipv4.checksum(fragment, 0, 20))
        assertNull(Ipv4.parsePacket(fragment))
    }

    @Test
    fun pingsAreAnswered() {
        val icmp = byteArrayOf(8, 0, 0, 0, 0x12, 0x34, 0, 1, 'h'.code.toByte(), 'i'.code.toByte())
        Ipv4.put16(icmp, 2, Ipv4.checksum(icmp))
        val request = ByteArray(20 + icmp.size)
        request[0] = 0x45
        Ipv4.put16(request, 2, request.size)
        request[8] = 64
        request[9] = 1
        Ipv4.put32(request, 12, radio)
        Ipv4.put32(request, 16, phone)
        Ipv4.put16(request, 10, Ipv4.checksum(request, 0, 20))
        icmp.copyInto(request, 20)

        val reply = Ipv4.parsePacket(Ipv4.echoReply(Ipv4.parsePacket(request)!!, phone, 1)!!)!!
        assertEquals(phone, reply.source)
        assertEquals(radio, reply.destination)
        assertEquals(0, reply.payload[0].toInt())
        assertEquals(0, Ipv4.checksum(reply.payload))
        assertArrayEquals(icmp.copyOfRange(4, icmp.size), reply.payload.copyOfRange(4, reply.payload.size))
    }

    @Test
    fun slipRoundTripsAcrossSplitReads() {
        val a = byteArrayOf(1, 0xC0.toByte(), 2, 0xDB.toByte(), 3)
        val b = ByteArray(300) { it.toByte() }
        val stream = Slip.encode(a) + Slip.encode(b)
        assertFalse(Slip.encode(a).copyOfRange(1, Slip.encode(a).size - 1).any { it == 0xC0.toByte() })
        val got = ArrayList<ByteArray>()
        val dec = SlipDecoder { got.add(it) }
        for (i in stream.indices step 7) dec.feed(stream, i, minOf(7, stream.size - i))
        assertEquals(2, got.size)
        assertArrayEquals(a, got[0])
        assertArrayEquals(b, got[1])
    }

    @Test
    fun slipDropsOversizedPackets() {
        val got = ArrayList<ByteArray>()
        val dec = SlipDecoder(maxSize = 10) { got.add(it) }
        dec.feed(Slip.encode(ByteArray(11)) + Slip.encode(byteArrayOf(5)))
        assertEquals(1, got.size)
        assertArrayEquals(byteArrayOf(5), got[0])
    }

    /** Two PPP ends joined by a lossless line. Bytes are queued so neither end is re-entered. */
    private class PppPair(aLocal: Int, aPeer: Int, bLocal: Int, bPeer: Int) {
        val toA = ArrayDeque<ByteArray>()
        val toB = ArrayDeque<ByteArray>()
        val aGot = ArrayList<ByteArray>()
        val bGot = ArrayList<ByteArray>()
        var now = 0L
        val a = PppLink(aLocal, aPeer, { toB.add(it) }, { aGot.add(it) }, { now }, Random(1))
        val b = PppLink(bLocal, bPeer, { toA.add(it) }, { bGot.add(it) }, { now }, Random(2))

        fun pump() {
            var guard = 0
            while ((toA.isNotEmpty() || toB.isNotEmpty()) && guard++ < 1000) {
                toA.poll()?.let { a.feed(it) }
                toB.poll()?.let { b.feed(it) }
            }
        }
    }

    @Test
    fun pppNegotiatesAndCarriesIp() {
        val pair = PppPair(phone, radio, radio, phone)
        pair.a.start()
        pair.b.start()
        pair.pump()
        assertTrue(pair.a.up)
        assertTrue(pair.b.up)
        assertEquals(phone, pair.a.localAddress)
        assertEquals(radio, pair.a.peerAddress)
        assertEquals(phone, pair.b.peerAddress)

        // Payloads full of flag, escape and control bytes survive the framing.
        val packet = Ipv4.buildUdp(phone, Ipv4.BROADCAST, 1, 2, byteArrayOf(0x7E, 0x7D, 0x00, 0x11, 0x13, 0x7E), id = 1)
        assertTrue(pair.a.send(packet))
        pair.pump()
        assertEquals(1, pair.bGot.size)
        assertArrayEquals(packet, pair.bGot[0])
    }

    @Test
    fun pppTakesTheAddressTheRadioProposesAndAssignsOneWhenAsked() {
        // The phone asks for 10.0.0.9; the "radio" end has requestedLocal 0, so it asks for one
        // and must get the phone's defaultPeer.
        val pair = PppPair(Ipv4.parse("10.0.0.9")!!, radio, 0, phone)
        pair.a.start()
        pair.b.start()
        pair.pump()
        assertTrue(pair.a.up)
        assertTrue(pair.b.up)
        assertEquals(radio, pair.b.localAddress)
        assertEquals(radio, pair.a.peerAddress)
    }

    /** Builds a PPP frame the way a radio might: full address/control and a 2-byte protocol. */
    private fun pppFrame(protocol: Int, info: ByteArray): ByteArray {
        val raw = byteArrayOf(0xFF.toByte(), 0x03, (protocol ushr 8).toByte(), protocol.toByte()) + info
        val withFcs = Crc16.appendFcs(raw)
        val out = ByteArrayOutputStream()
        out.write(0x7E)
        for (b in withFcs) {
            val v = b.toInt() and 0xFF
            if (v < 0x20 || v == 0x7E || v == 0x7D) { out.write(0x7D); out.write(v xor 0x20) } else out.write(v)
        }
        out.write(0x7E)
        return out.toByteArray()
    }

    private fun control(code: Int, id: Int, data: ByteArray): ByteArray {
        val p = ByteArray(4 + data.size)
        p[0] = code.toByte()
        p[1] = id.toByte()
        Ipv4.put16(p, 2, p.size)
        data.copyInto(p, 4)
        return p
    }

    /** Decodes everything [bytes] holds into (protocol, info) pairs. */
    private fun decodeFrames(bytes: List<ByteArray>): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        for (f in bytes) {
            val raw = ByteArrayOutputStream()
            var esc = false
            for (b in f) {
                val v = b.toInt() and 0xFF
                when {
                    v == 0x7E -> {}
                    v == 0x7D -> esc = true
                    esc -> { raw.write(v xor 0x20); esc = false }
                    else -> raw.write(v)
                }
            }
            val r = raw.toByteArray()
            assertTrue("bad FCS", Crc16.check(r))
            out.add(Ipv4.get16(r, 2) to r.copyOfRange(4, r.size - 2))
        }
        return out
    }

    @Test
    fun pppRejectsAuthenticationAndAnswersEchoes() {
        val sent = ArrayList<ByteArray>()
        val ppp = PppLink(phone, radio, { sent.add(it) }, {}, { 0L }, Random(3))
        ppp.start()
        val ourRequest = decodeFrames(sent).single()
        assertEquals(PppLink.PROTO_LCP, ourRequest.first)
        sent.clear()

        // The radio asks for PAP (option 3) and an MRU; PAP must be rejected, alone.
        val pap = byteArrayOf(3, 4, 0xC0.toByte(), 0x23)
        val mru = byteArrayOf(1, 4, 0x05, 0xDC.toByte())
        ppp.feed(pppFrame(PppLink.PROTO_LCP, control(PppLink.CONF_REQ, 7, mru + pap)))
        val reject = decodeFrames(sent).single().second
        assertEquals(PppLink.CONF_REJ, reject[0].toInt())
        assertEquals(7, reject[1].toInt())
        assertArrayEquals(pap, reject.copyOfRange(4, reject.size))
        sent.clear()

        // Without PAP it is acknowledged; with our request acknowledged too, LCP opens and IPCP starts.
        ppp.feed(pppFrame(PppLink.PROTO_LCP, control(PppLink.CONF_REQ, 8, mru)))
        ppp.feed(pppFrame(PppLink.PROTO_LCP, control(PppLink.CONF_ACK, ourRequest.second[1].toInt(), ourRequest.second.copyOfRange(4, ourRequest.second.size))))
        assertTrue(ppp.lcpOpen)
        val frames = decodeFrames(sent)
        assertEquals(PppLink.CONF_ACK, frames[0].second[0].toInt())
        assertEquals(PppLink.PROTO_IPCP, frames[1].first)
        sent.clear()

        ppp.feed(pppFrame(PppLink.PROTO_LCP, control(PppLink.ECHO_REQ, 42, byteArrayOf(0, 0, 0, 0, 9))))
        val echo = decodeFrames(sent).single().second
        assertEquals(PppLink.ECHO_REPLY, echo[0].toInt())
        assertEquals(42, echo[1].toInt())
        assertEquals(9, echo[8].toInt())
    }

    @Test
    fun pppGivesUpWhenNobodyAnswers() {
        var now = 0L
        val ppp = PppLink(phone, radio, {}, {}, { now }, Random(4))
        ppp.start()
        while (ppp.failure == null && now < 120_000) {
            now += 500
            ppp.tick()
        }
        assertNotNull(ppp.failure)
        assertFalse(ppp.up)
        assertTrue(ppp.failure!!.contains("PPP"))
    }

    @Test
    fun pppIgnoresFramesWithABadFcs() {
        val sent = ArrayList<ByteArray>()
        val ppp = PppLink(phone, radio, { sent.add(it) }, {}, { 0L }, Random(5))
        ppp.start()
        sent.clear()
        val frame = pppFrame(PppLink.PROTO_LCP, control(PppLink.CONF_REQ, 1, ByteArray(0)))
        frame[frame.size - 3] = (frame[frame.size - 3] + 1).toByte()
        ppp.feed(frame)
        assertTrue(sent.isEmpty())
    }
}
