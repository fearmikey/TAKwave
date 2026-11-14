// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.kiss

import java.io.ByteArrayOutputStream

/** KISS TNC framing (Chepponis / Karn, 1987): FEND-delimited frames with FESC escaping. */
object Kiss {
    const val FEND = 0xC0
    const val FESC = 0xDB
    const val TFEND = 0xDC
    const val TFESC = 0xDD
    const val CMD_DATA = 0x00

    /** Wraps an AX.25 frame (no FCS) as a KISS data frame for TNC [port]. */
    fun encode(frame: ByteArray, port: Int = 0): ByteArray {
        val out = ByteArrayOutputStream(frame.size + 8)
        out.write(FEND)
        out.write(((port and 0x0F) shl 4) or CMD_DATA)
        for (b in frame) {
            when (b.toInt() and 0xFF) {
                FEND -> { out.write(FESC); out.write(TFEND) }
                FESC -> { out.write(FESC); out.write(TFESC) }
                else -> out.write(b.toInt())
            }
        }
        out.write(FEND)
        return out.toByteArray()
    }
}

/** Streaming KISS decoder. Delivers the payload of data frames; other commands are ignored. */
class KissDecoder(private val maxFrameBytes: Int = 1024, private val onFrame: (ByteArray) -> Unit) {
    private val buf = ByteArrayOutputStream(256)
    private var escaped = false
    private var overflow = false

    fun feed(data: ByteArray, offset: Int, length: Int) {
        for (i in offset until offset + length) {
            val b = data[i].toInt() and 0xFF
            when {
                b == Kiss.FEND -> {
                    if (!overflow && buf.size() > 1) {
                        val raw = buf.toByteArray()
                        if (raw[0].toInt() and 0x0F == Kiss.CMD_DATA) onFrame(raw.copyOfRange(1, raw.size))
                    }
                    buf.reset()
                    escaped = false
                    overflow = false
                }
                overflow -> {}
                escaped -> {
                    escaped = false
                    when (b) {
                        Kiss.TFEND -> buf.write(Kiss.FEND)
                        Kiss.TFESC -> buf.write(Kiss.FESC)
                        else -> overflow = true // protocol violation: discard this frame
                    }
                }
                b == Kiss.FESC -> escaped = true
                else -> buf.write(b)
            }
            if (buf.size() > maxFrameBytes + 1) overflow = true
        }
    }
}
