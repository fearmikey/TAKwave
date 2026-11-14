// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ax25

/**
 * CRC-16-CCITT as used for the AX.25 / HDLC frame check sequence (a.k.a. CRC-16/X-25):
 * reflected polynomial 0x8408, initial value 0xFFFF, final XOR 0xFFFF.
 * The FCS goes on the air low byte first.
 */
object Crc16 {
    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8408 else c ushr 1 }
        c
    }

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = (crc ushr 8) xor table[(crc xor data[i].toInt()) and 0xFF]
        }
        return crc xor 0xFFFF
    }

    /** Returns [frame] with the two FCS bytes appended (low byte first). */
    fun appendFcs(frame: ByteArray): ByteArray {
        val crc = compute(frame)
        val out = frame.copyOf(frame.size + 2)
        out[frame.size] = (crc and 0xFF).toByte()
        out[frame.size + 1] = (crc ushr 8).toByte()
        return out
    }

    /** True when the last two bytes of [frameWithFcs] are a valid FCS for the preceding bytes. */
    fun check(frameWithFcs: ByteArray, length: Int = frameWithFcs.size): Boolean {
        if (length < 3) return false
        val crc = compute(frameWithFcs, 0, length - 2)
        return (crc and 0xFF) == (frameWithFcs[length - 2].toInt() and 0xFF) &&
            (crc ushr 8) == (frameWithFcs[length - 1].toInt() and 0xFF)
    }
}
