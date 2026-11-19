// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ip

import java.util.Random

/** How Ethernet frames are carried in USB transfers. */
enum class UsbNetFraming(val label: String) {
    /** Remote NDIS: frames wrapped in PACKET_MSGs, possibly several per transfer. */
    RNDIS("RNDIS"),

    /** CDC-ECM: one bare Ethernet frame per transfer. */
    ECM("CDC-ECM"),
}

/**
 * Which Ethernet address IPv4 multicast datagrams (224.0.0.0/4) are sent to on a USB network link.
 * DMR radios treat 225.x.y.z as talkgroups; which of these they accept from the computer is not
 * known for certain, so it is a setting.
 */
enum class MulticastDelivery {
    /** The Ethernet broadcast address, as for 255.255.255.255. */
    BROADCAST,

    /** The standard IPv4 multicast MAC (01:00:5e:...), as a desktop operating system sends it. */
    MULTICAST_MAC,

    /** The radio's own MAC, as if the radio were the router for those addresses. */
    RADIO,
}

/**
 * IPv4 over a virtual Ethernet link to a radio, as APX radios offer on USB. One call to [feed]
 * must be exactly one USB transfer.
 *
 * Bring-up: with [useDhcp], ask the radio for addresses (DHCP); its answer gives this end's
 * address and the radio's (router, else server identifier). Without an answer after
 * [DHCP_TRIES] attempts, or without DHCP, the configured [configuredLocal] and [configuredRadio]
 * are used. Then the radio's MAC address is found with ARP. The link is up once it is known; all
 * traffic goes to the radio (it routes over the air), the limited broadcast to the Ethernet
 * broadcast address. Multicast goes where [multicastDelivery] says: MOTOTRBO radios treat
 * 225.x.y.z as talkgroup addresses. ARP requests for this end's address are answered. DHCP goes
 * out with TTL 1.
 */
class EthernetIpLink(
    private val framing: UsbNetFraming,
    private val mac: ByteArray,
    private val configuredLocal: Int,
    private val configuredRadio: Int,
    private val useDhcp: Boolean,
    private val output: (ByteArray) -> Unit,
    private val onPacket: (ByteArray) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    random: Random = Random(),
    private val multicastDelivery: MulticastDelivery = MulticastDelivery.BROADCAST,
) : IpPacketLink {
    private enum class Phase { DHCP_DISCOVER, DHCP_REQUEST, ARP, UP }

    @Volatile override var localAddress: Int = configuredLocal
        private set
    @Volatile override var peerAddress: Int = configuredRadio
        private set
    @Volatile override var failure: String? = null
        private set
    @Volatile override var up: Boolean = false
        private set

    /** True when the addresses came from the radio's DHCP server. */
    @Volatile var addressesFromDhcp: Boolean = false
        private set

    private var phase = if (useDhcp) Phase.DHCP_DISCOVER else Phase.ARP
    private var radioMac: ByteArray? = null
    private val xid = random.nextInt()
    private var offered = 0
    private var offerServer: Int? = null
    private var lastSendMs = 0L
    private var tries = 0
    private var startedMs = 0L
    private var ipId = random.nextInt(0x10000)

    @Synchronized
    override fun start() {
        startedMs = nowMs()
        sendForPhase()
    }

    @Synchronized
    override fun feed(data: ByteArray, offset: Int, length: Int) {
        val frames = when (framing) {
            UsbNetFraming.RNDIS -> Rndis.unwrap(data, offset, length)
            UsbNetFraming.ECM -> if (length >= Ethernet.HEADER_SIZE) listOf(data.copyOfRange(offset, offset + length)) else emptyList()
        }
        for (f in frames) handleFrame(f)
    }

    @Synchronized
    override fun tick() {
        if (failure != null || phase == Phase.UP) return
        val now = nowMs()
        if (now - startedMs > TIMEOUT_MS) {
            fail("the radio did not answer on its USB network link (no ARP reply from ${Ipv4.format(peerAddress)}); check the radio's IP address")
            return
        }
        if (now - lastSendMs < RETRY_MS) return
        if ((phase == Phase.DHCP_DISCOVER || phase == Phase.DHCP_REQUEST) && tries >= DHCP_TRIES) {
            // No DHCP server on this radio: use the configured addresses.
            phase = Phase.ARP
            tries = 0
        }
        sendForPhase()
    }

    override fun send(packet: ByteArray): Boolean {
        val dest = synchronized(this) { if (up) destinationMac(packet) else null } ?: return false
        output(wrap(Ethernet.build(dest, mac, Ethernet.TYPE_IPV4, packet)))
        return true
    }

    private fun destinationMac(packet: ByteArray): ByteArray? {
        if (packet.size < Ipv4.HEADER_SIZE) return null
        val dst = Ipv4.get32(packet, 16)
        // Limited broadcast goes to everyone on the link, multicast as configured, everything else
        // to the radio.
        if (dst == Ipv4.BROADCAST) return Ethernet.BROADCAST
        if ((dst ushr 28) != 0xE) return radioMac
        return when (multicastDelivery) {
            MulticastDelivery.BROADCAST -> Ethernet.BROADCAST
            MulticastDelivery.MULTICAST_MAC -> Ethernet.multicastMac(dst)
            MulticastDelivery.RADIO -> radioMac
        }
    }

    private fun sendForPhase() {
        lastSendMs = nowMs()
        tries++
        when (phase) {
            Phase.DHCP_DISCOVER -> sendDhcp(Dhcp.discover(mac, xid))
            Phase.DHCP_REQUEST -> sendDhcp(Dhcp.request(mac, xid, offered, offerServer))
            Phase.ARP -> output(wrap(Ethernet.build(Ethernet.BROADCAST, mac, Ethernet.TYPE_ARP, Arp.build(Arp.REQUEST, mac, localAddress, ByteArray(6), peerAddress))))
            Phase.UP -> {}
        }
    }

    private fun sendDhcp(payload: ByteArray) {
        val ip = Ipv4.buildUdp(0, Ipv4.BROADCAST, Dhcp.CLIENT_PORT, Dhcp.SERVER_PORT, payload, nextId(), ttl = 1)
        output(wrap(Ethernet.build(Ethernet.BROADCAST, mac, Ethernet.TYPE_IPV4, ip)))
    }

    private fun handleFrame(frame: ByteArray) {
        val eth = Ethernet.parse(frame) ?: return
        if (!eth.destination.contentEquals(mac) && !Ethernet.isGroup(eth.destination)) return
        if (eth.source.contentEquals(mac)) return
        when (eth.type) {
            Ethernet.TYPE_ARP -> handleArp(Arp.parse(eth.payload) ?: return)
            Ethernet.TYPE_IPV4 -> handleIp(eth)
        }
    }

    private fun handleArp(arp: ArpPacket) {
        if (arp.senderIp == peerAddress && arp.senderIp != 0) learnRadio(arp.senderMac)
        if (arp.operation == Arp.REQUEST && arp.targetIp == localAddress && localAddress != 0 && phase != Phase.DHCP_DISCOVER && phase != Phase.DHCP_REQUEST) {
            output(wrap(Ethernet.build(arp.senderMac, mac, Ethernet.TYPE_ARP, Arp.build(Arp.REPLY, mac, localAddress, arp.senderMac, arp.senderIp))))
        }
    }

    private fun handleIp(eth: EthernetFrame) {
        val ip = Ipv4.parsePacket(eth.payload) ?: return
        if (phase == Phase.DHCP_DISCOVER || phase == Phase.DHCP_REQUEST) {
            val udp = Ipv4.parseUdp(ip) ?: return
            if (udp.destinationPort != Dhcp.CLIENT_PORT || udp.sourcePort != Dhcp.SERVER_PORT) return
            handleDhcp(Dhcp.parse(udp.payload, xid, mac) ?: return, eth.source)
            return
        }
        if (ip.source == peerAddress) learnRadio(eth.source)
        if (up) onPacket(eth.payload)
    }

    private fun handleDhcp(reply: DhcpReply, serverMac: ByteArray) {
        when {
            phase == Phase.DHCP_DISCOVER && reply.type == Dhcp.OFFER && reply.yourAddress != 0 -> {
                offered = reply.yourAddress
                offerServer = reply.server
                phase = Phase.DHCP_REQUEST
                tries = 0
                sendForPhase()
            }
            phase == Phase.DHCP_REQUEST && reply.type == Dhcp.ACK && reply.yourAddress != 0 -> {
                localAddress = reply.yourAddress
                peerAddress = reply.router ?: reply.server ?: offerServer ?: configuredRadio
                addressesFromDhcp = true
                phase = Phase.ARP
                tries = 0
                // The server is very likely the radio itself; ARP still confirms it.
                if (reply.router == null || reply.router == reply.server) learnRadio(serverMac)
                if (phase != Phase.UP) sendForPhase()
            }
            phase == Phase.DHCP_REQUEST && reply.type == Dhcp.NAK -> {
                phase = Phase.DHCP_DISCOVER
                tries = 0
                sendForPhase()
            }
        }
    }

    private fun learnRadio(radio: ByteArray) {
        if (Ethernet.isGroup(radio)) return
        radioMac = radio
        if (phase == Phase.ARP) {
            phase = Phase.UP
            up = true
        }
    }

    private fun wrap(frame: ByteArray): ByteArray = when (framing) {
        UsbNetFraming.RNDIS -> Rndis.wrap(frame)
        UsbNetFraming.ECM -> frame
    }

    private fun nextId(): Int {
        ipId = (ipId + 1) and 0xFFFF
        return ipId
    }

    private fun fail(reason: String) {
        if (failure == null) failure = reason
        up = false
    }

    companion object {
        const val RETRY_MS = 2_000L
        const val DHCP_TRIES = 3
        const val TIMEOUT_MS = 45_000L
    }
}
