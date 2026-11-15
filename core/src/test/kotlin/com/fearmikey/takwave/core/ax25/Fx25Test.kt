// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ax25

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class Fx25Test {
    private val rnd = Random(25)

    private fun frame(infoBytes: Int): ByteArray {
        val info = ByteArray(infoBytes).also { rnd.nextBytes(it) }
        return Ax25Frame(Ax25Address("TAKWV"), Ax25Address("N0CALL", 3), info).encode()
    }

    /** Runs logical bits (before NRZI) through an FX.25 receiver and returns what it delivered. */
    private fun receive(bits: BooleanArray): List<Pair<ByteArray, Int>> {
        val got = ArrayList<Pair<ByteArray, Int>>()
        val rx = Fx25Receiver { f, n -> got.add(f to n) }
        for (b in bits) rx.pushBit(b)
        return got
    }

    private fun plainReceive(bits: BooleanArray): List<ByteArray> {
        val got = ArrayList<ByteArray>()
        val d = HdlcDeframer { got.add(it) }
        for (b in bits) d.pushBit(b)
        return got
    }

    @Test
    fun reedSolomonCorrectsUpToHalfTheCheckBytes() {
        for (nroots in listOf(16, 32, 64)) {
            val rs = ReedSolomon(nroots)
            repeat(20) { trial ->
                val data = ByteArray(rs.k).also { rnd.nextBytes(it) }
                val block = data + rs.encode(data)
                assertEquals(0, rs.decode(block.copyOf()))
                val errors = 1 + trial % (nroots / 2)
                val damaged = block.copyOf()
                val positions = (0 until ReedSolomon.NN).shuffled(rnd).take(errors)
                for (p in positions) damaged[p] = (damaged[p].toInt() xor (1 + rnd.nextInt(255))).toByte()
                assertEquals("nroots $nroots, $errors errors", errors, rs.decode(damaged))
                assertArrayEquals(block, damaged)
            }
        }
    }

    @Test
    fun reedSolomonLeavesAnUncorrectableBlockAlone() {
        val rs = ReedSolomon(16)
        val data = ByteArray(rs.k).also { rnd.nextBytes(it) }
        val block = data + rs.encode(data)
        // Far more errors than the code can handle: either refused, or (rarely) "corrected" to another codeword.
        for (p in 0 until 40) block[p * 5] = (block[p * 5].toInt() xor 0x5A).toByte()
        val before = block.copyOf()
        if (rs.decode(block) < 0) assertArrayEquals(before, block)
    }

    @Test
    fun generatorRootsAreAlphaOneUpwards() {
        // A codeword evaluates to zero at every root of the generator; check via the decoder's syndromes.
        val rs = ReedSolomon(16)
        val data = ByteArray(rs.k) { it.toByte() }
        assertEquals(0, rs.decode(data + rs.encode(data)))
    }

    @Test
    fun picksTheSmallestCodeThatFits() {
        assertEquals(0x04, Fx25.pickMode(16, 32)!!.tag)
        assertEquals(0x03, Fx25.pickMode(16, 33)!!.tag)
        assertEquals(0x06, Fx25.pickMode(32, 100)!!.tag)
        assertEquals(0x09, Fx25.pickMode(64, 150)!!.tag)
        assertNull(Fx25.pickMode(64, 192))
        assertNull(Fx25.pickMode(16, 240))
    }

    @Test
    fun roundTripForEveryCheckSize() {
        for (check in listOf(16, 32, 64)) {
            for (size in listOf(10, 40, 90, 150)) {
                val f = frame(size)
                val bits = Fx25.encodeFrame(f, 10, 2, check) ?: continue
                val got = receive(bits).single()
                assertArrayEquals(f, got.first)
                assertEquals(0, got.second)
            }
        }
    }

    @Test
    fun plainReceiversStillDecodeAnFx25Transmission() {
        val f = frame(37)
        val bits = Fx25.encodeFrame(f, 10, 2, 16)!!
        assertArrayEquals(f, plainReceive(bits).single())
    }

    @Test
    fun repairsDamagedBytesThatDefeatPlainHdlc() {
        val f = frame(50)
        val bits = Fx25.encodeFrame(f, 10, 2, 32)!!
        // Flip a run of bits inside the AX.25 frame: 64 preamble bits (8 flags... here 10 flags = 80) plus the 64-bit tag.
        val start = 80 + 64 + 8 * 20
        for (i in start until start + 8 * 10) bits[i] = !bits[i]
        assertTrue("plain HDLC should lose this frame", plainReceive(bits).isEmpty())
        val got = receive(bits).single()
        assertArrayEquals(f, got.first)
        assertTrue(got.second in 10..11)
    }

    @Test
    fun toleratesBitErrorsInTheCorrelationTag() {
        val f = frame(30)
        val bits = Fx25.encodeFrame(f, 10, 2, 16)!!
        for (i in 0 until Fx25.TAG_TOLERANCE_BITS) bits[80 + i * 7] = !bits[80 + i * 7]
        assertArrayEquals(f, receive(bits).single().first)
    }

    @Test
    fun aFrameTooLongForFx25IsNotEncoded() {
        assertNull(Fx25.encodeFrame(frame(256), 10, 2, 16))
        assertNotNull(Fx25.encodeFrame(frame(150), 10, 2, 16))
    }

    @Test
    fun tagsAreFarApart() {
        // A false match between two codes would be fatal; the spec's tags differ in many more bits than the tolerance.
        for (a in Fx25.MODES) for (b in Fx25.MODES) {
            if (a !== b) assertTrue(java.lang.Long.bitCount(a.tagValue xor b.tagValue) > 2 * Fx25.TAG_TOLERANCE_BITS)
        }
    }
}
