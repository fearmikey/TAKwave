// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PttCommandTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun plainAsciiIsSentAsIs() {
        assertArrayEquals("TX1;".toByteArray(Charsets.US_ASCII), PttCommand.parse("TX1;"))
    }

    @Test
    fun escapesAreDecoded() {
        assertArrayEquals(bytes(0x54, 0x58, 0x0D, 0x0A, 0x09, 0x5C), PttCommand.parse("TX\\r\\n\\t\\\\"))
    }

    @Test
    fun hexEscapesGiveBinaryCommands() {
        assertArrayEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x1C, 0x00, 0x01, 0xFD),
            PttCommand.parse("\\xFE\\xfe\\x94\\xE0\\x1C\\x00\\x01\\xFD"),
        )
    }

    @Test
    fun invalidCommandsAreRejected() {
        assertNotNull(PttCommand.errorOf(""))
        assertNotNull(PttCommand.errorOf("TX\\"))
        assertNotNull(PttCommand.errorOf("\\xF"))
        assertNotNull(PttCommand.errorOf("\\xZZ"))
        assertNotNull(PttCommand.errorOf("\\q"))
        assertNotNull(PttCommand.errorOf("TXé"))
        assertNotNull(PttCommand.errorOf("A".repeat(PttCommand.MAX_BYTES + 1)))
        assertNull(PttCommand.errorOf("A".repeat(PttCommand.MAX_BYTES)))
        assertNull(PttCommand.errorOf("RX;"))
    }
}
