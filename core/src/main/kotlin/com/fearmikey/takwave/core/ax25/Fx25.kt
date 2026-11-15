// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ax25

/**
 * FX.25 forward error correction (Stensat Group, 2006), compatible with Dire Wolf.
 *
 * An FX.25 transmission is: flags, a 64-bit correlation tag naming the code, then a Reed-Solomon
 * block whose data part is the complete HDLC frame (opening flag, bit-stuffed AX.25 frame and
 * FCS, closing flag, then flag-pattern padding), then the check bytes. Everything goes through
 * NRZI like plain AX.25, so a receiver without FX.25 still decodes the frame inside when it
 * arrives intact; an FX.25 receiver can also repair it.
 */
object Fx25 {
    /** One correlation tag and the shortened Reed-Solomon code it stands for. */
    class Mode(val tag: Int, val tagValue: Long, val dataBytes: Int, val checkBytes: Int)

    /** Tags 0x01 to 0x0B. Values from the FX.25 specification (as in Dire Wolf's fx25_init.c). */
    val MODES: List<Mode> = listOf(
        Mode(0x01, 0xB74DB7DF8A532F3EuL.toLong(), 239, 16),
        Mode(0x02, 0x26FF60A600CC8FDEuL.toLong(), 128, 16),
        Mode(0x03, 0xC7DC0508F3D9B09EuL.toLong(), 64, 16),
        Mode(0x04, 0x8F056EB4369660EEuL.toLong(), 32, 16),
        Mode(0x05, 0x6E260B1AC5835FAEuL.toLong(), 223, 32),
        Mode(0x06, 0xFF94DC634F1CFF4EuL.toLong(), 128, 32),
        Mode(0x07, 0x1EB7B9CDBC09C00EuL.toLong(), 64, 32),
        Mode(0x08, 0xDBF869BD2DBB1776uL.toLong(), 32, 32),
        Mode(0x09, 0x3ADB0C13DEAE2836uL.toLong(), 191, 64),
        Mode(0x0A, 0xAB69DB6A543188D6uL.toLong(), 128, 64),
        Mode(0x0B, 0x4A4ABEC4A724B796uL.toLong(), 64, 64),
    )

    /** A received tag may differ from the real one in this many bits (Dire Wolf's limit, chosen to avoid false triggers at 1200 baud). */
    const val TAG_TOLERANCE_BITS = 8

    private val codecs = HashMap<Int, ReedSolomon>()

    @Synchronized
    internal fun codec(nroots: Int): ReedSolomon = codecs.getOrPut(nroots) { ReedSolomon(nroots) }

    /** The smallest code with [checkBytes] check bytes whose data part holds [dataBytes], or null. */
    fun pickMode(checkBytes: Int, dataBytes: Int): Mode? =
        MODES.filter { it.checkBytes == checkBytes && it.dataBytes >= dataBytes }.minByOrNull { it.dataBytes }

    /** The mode whose tag is within [TAG_TOLERANCE_BITS] of [accum], or null. */
    fun matchTag(accum: Long): Mode? =
        MODES.firstOrNull { java.lang.Long.bitCount(accum xor it.tagValue) <= TAG_TOLERANCE_BITS }

    /**
     * Builds the logical bit stream for one frame sent as FX.25 with [checkBytes] check bytes
     * (16, 32 or 64), or returns null when the frame is too long for any such code; the caller
     * then sends plain AX.25. [frame] is the AX.25 frame without FCS.
     */
    fun encodeFrame(frame: ByteArray, preambleFlags: Int, tailFlags: Int, checkBytes: Int): BooleanArray? {
        val hdlc = BitBuffer((frame.size + 4) * 10)
        Hdlc.addFlag(hdlc)
        Hdlc.addStuffed(hdlc, Crc16.appendFcs(frame))
        Hdlc.addFlag(hdlc)
        val mode = pickMode(checkBytes, (hdlc.size + 7) / 8) ?: return null

        // Pad with the flag pattern, continuing bit by bit (as Dire Wolf does), to fill the data part.
        var padBit = 0
        while (hdlc.size < mode.dataBytes * 8) {
            hdlc.add((Hdlc.FLAG ushr padBit) and 1 != 0)
            padBit = (padBit + 1) and 7
        }
        val rs = codec(mode.checkBytes)
        val data = ByteArray(rs.k)
        for (i in 0 until mode.dataBytes * 8) {
            if (hdlc[i]) data[i ushr 3] = (data[i ushr 3].toInt() or (1 shl (i and 7))).toByte()
        }
        val check = rs.encode(data)

        val out = BitBuffer((preambleFlags + tailFlags) * 8 + 64 + (mode.dataBytes + mode.checkBytes) * 8)
        repeat(maxOf(1, preambleFlags)) { Hdlc.addFlag(out) }
        for (i in 0 until 64) out.add((mode.tagValue ushr i) and 1L != 0L)
        for (i in 0 until mode.dataBytes) addByte(out, data[i])
        for (b in check) addByte(out, b)
        repeat(maxOf(1, tailFlags)) { Hdlc.addFlag(out) }
        return out.toBooleanArray()
    }

    private fun addByte(out: BitBuffer, b: Byte) {
        for (i in 0 until 8) out.add((b.toInt() ushr i) and 1 != 0)
    }
}

/**
 * Bit-at-a-time FX.25 receiver. Feed it the same NRZI-decoded bits as the HDLC deframer. When it
 * sees a correlation tag it collects the Reed-Solomon block, corrects it, and hands the AX.25
 * frame inside (FCS checked and removed) to [onFrame] with the number of bytes it repaired.
 */
class Fx25Receiver(private val onFrame: (frame: ByteArray, corrected: Int) -> Unit) {
    private var accum = 0L
    private var mode: Fx25.Mode? = null
    private val block = ByteArray(ReedSolomon.NN)
    private var bytes = 0
    private var current = 0
    private var bit = 0

    fun reset() {
        mode = null
        accum = 0L
    }

    fun pushBit(value: Boolean) {
        val m = mode
        if (m == null) {
            // Bits arrive least significant first, so each one enters at the top.
            accum = (accum ushr 1) or (if (value) Long.MIN_VALUE else 0L)
            val found = Fx25.matchTag(accum) ?: return
            mode = found
            block.fill(0)
            bytes = 0
            current = 0
            bit = 0
            return
        }
        if (value) current = current or (1 shl bit)
        if (++bit < 8) return
        // Data fills the front of the 255-byte block, check bytes its end; the unsent middle stays zero.
        val pos = if (bytes < m.dataBytes) bytes else ReedSolomon.NN - m.checkBytes + (bytes - m.dataBytes)
        block[pos] = current.toByte()
        bytes++
        current = 0
        bit = 0
        if (bytes == m.dataBytes + m.checkBytes) {
            finish(m)
            reset()
        }
    }

    private fun finish(m: Fx25.Mode) {
        val corrected = Fx25.codec(m.checkBytes).decode(block, m.dataBytes until ReedSolomon.NN - m.checkBytes)
        if (corrected < 0 || block[0] != Hdlc.FLAG.toByte()) return
        var frame: ByteArray? = null
        val deframer = HdlcDeframer { if (frame == null) frame = it }
        for (i in 0 until m.dataBytes * 8) {
            deframer.pushBit((block[i ushr 3].toInt() ushr (i and 7)) and 1 != 0)
            if (frame != null) break
        }
        frame?.let { onFrame(it, corrected) }
    }
}
