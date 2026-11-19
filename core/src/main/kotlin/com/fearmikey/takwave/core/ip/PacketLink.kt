// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ip

/** How IP packets are framed on the serial line to the radio. */
enum class IpFraming { PPP, SLIP }

/**
 * IP packets over one open serial connection. Raw bytes from the line go to [feed]; whole IP
 * packets come out of the `onPacket` callback the implementation was built with, and go in
 * through [send]. Implementations are safe to call from several threads.
 */
interface IpPacketLink {
    /** True once IP packets can be exchanged. */
    val up: Boolean

    /** This end's IP address (it can change while PPP negotiates). */
    val localAddress: Int

    /** The radio's address on the link, as configured or as negotiated. */
    val peerAddress: Int

    /** Set when the link has failed for good and must be reopened; a user-readable reason. */
    val failure: String?

    /** Begins any negotiation. Call once, after the line is open. */
    fun start()

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset)

    /** Retransmits and times out negotiation. Call about twice a second. */
    fun tick()

    /** Sends one IP packet. Returns false when the link is not up. */
    fun send(packet: ByteArray): Boolean

    companion object {
        fun create(
            framing: IpFraming,
            localAddress: Int,
            peerAddress: Int,
            output: (ByteArray) -> Unit,
            onPacket: (ByteArray) -> Unit,
            nowMs: () -> Long = System::currentTimeMillis,
        ): IpPacketLink = when (framing) {
            IpFraming.SLIP -> SlipLink(localAddress, peerAddress, output, onPacket)
            IpFraming.PPP -> PppLink(localAddress, peerAddress, output, onPacket, nowMs)
        }
    }
}

/** SLIP has no negotiation: both addresses are configured and the link is up at once. */
class SlipLink(
    override val localAddress: Int,
    override val peerAddress: Int,
    private val output: (ByteArray) -> Unit,
    onPacket: (ByteArray) -> Unit,
) : IpPacketLink {
    private val decoder = SlipDecoder(onPacket = onPacket)

    override val up: Boolean get() = true
    override val failure: String? get() = null

    override fun start() {}

    @Synchronized
    override fun feed(data: ByteArray, offset: Int, length: Int) = decoder.feed(data, offset, length)

    override fun tick() {}

    override fun send(packet: ByteArray): Boolean {
        output(Slip.encode(packet))
        return true
    }
}
