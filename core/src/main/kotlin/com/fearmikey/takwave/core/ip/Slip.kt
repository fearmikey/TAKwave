// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ip

import java.io.ByteArrayOutputStream

/** SLIP framing (RFC 1055): IP packets on a serial line, separated by END bytes. */
object Slip {
    const val END = 0xC0
    const val ESC = 0xDB
    const val ESC_END = 0xDC
    const val ESC_ESC = 0xDD

    /** One packet, with an END before it (to flush line noise) and after it. */
    fun encode(packet: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(packet.size + 8)
        out.write(END)
        for (b in packet) {
            when (val v = b.toInt() and 0xFF) {
                END -> { out.write(ESC); out.write(ESC_END) }
                ESC -> { out.write(ESC); out.write(ESC_ESC) }
                else -> out.write(v)
            }
        }
        out.write(END)
        return out.toByteArray()
    }
}

/** Splits a SLIP byte stream into packets. Oversized packets are dropped up to the next END. */
class SlipDecoder(private val maxSize: Int = 2048, private val onPacket: (ByteArray) -> Unit) {
    private val buf = ByteArray(maxSize)
    private var len = 0
    private var escaped = false
    private var overflow = false

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        for (i in offset until offset + length) {
            var v = data[i].toInt() and 0xFF
            if (v == Slip.END) {
                if (len > 0 && !overflow) onPacket(buf.copyOf(len))
                len = 0
                escaped = false
                overflow = false
                continue
            }
            if (escaped) {
                escaped = false
                v = when (v) {
                    Slip.ESC_END -> Slip.END
                    Slip.ESC_ESC -> Slip.ESC
                    else -> v // Protocol violation; RFC 1055 keeps the byte.
                }
            } else if (v == Slip.ESC) {
                escaped = true
                continue
            }
            if (len == maxSize) {
                overflow = true
            } else if (!overflow) {
                buf[len++] = v.toByte()
            }
        }
    }
}
