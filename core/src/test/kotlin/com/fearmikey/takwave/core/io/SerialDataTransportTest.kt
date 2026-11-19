// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.io

import com.fearmikey.takwave.core.ax25.Ax25Address
import com.fearmikey.takwave.core.ax25.Ax25Frame
import com.fearmikey.takwave.core.kiss.Kiss
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SerialDataTransportTest {
    private val frame = Ax25Frame(Ax25Address("TAKWV"), Ax25Address("N0CALL", 1), ByteArray(60) { (it * 7).toByte() }).encode()

    /** A port where whatever is written comes back, as from another radio on the channel. */
    private class Loopback : KissLink {
        val fromRadio = PipedInputStream(16384)
        val toRadio = PipedOutputStream(fromRadio)
        override val input = fromRadio
        override val output = toRadio
        override fun close() {
            try { toRadio.close() } catch (ignored: Exception) { /* closed */ }
        }

        /** Bytes as if another station had sent them. */
        fun inject(bytes: ByteArray) {
            synchronized(toRadio) { toRadio.write(bytes) }
            toRadio.flush()
        }
    }

    private fun open(encoding: SerialEncoding, link: Loopback, diagnostics: MutableList<String>? = null): kotlin.Pair<SerialDataTransport, LinkedBlockingQueue<ByteArray>> {
        val opened = LinkedBlockingQueue<Boolean>()
        val t = SerialDataTransport(
            "radio", SerialDataProfile.DSTAR_FAST, encoding, pace = false,
            onStatus = { if (it.endsWith("connected")) opened.add(true) },
            onDiagnostic = diagnostics?.let { d -> { msg: String -> synchronized(d) { d.add(msg) } } },
        ) { link }
        val received = LinkedBlockingQueue<ByteArray>()
        t.start { received.add(it) }
        assertTrue(opened.poll(5, TimeUnit.SECONDS) == true)
        return t to received
    }

    @Test
    fun wrapAppendsTheFcsAndUnwrapChecksIt() {
        val wrapped = SerialDataTransport.wrap(frame)
        assertEquals(frame.size + 2, wrapped.size)
        assertArrayEquals(frame, SerialDataTransport.unwrap(wrapped))
        val damaged = wrapped.copyOf().also { it[20] = (it[20].toInt() xor 0x10).toByte() }
        assertNull(SerialDataTransport.unwrap(damaged))
        assertNull("lost byte", SerialDataTransport.unwrap(wrapped.copyOfRange(1, wrapped.size)))
        assertNull("too short", SerialDataTransport.unwrap(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun textLinesArePrintableAscii() {
        val line = SerialDataTransport.textLine(frame)
        val text = String(line, Charsets.US_ASCII)
        assertTrue(text.startsWith(SerialDataTransport.TEXT_PREFIX))
        assertTrue(text.endsWith("\r\n"))
        assertTrue(text.trimEnd().all { it.code in 0x20..0x7E })
    }

    @Test
    fun binaryFramesGoThroughAndDamagedOnesAreDropped() {
        val link = Loopback()
        val diagnostics = ArrayList<String>()
        val (t, received) = open(SerialEncoding.BINARY, link, diagnostics)
        try {
            assertTrue(t.connected)
            assertTrue(t.lastError, t.send(frame))
            assertArrayEquals(frame, received.poll(5, TimeUnit.SECONDS))

            val damaged = SerialDataTransport.wrap(frame).also { it[10] = (it[10].toInt() xor 1).toByte() }
            link.inject(Kiss.encode(damaged))
            // A good frame after the damaged one still arrives, so the damaged one was read and dropped.
            assertTrue(t.send(frame))
            assertArrayEquals(frame, received.poll(5, TimeUnit.SECONDS))
            assertNull(received.poll(200, TimeUnit.MILLISECONDS))
            assertEquals(1, t.badFrames)
            synchronized(diagnostics) {
                assertTrue(diagnostics.any { it.contains("damaged KISS frame") })
                assertTrue(diagnostics.any { it.contains("received") })
            }
        } finally {
            t.close()
        }
    }

    @Test
    fun textFramesGoThroughAndBothEncodingsAreAlwaysDecoded() {
        val link = Loopback()
        val (t, received) = open(SerialEncoding.TEXT, link)
        try {
            assertTrue(t.lastError, t.send(frame))
            assertArrayEquals(frame, received.poll(5, TimeUnit.SECONDS))
            // A station set to binary is still understood.
            link.inject(Kiss.encode(SerialDataTransport.wrap(frame)))
            assertArrayEquals(frame, received.poll(5, TimeUnit.SECONDS))
            // Unrelated text and a damaged text frame are dropped; the next good one arrives.
            val good = String(SerialDataTransport.textLine(frame), Charsets.US_ASCII)
            val broken = good.replaceRange(10, 11, if (good[10] == 'A') "B" else "A")
            link.inject("\$GPGGA,123519,4807.038,N\r\n$broken".toByteArray(Charsets.US_ASCII))
            link.inject(SerialDataTransport.textLine(frame))
            assertArrayEquals(frame, received.poll(5, TimeUnit.SECONDS))
            assertNull(received.poll(200, TimeUnit.MILLISECONDS))
            assertEquals(1, t.badFrames)
        } finally {
            t.close()
        }
    }

    @Test
    fun aClosedLinkRefusesToSend() {
        val t = SerialDataTransport("radio", SerialDataProfile.NXDN, pace = false) { error("no radio") }
        assertFalse(t.send(frame))
        assertTrue(t.lastError!!.contains("not connected"))
    }

    @Test
    fun pacingFollowsTheProfileOrTheOverride() {
        val slow = SerialDataTransport("radio", SerialDataProfile.DSTAR_SLOW) { error("no radio") }
        val fast = SerialDataTransport("radio", SerialDataProfile.DSTAR_FAST) { error("no radio") }
        assertTrue(slow.estimateAirtimeMs(200) > fast.estimateAirtimeMs(200))
        assertEquals(400L + (200 + SerialDataTransport.OVERHEAD_BYTES) * 8L * 1000L / 900L, slow.estimateAirtimeMs(200))
        val custom = SerialDataTransport("radio", throughputBps = 600, keyUpMs = 1000) { error("no radio") }
        assertEquals(1000L + (200 + SerialDataTransport.OVERHEAD_BYTES) * 8L * 1000L / 600L, custom.estimateAirtimeMs(200))
        val text = SerialDataTransport("radio", SerialDataProfile.DSTAR_SLOW, SerialEncoding.TEXT) { error("no radio") }
        assertTrue("text costs more air time", text.estimateAirtimeMs(200) > slow.estimateAirtimeMs(200))
    }
}
