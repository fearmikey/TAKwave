// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ax25

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class Ax25Test {
    @Test
    fun crcKnownVectors() {
        assertEquals(0x906E, Crc16.compute("123456789".toByteArray(Charsets.US_ASCII)))
        assertEquals(0x0000, Crc16.compute(ByteArray(0)))
        // Residue property: CRC over data plus its FCS always validates.
        val withFcs = Crc16.appendFcs("TAKwave".toByteArray())
        assertTrue(Crc16.check(withFcs))
        withFcs[2] = (withFcs[2].toInt() xor 0x10).toByte()
        assertFalse(Crc16.check(withFcs))
    }

    @Test
    fun addressEncodeDecode() {
        val a = Ax25Address("N0CALL", 7)
        val enc = a.encode(last = true)
        assertEquals(7, enc.size)
        assertEquals('N'.code shl 1, enc[0].toInt() and 0xFF)
        assertEquals(0x60 or (7 shl 1) or 1, enc[6].toInt() and 0xFF)
        assertEquals(a, Ax25Address.decode(enc, 0))

        val short = Ax25Address("K1A", 0)
        val encShort = short.encode(last = false)
        assertEquals(' '.code shl 1, encShort[5].toInt() and 0xFF)
        assertEquals(0, encShort[6].toInt() and 1)
        assertEquals(short, Ax25Address.decode(encShort, 0))

        for (ssid in 0..15) {
            assertEquals(ssid, Ax25Address.decode(Ax25Address("TAKWV", ssid).encode(false), 0)!!.ssid)
        }
    }

    @Test
    fun addressParsing() {
        assertEquals(Ax25Address("W1AW", 0), Ax25Address.parse("w1aw"))
        assertEquals(Ax25Address("W1AW", 15), Ax25Address.parse(" W1AW-15 "))
        assertEquals("W1AW-9", Ax25Address.parse("W1AW-9").toString())
        assertEquals("W1AW", Ax25Address.parse("W1AW-0").toString())
        assertNull(Ax25Address.parse(""))
        assertNull(Ax25Address.parse("TOOLONG1"))
        assertNull(Ax25Address.parse("W1AW-16"))
        assertNull(Ax25Address.parse("W1AW-x"))
        assertNull(Ax25Address.parse("W1 AW"))
        assertNull(Ax25Address.parse("W1ÅW"))
    }

    @Test
    fun frameRoundTrip() {
        val info = byteArrayOf(0x54, 1, 2, 3, -1)
        val f = Ax25Frame(Ax25Address("TAKWV"), Ax25Address("N0CALL", 3), info)
        val bytes = f.encode()
        assertEquals(14 + 2 + info.size, bytes.size)
        assertEquals(0x03, bytes[14].toInt())
        assertEquals(0xF0, bytes[15].toInt() and 0xFF)
        val d = Ax25Frame.decode(bytes)!!
        assertEquals("TAKWV", d.destination.toString())
        assertEquals("N0CALL-3", d.source.toString())
        assertTrue(d.isUi)
        assertEquals(0xF0, d.pid)
        assertArrayEquals(info, d.info)

        val viaDigi = Ax25Frame(f.destination, f.source, info, listOf(Ax25Address("WIDE1", 1)))
        val d2 = Ax25Frame.decode(viaDigi.encode())!!
        assertEquals(listOf(Ax25Address("WIDE1", 1)), d2.digipeaters)
        assertArrayEquals(info, d2.info)
    }

    @Test
    fun frameDecodeNeverThrowsOnGarbage() {
        val rnd = Random(1)
        repeat(20_000) {
            val b = ByteArray(rnd.nextInt(60))
            rnd.nextBytes(b)
            Ax25Frame.decode(b)
        }
        assertNull(Ax25Frame.decode(ByteArray(0)))
        assertNull(Ax25Frame.decode(ByteArray(14)))
    }

    @Test
    fun bitStuffing() {
        val out = BitBuffer()
        Hdlc.addStuffed(out, byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        // 16 ones become 11111 0 11111 0 11111 0 1
        val expected = "1111101111101111101"
        assertEquals(expected, bitsToString(out.toBooleanArray()))

        val out2 = BitBuffer()
        Hdlc.addStuffed(out2, byteArrayOf(0x7E))
        // 0x7E LSB first is 01111110; stuffing breaks the run of six ones.
        assertEquals("011111010", bitsToString(out2.toBooleanArray()))
    }

    @Test
    fun stuffedStreamNeverContainsSixOnes() {
        val rnd = Random(2)
        repeat(200) {
            val data = ByteArray(1 + rnd.nextInt(80))
            rnd.nextBytes(data)
            if (it % 4 == 0) data.fill(0xFF.toByte())
            val out = BitBuffer()
            Hdlc.addStuffed(out, data)
            assertFalse(bitsToString(out.toBooleanArray()).contains("111111"))
        }
    }

    @Test
    fun nrzi() {
        val bits = booleanArrayOf(true, false, false, true, true, false, true)
        val levels = Nrzi.encode(bits, initialLevel = true)
        assertEquals("1011100", bitsToString(levels))
        assertArrayEquals(bits, Nrzi.decode(levels, initialLevel = true))
        // Polarity inversion must not matter after the first bit.
        val inverted = BooleanArray(levels.size) { !levels[it] }
        val dec = Nrzi.decode(inverted, initialLevel = true)
        assertArrayEquals(bits.copyOfRange(1, bits.size), dec.copyOfRange(1, dec.size))
    }

    @Test
    fun hdlcRoundTrip() {
        val rnd = Random(3)
        val received = ArrayList<ByteArray>()
        val deframer = HdlcDeframer { received.add(it) }
        val sent = ArrayList<ByteArray>()
        repeat(100) { n ->
            val info = ByteArray(rnd.nextInt(200))
            rnd.nextBytes(info)
            if (n % 10 == 0) info.fill(0xFF.toByte())
            if (n % 10 == 1) info.fill(0x7E)
            val frame = Ax25Frame(Ax25Address("TAKWV"), Ax25Address("N0CALL", n % 16), info).encode()
            sent.add(frame)
            val bits = Nrzi.decode(Nrzi.encode(Hdlc.encodeFrame(frame, 3, 2)))
            for (b in bits) deframer.pushBit(b)
        }
        assertEquals(sent.size, received.size)
        for (i in sent.indices) assertArrayEquals(sent[i], received[i])
    }

    @Test
    fun hdlcRejectsCorruptionAndSurvivesNoise() {
        val received = ArrayList<ByteArray>()
        val deframer = HdlcDeframer { received.add(it) }
        val frame = Ax25Frame(Ax25Address("TAKWV"), Ax25Address("N0CALL"), ByteArray(40) { it.toByte() }).encode()
        val bits = Hdlc.encodeFrame(frame, 2, 2)
        bits[60] = !bits[60]
        for (b in bits) deframer.pushBit(b)
        assertEquals(0, received.size)

        val rnd = Random(4)
        repeat(200_000) { deframer.pushBit(rnd.nextBoolean()) }
        // A random 16-bit CRC match on noise is possible but must be rare.
        assertTrue(received.size <= 1)

        received.clear()
        for (b in Hdlc.encodeFrame(frame, 2, 2)) deframer.pushBit(b)
        assertEquals(1, received.size)
        assertNotNull(Ax25Frame.decode(received[0]))
    }

    private fun bitsToString(bits: BooleanArray) = bits.joinToString("") { if (it) "1" else "0" }
}
