// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.io

/**
 * Turns the text an operator types for a PTT command into the bytes sent to the radio.
 *
 * Printable ASCII is sent as-is. Escapes: `\r`, `\n`, `\t`, `\\`, and `\xHH` for any byte, so a
 * binary command such as Icom CI-V can be written as `\xFE\xFE\x94\xE0\x1C\x00\x01\xFD`.
 */
object PttCommand {
    const val MAX_BYTES = 64

    /** Throws [IllegalArgumentException] with a user-readable message when [text] is not a valid command. */
    fun parse(text: String): ByteArray {
        val out = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '\\') {
                require(c.code in 0x20..0x7E) { "only printable ASCII is allowed; write other bytes as \\xHH" }
                out.add(c.code.toByte())
                i++
                continue
            }
            require(i + 1 < text.length) { "a command cannot end with a single backslash" }
            when (val e = text[i + 1]) {
                'r' -> out.add('\r'.code.toByte())
                'n' -> out.add('\n'.code.toByte())
                't' -> out.add('\t'.code.toByte())
                '\\' -> out.add('\\'.code.toByte())
                'x', 'X' -> {
                    val hex = if (i + 4 <= text.length) text.substring(i + 2, i + 4) else ""
                    val value = if (hex.length == 2 && hex.all { it.isHexDigit() }) hex.toInt(16) else -1
                    require(value >= 0) { "\\x must be followed by two hex digits" }
                    out.add(value.toByte())
                    i += 2
                }
                else -> throw IllegalArgumentException("unknown escape \\$e")
            }
            i += 2
        }
        require(out.isNotEmpty()) { "the command is empty" }
        require(out.size <= MAX_BYTES) { "the command is longer than $MAX_BYTES bytes" }
        return out.toByteArray()
    }

    /** The problem with [text], or null when it is a valid command. */
    fun errorOf(text: String): String? = try {
        parse(text)
        null
    } catch (e: IllegalArgumentException) {
        e.message
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
