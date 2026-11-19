// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ip

import com.fearmikey.takwave.core.io.KissLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.util.Random
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A pretend radio data port. At [baud] it speaks [framing] properly; at any other rate it answers
 * every write with line noise, as a real UART at the wrong speed would. [echo] makes the cable
 * send everything written straight back, as some half-duplex adapters do.
 */
private class FakeRadioPort(
    private val baud: Int,
    private val framing: IpFraming?,
    private val radioAddress: Int,
    private val phoneAddress: Int,
    private val echo: Boolean = false,
    private val ignoreTtlOne: Boolean = false,
) {
    val opened = ArrayList<Int>()
    val radioSaw = ArrayList<ByteArray>()

    fun open(rate: Int): KissLink {
        opened.add(rate)
        val queue = LinkedBlockingQueue<ByteArray>()
        val noise = Random(rate.toLong())
        var closed = false
        val ip: IpPacketLink? = if (rate == baud && framing != null) {
            IpPacketLink.create(framing, radioAddress, phoneAddress, output = { queue.add(it) }, onPacket = { packet ->
                radioSaw.add(packet)
                val p = Ipv4.parsePacket(packet) ?: return@create
                if (ignoreTtlOne && (packet[8].toInt() and 0xFF) == 1) return@create
                if (p.destination == radioAddress) Ipv4.echoReply(p, radioAddress, 1)?.let { reply -> queue.add(Slip.encode(reply)) }
            })
        } else {
            null
        }
        var started = false
        return object : KissLink {
            override val input = object : InputStream() {
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    while (true) {
                        if (closed) return -1
                        val chunk = queue.poll(20, TimeUnit.MILLISECONDS) ?: continue
                        val n = minOf(len, chunk.size)
                        chunk.copyInto(b, off, 0, n)
                        if (n < chunk.size) queue.add(chunk.copyOfRange(n, chunk.size))
                        return n
                    }
                }
            }
            override val output = object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    val bytes = b.copyOfRange(off, off + len)
                    if (echo) queue.add(bytes)
                    if (ip == null) {
                        queue.add(ByteArray(len) { noise.nextInt().toByte() })
                        return
                    }
                    if (!started && framing == IpFraming.PPP) {
                        started = true
                        ip.start()
                    }
                    ip.feed(bytes, 0, bytes.size)
                }
            }
            override fun close() { closed = true }
        }
    }
}

class P25ProbeTest {
    private val phone = Ipv4.parse("192.168.128.2")!!
    private val radio = Ipv4.parse("192.168.128.1")!!
    private val bauds = listOf(9600, 19200, 38400, 57600, 115200)

    private fun probe(port: FakeRadioPort, order: List<Int> = bauds, addresses: List<Int> = listOf(radio)) =
        P25Probe(order, phone, addresses, listenMs = 300) { port.open(it) }

    @Test
    fun findsAPppRadioAtItsRate() {
        val port = FakeRadioPort(38400, IpFraming.PPP, radio, phone)
        val result = probe(port).run()
        assertEquals(P25ProbeResult(IpFraming.PPP, 38400, null), result)
        assertEquals(listOf(9600, 19200, 38400), port.opened)
    }

    @Test
    fun findsASlipRadioAndItsAddress() {
        val other = Ipv4.parse("10.20.30.1")!!
        val port = FakeRadioPort(19200, IpFraming.SLIP, other, phone)
        // The radio's address is not the default; the second candidate finds it.
        val result = probe(port, addresses = listOf(radio, other)).run()
        assertEquals(P25ProbeResult(IpFraming.SLIP, 19200, other), result)
    }

    @Test
    fun slipPingsAreSentWithTtlOne() {
        val port = FakeRadioPort(9600, IpFraming.SLIP, radio, phone)
        probe(port).run()
        // The PPP request arrives first as a junk SLIP packet, which a radio drops; then the ping.
        val ping = port.radioSaw.first { Ipv4.parsePacket(it) != null }
        assertEquals(1, ping[8].toInt())
        assertEquals(Ipv4.PROTO_ICMP, ping[9].toInt())
    }

    @Test
    fun theLastKnownRateIsTriedFirst() {
        val port = FakeRadioPort(115200, IpFraming.PPP, radio, phone)
        probe(port, order = listOf(115200) + bauds.dropLast(1)).run()
        assertEquals(listOf(115200), port.opened)
    }

    @Test
    fun aCableThatEchoesIsNotTakenForARadio() {
        val port = FakeRadioPort(9600, null, radio, phone, echo = true)
        try {
            probe(port, order = listOf(9600)).run()
            fail("an echo must not count as a radio")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("no answer"))
        }
    }

    @Test
    fun noiseAtEveryRateIsReportedAsNoAnswer() {
        val port = FakeRadioPort(9600, null, radio, phone)
        try {
            probe(port).run()
            fail("noise must not count as a radio")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("no answer"))
        }
        assertEquals(bauds, port.opened)
    }

    @Test
    fun aPortThatCannotOpenEndsTheProbeAtOnce() {
        var calls = 0
        val p = P25Probe(bauds, phone, listOf(radio), listenMs = 300) {
            calls++
            error("no USB serial port found")
        }
        try {
            p.run()
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("no USB serial port found", e.message)
        }
        assertEquals(1, calls)
    }

    @Test
    fun aSlipRadioThatSaysNothingUsefulStillCountsWithoutAnAddress() {
        // A radio whose stack drops TTL-1 pings silently but sends other traffic: framing known, address not.
        val port = FakeRadioPort(9600, IpFraming.SLIP, radio, phone, ignoreTtlOne = true)
        val p = P25Probe(listOf(9600), phone, listOf(radio), listenMs = 300) { rate ->
            val link = port.open(rate)
            // The radio announces itself (say, an ARS registration) as soon as the line opens.
            val announce = Slip.encode(Ipv4.buildUdp(radio, Ipv4.parse("192.168.128.2")!!, 4005, 4005, byteArrayOf(1), id = 3))
            object : KissLink by link {
                private var sent = false
                override val input = object : InputStream() {
                    override fun read(): Int = throw UnsupportedOperationException()
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (!sent) {
                            sent = true
                            announce.copyInto(b, off)
                            return announce.size
                        }
                        return link.input.read(b, off, len)
                    }
                }
            }
        }
        val result = p.run()
        assertEquals(IpFraming.SLIP, result.framing)
        assertNull(result.radioAddress)
    }
}
