// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.io

import com.fearmikey.takwave.core.ax25.Ax25Address
import com.fearmikey.takwave.core.ax25.Ax25Frame
import com.fearmikey.takwave.core.ip.IpFraming
import com.fearmikey.takwave.core.ip.IpPacketLink
import com.fearmikey.takwave.core.ip.Ipv4
import com.fearmikey.takwave.core.ip.buildIcmpError
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * TAKwave against a pretend radio on the other end of a serial line. The radio side uses the
 * same SLIP / PPP code with the roles swapped, which is enough to check the transport's
 * plumbing; the codecs themselves are tested in IpTest.
 */
class P25DataTransportTest {
    private val phone = Ipv4.parse("192.168.128.2")!!
    private val radio = Ipv4.parse("192.168.128.1")!!
    private val peerRadio = Ipv4.parse("192.168.128.20")!!

    private fun frame(n: Int) = Ax25Frame(Ax25Address("TAKWV"), Ax25Address("N0CALL", n), ByteArray(60) { (it + n).toByte() }).encode()

    private fun roundTrip(framing: IpFraming) {
        val fromApp = PipedInputStream(1 shl 16)
        val toApp = PipedOutputStream()
        val appInput = PipedInputStream(toApp, 1 shl 16)
        val appOutput = PipedOutputStream(fromApp)
        val statuses = LinkedBlockingQueue<String>()
        val config = P25DataConfig(listOf(Ipv4.BROADCAST, peerRadio))
        val linkUp = LinkedBlockingQueue<Pair<Int, Int>>()
        val undelivered = LinkedBlockingQueue<String>()
        val ignored = LinkedBlockingQueue<String>()
        val t = P25DataTransport(
            "Test radio", config, reconnectDelayMs = 50, onStatus = { statuses.add(it) }, pace = false,
            onLinkUp = { local, r -> linkUp.add(local to r) },
            onIgnored = { ignored.add(it) },
            onUndelivered = { undelivered.add(it) },
        ) {
            val link = object : KissLink {
                override val input = appInput
                override val output = appOutput
                override fun close() { appInput.close() }
            }
            P25Line.serial(link, framing, phone, radio)
        }

        // The radio: decodes what the app sends and can inject datagrams of its own.
        val atRadio = LinkedBlockingQueue<ByteArray>()
        val radioWrite = Any()
        val radioLink = IpPacketLink.create(
            framing, radio, phone,
            output = { synchronized(radioWrite) { toApp.write(it); toApp.flush() } },
            onPacket = { atRadio.add(it) },
        )
        val radioReader = Thread {
            val buf = ByteArray(1024)
            try {
                while (true) {
                    val n = fromApp.read(buf)
                    if (n < 0) break
                    radioLink.feed(buf, 0, n)
                }
            } catch (e: Exception) {
                // Pipe closed at the end of the test.
            }
        }.apply { isDaemon = true; start() }

        val received = LinkedBlockingQueue<ByteArray>()
        t.start { received.add(it) }
        radioLink.start()
        try {
            val deadline = System.currentTimeMillis() + 10_000
            while (!t.connected && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue("link did not come up: $statuses", t.connected)
            assertEquals(phone to radio, linkUp.poll(5, TimeUnit.SECONDS))

            // App -> radio: one datagram per destination, frame as the UDP payload.
            assertTrue(t.lastError, t.send(frame(3)))
            assertTrue(t.lastAirtimeMs > 0)
            val destinations = ArrayList<Int>()
            repeat(2) {
                val ip = Ipv4.parsePacket(atRadio.poll(5, TimeUnit.SECONDS)!!)!!
                val udp = Ipv4.parseUdp(ip)!!
                assertEquals(phone, udp.source)
                assertEquals(P25DataConfig.DEFAULT_PORT, udp.destinationPort)
                assertArrayEquals(frame(3), udp.payload)
                destinations.add(udp.destination)
            }
            assertEquals(listOf(Ipv4.BROADCAST, peerRadio), destinations)

            // Radio -> app: a datagram for TAKwave's port is a frame; another port is ignored.
            radioLink.send(Ipv4.buildUdp(peerRadio, phone, 4007, 4007, frame(5), id = 1))
            radioLink.send(Ipv4.buildUdp(peerRadio, Ipv4.BROADCAST, 7, P25DataConfig.DEFAULT_PORT, frame(6), id = 2))
            assertArrayEquals(frame(6), received.poll(5, TimeUnit.SECONDS))
            assertTrue(received.isEmpty())

            // Radio -> app: an ICMP error about one of the app's frames is reported, whoever sends
            // it; one about a datagram on another port is not the app's and is only logged.
            val port = P25DataConfig.DEFAULT_PORT
            val toAll = Ipv4.buildUdp(phone, Ipv4.BROADCAST, port, port, frame(3), id = 7)
            val toPeer = Ipv4.buildUdp(phone, peerRadio, port, port, frame(3), id = 8)
            val notOurs = Ipv4.buildUdp(phone, peerRadio, 4007, 4007, frame(3), id = 9)
            radioLink.send(buildIcmpError(radio, phone, 3, 0, toAll))
            assertEquals(
                "Test radio: the radio reports that a packet to 255.255.255.255 was not sent (network unreachable)",
                undelivered.poll(5, TimeUnit.SECONDS),
            )
            radioLink.send(buildIcmpError(peerRadio, phone, 3, 3, toPeer))
            assertEquals(
                "Test radio: 192.168.128.20 reports that a packet to 192.168.128.20 was not delivered (port unreachable)",
                undelivered.poll(5, TimeUnit.SECONDS),
            )
            radioLink.send(buildIcmpError(radio, phone, 3, 1, notOurs))
            radioLink.send(buildIcmpError(radio, phone, 11, 0, toAll))
            assertEquals(
                "Test radio: the radio reports that a packet to 255.255.255.255 was not sent (time to live exceeded)",
                undelivered.poll(5, TimeUnit.SECONDS),
            )
            assertTrue(undelivered.isEmpty())
            assertTrue(ignored.toString(), ignored.any { it.contains("ICMP type 3 code 1 from 192.168.128.1") })
        } finally {
            t.close()
            fromApp.close()
            radioReader.interrupt()
        }
        assertFalse(t.connected)
        assertFalse("send after close must fail", t.send(frame(4)))
    }

    @Test
    fun framesTravelOverSlip() = roundTrip(IpFraming.SLIP)

    @Test
    fun framesTravelOverPpp() = roundTrip(IpFraming.PPP)

    @Test
    fun sendIsRefusedAndRetriedWhileTheRadioIsUnreachable() {
        val statuses = LinkedBlockingQueue<String>()
        var attempts = 0
        val t = P25DataTransport(
            "Test radio",
            P25DataConfig(listOf(Ipv4.BROADCAST)),
            reconnectDelayMs = 20,
            onStatus = { statuses.add(it) },
        ) {
            attempts++
            error("no USB serial adapter found")
        }
        t.start { }
        try {
            assertEquals("Test radio: no USB serial adapter found", statuses.poll(5, TimeUnit.SECONDS))
            assertFalse(t.send(frame(1)))
            assertTrue(t.lastError!!.contains("not connected"))
            val deadline = System.currentTimeMillis() + 5_000
            while (attempts < 3 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue(attempts >= 3)
        } finally {
            t.close()
        }
    }

    @Test
    fun airtimeEstimateCoversHeadersAndKeyUp() {
        val t = P25DataTransport("x", P25DataConfig(listOf(Ipv4.BROADCAST), throughputBps = 4000, keyUpMs = 300)) {
            error("unused")
        }
        // 72 bytes of frame + 28 of headers = 800 bits = 200 ms at 4 kbit/s.
        assertEquals(500L, t.estimateAirtimeMs(72))
    }
}
