// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.io

import com.fearmikey.takwave.core.ax25.Ax25Address
import com.fearmikey.takwave.core.ax25.Ax25Frame
import com.fearmikey.takwave.core.kiss.Kiss
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ByteQueueInputStreamTest {
    @Test
    fun readsChunksInOrderAcrossBoundaries() {
        val s = ByteQueueInputStream()
        s.offer(byteArrayOf(1, 2, 3))
        s.offer(byteArrayOf(4, 5))
        val buf = ByteArray(2)
        assertEquals(2, s.read(buf, 0, 2))
        assertArrayEquals(byteArrayOf(1, 2), buf)
        assertEquals(3, s.available())
        assertEquals(2, s.read(buf, 0, 2))
        assertArrayEquals(byteArrayOf(3, 4), buf)
        assertEquals(5, s.read())
        assertEquals(0, s.available())
    }

    @Test
    fun offeredArrayIsCopied() {
        val s = ByteQueueInputStream()
        val data = byteArrayOf(7, 8)
        s.offer(data)
        data[0] = 0
        assertEquals(7, s.read())
    }

    @Test
    fun endDrainsQueueThenReturnsMinusOne() {
        val s = ByteQueueInputStream()
        s.offer(byteArrayOf(9))
        s.end()
        s.offer(byteArrayOf(10)) // Ignored after the end.
        assertEquals(9, s.read())
        assertEquals(-1, s.read())
    }

    @Test
    fun failDrainsQueueThenThrows() {
        val s = ByteQueueInputStream()
        s.offer(byteArrayOf(1))
        s.fail("link lost")
        assertEquals(1, s.read())
        try {
            s.read()
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("link lost", e.message)
        }
    }

    @Test
    fun closeDiscardsQueue() {
        val s = ByteQueueInputStream()
        s.offer(byteArrayOf(1, 2))
        s.close()
        assertEquals(-1, s.read())
    }

    @Test
    fun dropsBeyondLimit() {
        val s = ByteQueueInputStream(maxQueuedBytes = 4)
        s.offer(byteArrayOf(1, 2, 3))
        s.offer(byteArrayOf(4, 5))
        assertEquals(2L, s.dropped)
        assertEquals(3, s.available())
    }

    @Test
    fun blockedReaderWakesOnDataAndOnEnd() {
        val s = ByteQueueInputStream()
        val got = LinkedBlockingQueue<Int>()
        val reader = Thread {
            got.put(s.read())
            got.put(s.read())
        }
        reader.start()
        Thread.sleep(50)
        s.offer(byteArrayOf(42))
        assertEquals(42, got.poll(2, TimeUnit.SECONDS))
        s.end()
        assertEquals(-1, got.poll(2, TimeUnit.SECONDS))
        reader.join(2000)
    }

    /** What a BLE TNC does: KISS bytes in notification-sized pieces, read back as whole frames by the transport. */
    @Test
    fun kissTransportReadsFramesSplitIntoNotifications() {
        val frame = Ax25Frame(Ax25Address("TAKWV"), Ax25Address("N0CALL", 1), ByteArray(200) { it.toByte() }).encode()
        val incoming = ByteQueueInputStream()
        val written = ByteArrayOutputStream()
        val link = object : KissLink {
            override val input = incoming
            override val output = written
            override fun close() = incoming.end()
        }
        val frames = LinkedBlockingQueue<ByteArray>()
        val transport = KissStreamTransport("BLE TNC", reconnectDelayMs = 60_000) { link }
        transport.start { frames.put(it) }
        for (piece in BleKiss.split(Kiss.encode(frame), BleKiss.payloadSize(BleKiss.DEFAULT_MTU))) incoming.offer(piece)
        assertArrayEquals(frame, frames.poll(2, TimeUnit.SECONDS))
        transport.close()
    }
}
