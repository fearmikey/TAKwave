// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ax25

/** An AX.25 station address: 1-6 upper-case letters/digits plus an SSID of 0-15. */
data class Ax25Address(val call: String, val ssid: Int = 0) {
    init {
        require(isValidCall(call)) { "invalid callsign '$call'" }
        require(ssid in 0..15) { "invalid SSID $ssid" }
    }

    override fun toString(): String = if (ssid == 0) call else "$call-$ssid"

    /**
     * Encodes to the 7-byte on-air form: six ASCII characters shifted left one bit (space padded)
     * and an SSID byte. [last] sets the address-extension bit that ends the address field and
     * [cBit] sets the command/response (or has-been-repeated) bit.
     */
    fun encode(last: Boolean, cBit: Boolean = false): ByteArray {
        val out = ByteArray(ENCODED_SIZE)
        for (i in 0 until 6) {
            val ch = if (i < call.length) call[i] else ' '
            out[i] = (ch.code shl 1).toByte()
        }
        out[6] = (0x60 or (if (cBit) 0x80 else 0) or (ssid shl 1) or (if (last) 1 else 0)).toByte()
        return out
    }

    companion object {
        const val ENCODED_SIZE = 7

        fun isValidCall(call: String): Boolean =
            call.length in 1..6 && call.all { it in 'A'..'Z' || it in '0'..'9' }

        /** Parses "CALL" or "CALL-SSID" (case-insensitive). Returns null when invalid. */
        fun parse(text: String): Ax25Address? {
            val t = text.trim().uppercase()
            val dash = t.indexOf('-')
            val call = if (dash < 0) t else t.substring(0, dash)
            val ssid = if (dash < 0) 0 else t.substring(dash + 1).toIntOrNull() ?: return null
            if (!isValidCall(call) || ssid !in 0..15) return null
            return Ax25Address(call, ssid)
        }

        /** Decodes 7 bytes at [offset]. Returns null when the bytes are not a plausible address. */
        fun decode(data: ByteArray, offset: Int): Ax25Address? {
            if (offset < 0 || offset + ENCODED_SIZE > data.size) return null
            val sb = StringBuilder(6)
            var padding = false
            for (i in 0 until 6) {
                val b = data[offset + i].toInt() and 0xFF
                if (b and 1 != 0) return null
                val ch = (b ushr 1).toChar()
                if (ch == ' ') {
                    padding = true
                } else {
                    if (padding) return null
                    sb.append(ch)
                }
            }
            val call = sb.toString()
            if (!isValidCall(call)) return null
            val ssid = (data[offset + 6].toInt() ushr 1) and 0x0F
            return Ax25Address(call, ssid)
        }
    }
}

/**
 * An AX.25 frame as carried by KISS: addresses, control, PID and information field, without
 * flags or FCS. TAKwave only originates UI frames (control 0x03) but decodes digipeater paths
 * so that frames relayed by a digipeater are still understood.
 */
class Ax25Frame(
    val destination: Ax25Address,
    val source: Ax25Address,
    val info: ByteArray,
    val digipeaters: List<Ax25Address> = emptyList(),
    val control: Int = CONTROL_UI,
    val pid: Int = PID_NO_LAYER3,
) {
    val isUi: Boolean get() = (control and 0xEF) == CONTROL_UI

    fun encode(): ByteArray {
        require(digipeaters.size <= MAX_DIGIPEATERS) { "too many digipeaters" }
        val out = ByteArray((2 + digipeaters.size) * Ax25Address.ENCODED_SIZE + 2 + info.size)
        var p = 0
        // Command frame: C bit set on destination, clear on source.
        destination.encode(last = false, cBit = true).copyInto(out, p); p += 7
        source.encode(last = digipeaters.isEmpty()).copyInto(out, p); p += 7
        digipeaters.forEachIndexed { i, d ->
            d.encode(last = i == digipeaters.lastIndex).copyInto(out, p); p += 7
        }
        out[p++] = control.toByte()
        out[p++] = pid.toByte()
        info.copyInto(out, p)
        return out
    }

    companion object {
        const val CONTROL_UI = 0x03
        const val PID_NO_LAYER3 = 0xF0
        const val MAX_DIGIPEATERS = 8
        /** Two addresses plus the control byte: the shortest frame AX.25 allows. */
        const val MIN_SIZE = 15

        /** Decodes a frame (no FCS). Returns null for anything malformed; never throws. */
        fun decode(data: ByteArray): Ax25Frame? {
            if (data.size < MIN_SIZE) return null
            val dest = Ax25Address.decode(data, 0) ?: return null
            val src = Ax25Address.decode(data, 7) ?: return null
            var p = 14
            var last = data[13].toInt() and 1 != 0
            val digis = ArrayList<Ax25Address>()
            while (!last) {
                if (digis.size >= MAX_DIGIPEATERS) return null
                val d = Ax25Address.decode(data, p) ?: return null
                last = data[p + 6].toInt() and 1 != 0
                digis.add(d)
                p += 7
            }
            if (p >= data.size) return null
            val control = data[p++].toInt() and 0xFF
            // Only UI and I frames carry a PID byte.
            val hasPid = (control and 0xEF) == CONTROL_UI || (control and 1) == 0
            var pid = PID_NO_LAYER3
            if (hasPid) {
                if (p >= data.size) return null
                pid = data[p++].toInt() and 0xFF
            }
            return Ax25Frame(dest, src, data.copyOfRange(p, data.size), digis, control, pid)
        }
    }
}
