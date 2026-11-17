// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.io

import com.fearmikey.takwave.core.ax25.Ax25Address
import com.fearmikey.takwave.core.ax25.Ax25Frame
import com.fearmikey.takwave.core.kiss.Kiss
import com.fearmikey.takwave.core.kiss.KissDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A one-way audio cable: what is written to the sink comes out of the source; silence otherwise. */
private class AudioCable : AudioSink, AudioSource {
    private val queue = LinkedBlockingQueue<Short>()
    @Volatile var aborted = false

    override fun write(samples: ShortArray, offset: Int, length: Int) {
        for (i in offset until offset + length) queue.add(samples[i])
    }

    override fun drain() {
        while (queue.isNotEmpty() && !aborted) Thread.sleep(1)
    }

    override fun abort() { aborted = true }

    override fun read(buffer: ShortArray, offset: Int, length: Int): Int {
        // Faster than real time when audio is waiting, a short pause when idle.
        if (queue.isEmpty()) Thread.sleep(2)
        for (i in 0 until length) buffer[offset + i] = queue.poll() ?: 0
        return length
    }
}

private class RecordingPtt : Ptt {
    val keys = AtomicInteger()
    val unkeys = AtomicInteger()
    @Volatile var keyed = false
    override fun key() { keyed = true; keys.incrementAndGet() }
    override fun unkey() { keyed = false; unkeys.incrementAndGet() }
}

class TransportTest {
    private fun frame(n: Int, size: Int = 40): ByteArray {
        val info = ByteArray(size)
        Random(n.toLong()).nextBytes(info)
        return Ax25Frame(Ax25Address("TAKWV"), Ax25Address("N0CALL", n % 16), info).encode()
    }

    private val fast = ModemConfig(txDelayMs = 100, slotTimeMs = 1, persistence = 255)

    @Test
    fun softModemsExchangeFramesOverAudio() {
        val aToB = AudioCable()
        val bToA = AudioCable()
        val pttA = RecordingPtt()
        val a = SoftModemTransport(bToA, aToB, pttA, fast)
        val b = SoftModemTransport(aToB, bToA, RecordingPtt(), fast)
        val atB = LinkedBlockingQueue<ByteArray>()
        val atA = LinkedBlockingQueue<ByteArray>()
        a.start { atA.add(it) }
        b.start { atB.add(it) }
        try {
            for (n in 0 until 5) {
                assertTrue(a.lastError, a.send(frame(n)))
                assertFalse("PTT still keyed after send", pttA.keyed)
                val got = atB.poll(10, TimeUnit.SECONDS)
                assertNotNull("frame $n not received", got)
                assertArrayEquals(frame(n), got)
            }
            assertEquals(5, pttA.keys.get())
            assertTrue(b.send(frame(99)))
            assertArrayEquals(frame(99), atA.poll(10, TimeUnit.SECONDS))
            // Carrier detect drops shortly after the trailing flags end.
            val deadline = System.currentTimeMillis() + 5_000
            while (a.state != RfState.IDLE && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(RfState.IDLE, a.state)
        } finally {
            a.close()
            b.close()
        }
        assertFalse(pttA.keyed)
        assertFalse("send after close must fail", a.send(frame(1)))
    }

    @Test
    fun pttIsReleasedWhenAudioFails() {
        val ptt = RecordingPtt()
        val failing = object : AudioSink {
            override fun write(samples: ShortArray, offset: Int, length: Int) = throw IllegalStateException("audio device lost")
            override fun drain() {}
            override fun abort() {}
        }
        val t = SoftModemTransport(AudioCable(), failing, ptt, fast)
        t.start { }
        try {
            assertFalse(t.send(frame(1)))
            assertEquals(1, ptt.keys.get())
            assertFalse(ptt.keyed)
            assertTrue(t.lastError!!.contains("audio device lost"))
        } finally {
            t.close()
        }
    }

    @Test
    fun watchdogForceUnkeysAStuckTransmitter() {
        val ptt = RecordingPtt()
        val release = CountDownLatch(1)
        val stuck = object : AudioSink {
            override fun write(samples: ShortArray, offset: Int, length: Int) {}
            // Simulates an audio track that never finishes playing.
            override fun drain() { release.await(20, TimeUnit.SECONDS) }
            override fun abort() { release.countDown() }
        }
        val t = SoftModemTransport(AudioCable(), stuck, ptt, fast.copy(txTimeoutMs = 700))
        t.start { }
        try {
            val started = System.nanoTime()
            assertFalse(t.send(frame(1)))
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("took $elapsedMs ms", elapsedMs in 600..5_000)
            assertFalse(ptt.keyed)
            assertTrue(t.lastError!!.contains("TX timeout"))
            assertEquals(RfState.IDLE, t.state)
        } finally {
            t.close()
        }
    }

    @Test
    fun overlongTransmissionIsRefusedBeforeKeying() {
        val ptt = RecordingPtt()
        val t = SoftModemTransport(AudioCable(), AudioCable(), ptt, fast.copy(txTimeoutMs = 500))
        t.start { }
        try {
            assertFalse(t.send(frame(1, size = 200))) // about 1.6 s of audio
            assertEquals(0, ptt.keys.get())
        } finally {
            t.close()
        }
    }

    @Test
    fun kissFramingRoundTrip() {
        val special = byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 0xDC.toByte(), 0xDD.toByte(), 0, 0xC0.toByte())
        val encoded = Kiss.encode(special)
        assertEquals(0xC0, encoded.first().toInt() and 0xFF)
        assertEquals(0xC0, encoded.last().toInt() and 0xFF)
        assertEquals(0, encoded[1].toInt())
        // No raw FEND inside the frame body.
        assertEquals(2, encoded.count { it == 0xC0.toByte() })

        val got = ArrayList<ByteArray>()
        val dec = KissDecoder { got.add(it) }
        val stream = encoded + Kiss.encode(frame(3)) + byteArrayOf(0xC0.toByte(), 0xC0.toByte()) +
            byteArrayOf(0xC0.toByte(), 0x06, 1, 0xC0.toByte()) + // a non-data command: ignored
            byteArrayOf(0xC0.toByte(), 0x00, 0xDB.toByte(), 0x01, 0xC0.toByte()) + // bad escape: dropped
            Kiss.encode(frame(4))
        for (b in stream) dec.feed(byteArrayOf(b), 0, 1)
        assertEquals(3, got.size)
        assertArrayEquals(special, got[0])
        assertArrayEquals(frame(3), got[1])
        assertArrayEquals(frame(4), got[2])

        val rnd = Random(5)
        val junk = ByteArray(100_000).also { rnd.nextBytes(it) }
        dec.feed(junk, 0, junk.size) // must not throw or grow without bound
    }

    @Test
    fun kissStreamTransportReconnectsAndReportsTheLink() {
        // What the TNC end of each link sees and sends; a link is handed out on the second attempt.
        val fromApp = java.io.PipedInputStream(4096)
        val toApp = java.io.PipedOutputStream()
        val attempts = AtomicInteger()
        val closed = AtomicInteger()
        val statuses = LinkedBlockingQueue<String>()
        val t = KissStreamTransport("Test TNC", reconnectDelayMs = 20, onStatus = { statuses.add(it) }) {
            if (attempts.incrementAndGet() == 1) error("radio is switched off")
            object : KissLink {
                override val input = java.io.PipedInputStream(toApp, 4096)
                override val output = java.io.PipedOutputStream(fromApp)
                override fun close() { closed.incrementAndGet(); input.close() }
            }
        }
        val received = LinkedBlockingQueue<ByteArray>()
        t.start { received.add(it) }
        try {
            assertEquals("Test TNC: radio is switched off", statuses.poll(5, TimeUnit.SECONDS))
            assertEquals("Test TNC: connected", statuses.poll(5, TimeUnit.SECONDS))
            assertTrue(t.connected)

            toApp.write(Kiss.encode(frame(2)))
            toApp.flush()
            assertArrayEquals(frame(2), received.poll(5, TimeUnit.SECONDS))

            assertTrue(t.lastError, t.send(frame(3)))
            assertTrue(t.lastAirtimeMs > 0)
            val got = LinkedBlockingQueue<ByteArray>()
            val dec = KissDecoder { got.add(it) }
            val buf = ByteArray(1024)
            while (got.isEmpty()) {
                val n = fromApp.read(buf)
                if (n < 0) break
                dec.feed(buf, 0, n)
            }
            assertArrayEquals(frame(3), got.poll())
        } finally {
            t.close()
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (t.connected && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse(t.connected)
        assertTrue(closed.get() >= 1)
        assertFalse("send after close must fail", t.send(frame(4)))
        assertTrue(t.lastError!!.contains("not connected"))
    }

    @Test
    fun kissTcpTransportTalksToATnc() {
        ServerSocket(0).use { server ->
            val t = KissTcpTransport("127.0.0.1", server.localPort, reconnectDelayMs = 50)
            val received = LinkedBlockingQueue<ByteArray>()
            t.start { received.add(it) }
            try {
                assertFalse(t.send(frame(1)) && !t.connected)
                val tnc = server.accept()
                val deadline = System.currentTimeMillis() + 5_000
                while (!t.connected && System.currentTimeMillis() < deadline) Thread.sleep(10)
                assertTrue(t.connected)

                // TNC -> TAKwave
                tnc.getOutputStream().write(Kiss.encode(frame(7)))
                tnc.getOutputStream().flush()
                assertArrayEquals(frame(7), received.poll(5, TimeUnit.SECONDS))

                // TAKwave -> TNC
                assertTrue(t.send(frame(8)))
                val fromApp = LinkedBlockingQueue<ByteArray>()
                val dec = KissDecoder { fromApp.add(it) }
                val buf = ByteArray(1024)
                tnc.soTimeout = 5_000
                while (fromApp.isEmpty()) {
                    val n = tnc.getInputStream().read(buf)
                    if (n < 0) break
                    dec.feed(buf, 0, n)
                }
                assertArrayEquals(frame(8), fromApp.poll())

                // The TNC goes away; the transport reconnects on its own.
                tnc.close()
                val tnc2 = server.accept()
                tnc2.getOutputStream().write(Kiss.encode(frame(9)))
                tnc2.getOutputStream().flush()
                assertArrayEquals(frame(9), received.poll(5, TimeUnit.SECONDS))
                tnc2.close()
            } finally {
                t.close()
            }
        }
    }
}
