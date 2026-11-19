// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ip

import com.fearmikey.takwave.core.ax25.Ax25Address
import com.fearmikey.takwave.core.ax25.Ax25Frame
import com.fearmikey.takwave.core.io.KissLink
import com.fearmikey.takwave.core.io.P25DataConfig
import com.fearmikey.takwave.core.io.P25DataTransport
import com.fearmikey.takwave.core.io.P25Line
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.Random
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The radio side of an APX-style USB network link: answers DHCP (if [dhcpServer]), ARP for its
 * own address, and records the UDP datagrams it gets. [send] delivers one transfer to the host.
 */
private class FakeApx(
    private val framing: UsbNetFraming,
    val radioIp: Int,
    private val hostIp: Int,
    private val dhcpServer: Boolean,
    private val send: (ByteArray) -> Unit,
) {
    val radioMac = byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55)
    val udp = LinkedBlockingQueue<UdpDatagram>()
    val udpDestMacs = LinkedBlockingQueue<ByteArray>()
    var dhcpTtl = -1
    var hostMac: ByteArray? = null

    fun fromHost(transfer: ByteArray) {
        val frames = when (framing) {
            UsbNetFraming.RNDIS -> Rndis.unwrap(transfer)
            UsbNetFraming.ECM -> listOf(transfer)
        }
        for (f in frames) handle(Ethernet.parse(f)!!)
    }

    private fun handle(eth: EthernetFrame) {
        when (eth.type) {
            Ethernet.TYPE_ARP -> {
                val arp = Arp.parse(eth.payload)!!
                if (arp.senderIp != 0) hostMac = arp.senderMac
                if (arp.operation == Arp.REQUEST && arp.targetIp == radioIp) {
                    out(arp.senderMac, Ethernet.TYPE_ARP, Arp.build(Arp.REPLY, radioMac, radioIp, arp.senderMac, arp.senderIp))
                }
            }
            Ethernet.TYPE_IPV4 -> {
                val ip = Ipv4.parsePacket(eth.payload)!!
                val d = Ipv4.parseUdp(ip) ?: return
                if (d.destinationPort == Dhcp.SERVER_PORT) {
                    dhcpTtl = eth.payload[8].toInt()
                    if (dhcpServer) answerDhcp(d.payload, eth.source)
                    return
                }
                udp.add(d)
                udpDestMacs.add(eth.destination)
            }
        }
    }

    private fun answerDhcp(request: ByteArray, mac: ByteArray) {
        val xid = Ipv4.get32(request, 4)
        // Message type is the first option after the magic cookie in TAKwave's messages.
        val type = request[242].toInt()
        val replyType = if (type == Dhcp.DISCOVER) Dhcp.OFFER else Dhcp.ACK
        val p = ByteArray(236)
        p[0] = 2
        p[1] = 1
        p[2] = 6
        Ipv4.put32(p, 4, xid)
        Ipv4.put32(p, 16, hostIp)
        mac.copyInto(p, 28)
        val opts = byteArrayOf(99, 130.toByte(), 83, 99, 53, 1, replyType.toByte(), 54, 4) + int32(radioIp) +
            byteArrayOf(1, 4) + int32(0xFFFFFF00.toInt()) + byteArrayOf(0xFF.toByte())
        val ip = Ipv4.buildUdp(radioIp, Ipv4.BROADCAST, Dhcp.SERVER_PORT, Dhcp.CLIENT_PORT, p + opts, id = 5)
        out(Ethernet.BROADCAST, Ethernet.TYPE_IPV4, ip)
    }

    /** The radio sends a datagram to the host, ARPing for it first as a real stack would. */
    fun sendUdp(payload: ByteArray, port: Int) {
        val mac = hostMac ?: error("radio does not know the host's MAC yet")
        out(mac, Ethernet.TYPE_IPV4, Ipv4.buildUdp(Ipv4.parse("192.168.128.20")!!, hostIp, port, port, payload, id = 9))
    }

    fun arpForHost() = out(Ethernet.BROADCAST, Ethernet.TYPE_ARP, Arp.build(Arp.REQUEST, radioMac, radioIp, ByteArray(6), hostIp))

    private fun out(dst: ByteArray, type: Int, payload: ByteArray) {
        val frame = Ethernet.build(dst, radioMac, type, payload)
        send(if (framing == UsbNetFraming.RNDIS) Rndis.wrap(frame) else frame)
    }

    private fun int32(v: Int) = ByteArray(4).also { Ipv4.put32(it, 0, v) }
}

class UsbNetTest {
    private val hostMac = byteArrayOf(0x02, 0x66, 0x77, 0x00, 0x00, 0x01)
    private val radio = Ipv4.parse("192.168.128.1")!!
    private val phone = Ipv4.parse("192.168.128.2")!!

    @Test
    fun rndisPacketsRoundTripAndBatchesSplit() {
        val a = ByteArray(60) { it.toByte() }
        val b = ByteArray(1514) { (it * 3).toByte() }
        val transfer = Rndis.wrap(a) + Rndis.wrap(b) + byteArrayOf(0) // trailing pad byte
        val frames = Rndis.unwrap(transfer)
        assertEquals(2, frames.size)
        assertArrayEquals(a, frames[0])
        assertArrayEquals(b, frames[1])
        // A corrupt length ends the transfer instead of reading past it.
        val bad = Rndis.wrap(a).also { Rndis.le32(it, 4, 10_000) }
        assertTrue(Rndis.unwrap(bad).isEmpty())
    }

    /** The device side of the RNDIS control channel, with a keep-alive thrown in. */
    private class FakeRndisControl(private val mac: ByteArray, private val refuse: Boolean = false) : RndisControl {
        val pending = ArrayDeque<ByteArray>()
        val fromHost = ArrayList<ByteArray>()

        override fun send(message: ByteArray) {
            fromHost.add(message)
            val type = Rndis.le32(message, 0)
            val id = Rndis.le32(message, 8)
            when (type) {
                Rndis.INITIALIZE_MSG -> {
                    val keepAlive = ByteArray(12).also { Rndis.le32(it, 0, Rndis.KEEPALIVE_MSG); Rndis.le32(it, 4, 12); Rndis.le32(it, 8, 77) }
                    pending.add(keepAlive)
                    val r = ByteArray(52)
                    Rndis.le32(r, 0, Rndis.INITIALIZE_CMPLT)
                    Rndis.le32(r, 4, 52)
                    Rndis.le32(r, 8, id)
                    Rndis.le32(r, 12, if (refuse) 0xC0000001.toInt() else 0)
                    Rndis.le32(r, 36, 1600)
                    pending.add(r)
                }
                Rndis.QUERY_MSG -> {
                    val r = ByteArray(24 + 6)
                    Rndis.le32(r, 0, Rndis.QUERY_CMPLT)
                    Rndis.le32(r, 4, r.size)
                    Rndis.le32(r, 8, id)
                    Rndis.le32(r, 16, 6)
                    Rndis.le32(r, 20, 16)
                    mac.copyInto(r, 24)
                    pending.add(r)
                }
                Rndis.SET_MSG -> {
                    val r = ByteArray(16)
                    Rndis.le32(r, 0, Rndis.SET_CMPLT)
                    Rndis.le32(r, 4, 16)
                    Rndis.le32(r, 8, id)
                    pending.add(r)
                }
            }
        }

        override fun receive(timeoutMs: Long): ByteArray? = pending.poll()
    }

    @Test
    fun rndisInitializesReadsTheMacAndAnswersKeepAlives() {
        val control = FakeRndisControl(hostMac)
        val device = Rndis.initialize(control)
        assertArrayEquals(hostMac, device.mac)
        assertEquals(1600, device.maxTransferSize)
        val types = control.fromHost.map { Rndis.le32(it, 0) }
        assertEquals(listOf(Rndis.INITIALIZE_MSG, Rndis.KEEPALIVE_CMPLT, Rndis.QUERY_MSG, Rndis.SET_MSG), types)
        val set = control.fromHost.last()
        assertEquals(Rndis.OID_GEN_CURRENT_PACKET_FILTER, Rndis.le32(set, 12))
        assertEquals(Rndis.PACKET_FILTER, Rndis.le32(set, 28))
    }

    @Test
    fun rndisRefusalIsReported() {
        try {
            Rndis.initialize(FakeRndisControl(hostMac, refuse = true))
            fail()
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("refused"))
        }
    }

    @Test
    fun dhcpRepliesAreCheckedAgainstTheTransaction() {
        val discover = Dhcp.discover(hostMac, 0x1234)
        assertEquals(1, discover[0].toInt())
        assertEquals(Dhcp.DISCOVER, discover[242].toInt())
        val reply = discover.copyOf().also { it[0] = 2; it[242] = Dhcp.OFFER.toByte(); Ipv4.put32(it, 16, phone) }
        val parsed = Dhcp.parse(reply, 0x1234, hostMac)!!
        assertEquals(Dhcp.OFFER, parsed.type)
        assertEquals(phone, parsed.yourAddress)
        assertNull("other transaction", Dhcp.parse(reply, 0x9999, hostMac))
        assertNull("other client", Dhcp.parse(reply, 0x1234, ByteArray(6)))
    }

    /** Host and radio joined by queues, pumped without re-entering either side. */
    private class Pair(framing: UsbNetFraming, dhcp: Boolean, radioIp: Int, hostIp: Int, configuredLocal: Int, configuredRadio: Int, mac: ByteArray, delivery: MulticastDelivery = MulticastDelivery.BROADCAST) {
        val toHost = ArrayDeque<ByteArray>()
        val toRadio = ArrayDeque<ByteArray>()
        val hostGot = ArrayList<ByteArray>()
        var now = 0L
        val apx = FakeApx(framing, radioIp, hostIp, dhcp) { toHost.add(it) }
        val host = EthernetIpLink(framing, mac, configuredLocal, configuredRadio, useDhcp = true, output = { toRadio.add(it) }, onPacket = { hostGot.add(it) }, nowMs = { now }, random = Random(1), multicastDelivery = delivery)

        fun pump() {
            var guard = 0
            while ((toHost.isNotEmpty() || toRadio.isNotEmpty()) && guard++ < 1000) {
                toRadio.poll()?.let { apx.fromHost(it) }
                toHost.poll()?.let { host.feed(it) }
            }
        }
    }

    @Test
    fun addressesComeFromTheRadiosDhcpServer() {
        for (framing in UsbNetFraming.entries) {
            val radioIp = Ipv4.parse("10.9.8.1")!!
            val hostIp = Ipv4.parse("10.9.8.7")!!
            val p = Pair(framing, dhcp = true, radioIp, hostIp, configuredLocal = phone, configuredRadio = radio, mac = hostMac)
            p.host.start()
            p.pump()
            assertTrue("$framing: link up", p.host.up)
            assertTrue(p.host.addressesFromDhcp)
            assertEquals(hostIp, p.host.localAddress)
            assertEquals(radioIp, p.host.peerAddress)
            assertEquals("DHCP must not be routable", 1, p.apx.dhcpTtl)

            // Broadcast UDP goes to the Ethernet broadcast address, unicast to the radio's MAC.
            assertTrue(p.host.send(Ipv4.buildUdp(hostIp, Ipv4.BROADCAST, 1, 2, byteArrayOf(9), id = 1)))
            assertTrue(p.host.send(Ipv4.buildUdp(hostIp, Ipv4.parse("10.9.8.20")!!, 1, 2, byteArrayOf(8), id = 2)))
            p.pump()
            assertArrayEquals(Ethernet.BROADCAST, p.apx.udpDestMacs.poll())
            assertArrayEquals(p.apx.radioMac, p.apx.udpDestMacs.poll())

            // The radio ARPs for the host and then sends to it.
            p.apx.arpForHost()
            p.pump()
            assertArrayEquals(hostMac, p.apx.hostMac)
            p.apx.sendUdp(byteArrayOf(1, 2, 3), 50125)
            p.pump()
            val got = Ipv4.parseUdp(Ipv4.parsePacket(p.hostGot.single())!!)!!
            assertArrayEquals(byteArrayOf(1, 2, 3), got.payload)
        }
    }

    @Test
    fun multicastGoesWhereTheDeliverySettingSays() {
        val radioIp = Ipv4.parse(DmrIp.DEFAULT_RADIO_ADDRESS)!!
        val hostIp = Ipv4.parse(DmrIp.DEFAULT_LOCAL_ADDRESS)!!
        val talkgroup = DmrIp.group(1)
        val other = DmrIp.individual(1234)
        for (delivery in MulticastDelivery.entries) {
            val p = Pair(UsbNetFraming.RNDIS, dhcp = true, radioIp, hostIp, configuredLocal = phone, configuredRadio = radio, mac = hostMac, delivery = delivery)
            p.host.start()
            p.pump()
            assertTrue(p.host.up)
            assertTrue(p.host.send(Ipv4.buildUdp(hostIp, talkgroup, 1, 2, byteArrayOf(1), id = 1)))
            assertTrue(p.host.send(Ipv4.buildUdp(hostIp, other, 1, 2, byteArrayOf(2), id = 2)))
            assertTrue(p.host.send(Ipv4.buildUdp(hostIp, Ipv4.BROADCAST, 1, 2, byteArrayOf(3), id = 3)))
            p.pump()
            val groupMac = p.apx.udpDestMacs.poll()
            val expected = when (delivery) {
                MulticastDelivery.BROADCAST -> Ethernet.BROADCAST
                MulticastDelivery.MULTICAST_MAC -> byteArrayOf(0x01, 0x00, 0x5E, 0x00, 0x00, 0x01)
                MulticastDelivery.RADIO -> p.apx.radioMac
            }
            assertArrayEquals("$delivery", expected, groupMac)
            assertArrayEquals("unicast always to the radio", p.apx.radioMac, p.apx.udpDestMacs.poll())
            assertArrayEquals("limited broadcast always to everyone", Ethernet.BROADCAST, p.apx.udpDestMacs.poll())
            assertEquals(talkgroup, p.apx.udp.poll()!!.destination)
        }
    }

    @Test
    fun withoutDhcpTheConfiguredAddressesAreUsed() {
        val p = Pair(UsbNetFraming.RNDIS, dhcp = false, radio, phone, configuredLocal = phone, configuredRadio = radio, mac = hostMac)
        p.host.start()
        p.pump()
        assertFalse(p.host.up)
        repeat(EthernetIpLink.DHCP_TRIES + 1) {
            p.now += EthernetIpLink.RETRY_MS
            p.host.tick()
            p.pump()
        }
        assertTrue(p.host.up)
        assertFalse(p.host.addressesFromDhcp)
        assertEquals(phone, p.host.localAddress)
        assertEquals(radio, p.host.peerAddress)
    }

    @Test
    fun aRadioThatNeverAnswersFails() {
        val sent = ArrayList<ByteArray>()
        val link = EthernetIpLink(UsbNetFraming.ECM, hostMac, phone, radio, useDhcp = false, output = { sent.add(it) }, onPacket = {}, nowMs = { now }, random = Random(2))
        link.start()
        while (link.failure == null && now < 120_000) {
            now += 1_000
            link.tick()
        }
        assertNotNull(link.failure)
        assertFalse(link.up)
        assertTrue(sent.size > 3)
    }

    private var now = 0L

    @Test
    fun theTransportCarriesFramesOverRndis() {
        // Each read returns one transfer, as the Android side guarantees.
        val toHost = LinkedBlockingQueue<ByteArray>()
        val apx = FakeApx(UsbNetFraming.RNDIS, radio, phone, dhcpServer = true) { toHost.add(it) }
        var closed = false
        val link = object : KissLink {
            override val input = object : InputStream() {
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    while (true) {
                        if (closed) return -1
                        val t = toHost.poll(20, TimeUnit.MILLISECONDS) ?: continue
                        check(t.size <= len) { "transfer larger than the read buffer" }
                        t.copyInto(b, off)
                        return t.size
                    }
                }
            }
            override val output = object : OutputStream() {
                override fun write(b: Int) = throw UnsupportedOperationException()
                override fun write(b: ByteArray, off: Int, len: Int) = apx.fromHost(b.copyOfRange(off, off + len))
            }
            override fun close() { closed = true }
        }
        val up = LinkedBlockingQueue<kotlin.Pair<Int, Int>>()
        val ignored = LinkedBlockingQueue<String>()
        val t = P25DataTransport("APX", P25DataConfig(listOf(Ipv4.BROADCAST)), pace = false, onLinkUp = { l, r -> up.add(l to r) }, onIgnored = { ignored.add(it) }) {
            P25Line.usbNetwork(link, UsbNetFraming.RNDIS, hostMac, Ipv4.parse("10.0.0.2")!!, Ipv4.parse("10.0.0.1")!!, useDhcp = true, maxTransferSize = Rndis.HOST_MAX_TRANSFER)
        }
        val received = LinkedBlockingQueue<ByteArray>()
        t.start { received.add(it) }
        try {
            assertEquals(phone to radio, up.poll(10, TimeUnit.SECONDS))
            val frame = Ax25Frame(Ax25Address("TAKWV"), Ax25Address("N0CALL", 1), ByteArray(80) { it.toByte() }).encode()
            assertTrue(t.lastError, t.send(frame))
            val d = apx.udp.poll(5, TimeUnit.SECONDS)!!
            assertEquals(P25DataConfig.DEFAULT_PORT, d.destinationPort)
            assertArrayEquals(frame, d.payload)

            apx.arpForHost()
            val deadline = System.currentTimeMillis() + 5_000
            while (apx.hostMac == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
            apx.sendUdp(frame, P25DataConfig.DEFAULT_PORT)
            assertArrayEquals(frame, received.poll(5, TimeUnit.SECONDS))

            // The radio's own traffic on another port is not delivered, only reported.
            apx.sendUdp(byteArrayOf(0x0a, 0x0b), 4007)
            val note = ignored.poll(5, TimeUnit.SECONDS)!!
            assertTrue(note, note.contains(":4007") && note.contains("0a 0b"))
            assertNull(received.poll(200, TimeUnit.MILLISECONDS))
        } finally {
            t.close()
        }
    }
}
