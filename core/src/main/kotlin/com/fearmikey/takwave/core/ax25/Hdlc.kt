// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ax25

/** A growable bit sequence. Bits are stored one per byte for simplicity; frames are tiny. */
class BitBuffer(initialCapacity: Int = 1024) {
    private var bits = ByteArray(initialCapacity)
    var size = 0
        private set

    fun add(bit: Boolean) {
        if (size == bits.size) bits = bits.copyOf(size * 2)
        bits[size++] = if (bit) 1 else 0
    }

    operator fun get(index: Int): Boolean = bits[index].toInt() != 0

    fun toBooleanArray(): BooleanArray = BooleanArray(size) { bits[it].toInt() != 0 }
}

/** HDLC framing as used by AX.25: 0x7E flags, zero-bit stuffing, bytes sent LSB first. */
object Hdlc {
    const val FLAG = 0x7E

    /** Appends one unstuffed flag. */
    fun addFlag(out: BitBuffer) {
        for (i in 0 until 8) out.add((FLAG ushr i) and 1 != 0)
    }

    /** Appends [data] LSB first, inserting a 0 after every run of five 1 bits. */
    fun addStuffed(out: BitBuffer, data: ByteArray) {
        var ones = 0
        for (b in data) {
            for (i in 0 until 8) {
                val bit = (b.toInt() ushr i) and 1 != 0
                out.add(bit)
                if (bit) {
                    if (++ones == 5) {
                        out.add(false)
                        ones = 0
                    }
                } else {
                    ones = 0
                }
            }
        }
    }

    /**
     * Builds the complete logical bit stream for one frame: [preambleFlags] flags, the frame and
     * its FCS (stuffed), then [tailFlags] flags. [frame] is the AX.25 frame without FCS.
     */
    fun encodeFrame(frame: ByteArray, preambleFlags: Int, tailFlags: Int): BooleanArray {
        val out = BitBuffer((frame.size + 2) * 10 + (preambleFlags + tailFlags) * 8)
        repeat(maxOf(1, preambleFlags)) { addFlag(out) }
        addStuffed(out, Crc16.appendFcs(frame))
        repeat(maxOf(1, tailFlags)) { addFlag(out) }
        return out.toBooleanArray()
    }
}

/** NRZI line coding: a 0 bit is a level change, a 1 bit is no change. */
object Nrzi {
    fun encode(bits: BooleanArray, initialLevel: Boolean = true): BooleanArray {
        var level = initialLevel
        return BooleanArray(bits.size) { i ->
            if (!bits[i]) level = !level
            level
        }
    }

    fun decode(levels: BooleanArray, initialLevel: Boolean = true): BooleanArray {
        var prev = initialLevel
        return BooleanArray(levels.size) { i ->
            val bit = levels[i] == prev
            prev = levels[i]
            bit
        }
    }
}

/**
 * Bit-at-a-time HDLC receiver. Feed it NRZI-decoded bits; it finds flags, removes stuffed bits,
 * checks the FCS and hands each good frame (FCS stripped) to [onFrame].
 */
class HdlcDeframer(
    private val maxFrameBytes: Int = 1024,
    private val onFrame: (ByteArray) -> Unit,
) {
    private val buf = ByteArray(maxFrameBytes + 2)
    private var bitCount = 0
    private var ones = 0
    private var inFrame = false

    /** True between an opening flag and the next abort; a hint that a frame may be arriving. */
    val receiving: Boolean get() = inFrame

    /** Number of flags seen since construction; used for carrier detection. */
    var flagCount = 0L
        private set

    fun reset() {
        bitCount = 0
        ones = 0
        inFrame = false
    }

    fun pushBit(bit: Boolean) {
        if (bit) {
            ones++
            if (ones <= 5) {
                append(true)
            } else if (ones >= 7) {
                // Seven or more 1s in a row: abort / idle.
                inFrame = false
                bitCount = 0
            }
            // ones == 6: possibly a flag; decided by the next bit.
            return
        }
        when (ones) {
            5 -> { /* stuffed zero: discard */ }
            6 -> {
                // 01111110: a flag. The leading 0 and five of the 1s were already appended.
                flagCount++
                val dataBits = bitCount - 6
                if (inFrame && dataBits >= (Ax25Frame.MIN_SIZE + 2) * 8 && dataBits % 8 == 0) {
                    val len = dataBits / 8
                    if (Crc16.check(buf, len)) onFrame(buf.copyOf(len - 2))
                }
                inFrame = true
                bitCount = 0
            }
            else -> append(false)
        }
        ones = 0
    }

    private fun append(bit: Boolean) {
        if (!inFrame) return
        val byteIndex = bitCount ushr 3
        if (byteIndex >= buf.size) {
            inFrame = false
            bitCount = 0
            return
        }
        val mask = 1 shl (bitCount and 7)
        buf[byteIndex] = if (bit) (buf[byteIndex].toInt() or mask).toByte()
        else (buf[byteIndex].toInt() and mask.inv()).toByte()
        bitCount++
    }
}
