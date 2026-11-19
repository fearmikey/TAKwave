// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ip

/**
 * The control channel of an RNDIS device: on USB, SEND_ENCAPSULATED_COMMAND and
 * GET_ENCAPSULATED_RESPONSE requests on endpoint 0. Implemented by the Android side.
 */
interface RndisControl {
    fun send(message: ByteArray)

    /** The next message from the device, or null when none arrived within [timeoutMs]. */
    fun receive(timeoutMs: Long): ByteArray?
}

/** What [Rndis.initialize] learned: the MAC address for this end, and the largest transfer the device sends. */
class RndisDevice(val mac: ByteArray, val maxTransferSize: Int)

/**
 * Remote NDIS, the USB network protocol that Motorola APX radios (among many devices) use for
 * their data link to a computer. Only the host side, and only what a single point-to-point link
 * needs: initialize, read the MAC address, set the packet filter, answer keep-alives, wrap and
 * unwrap Ethernet frames. All fields are little-endian.
 */
object Rndis {
    const val PACKET_MSG = 0x00000001
    const val INITIALIZE_MSG = 0x00000002
    const val HALT_MSG = 0x00000003
    const val QUERY_MSG = 0x00000004
    const val SET_MSG = 0x00000005
    const val INDICATE_STATUS_MSG = 0x00000007
    const val KEEPALIVE_MSG = 0x00000008
    const val INITIALIZE_CMPLT = 0x80000002.toInt()
    const val QUERY_CMPLT = 0x80000004.toInt()
    const val SET_CMPLT = 0x80000005.toInt()
    const val KEEPALIVE_CMPLT = 0x80000008.toInt()

    const val OID_802_3_PERMANENT_ADDRESS = 0x01010101
    const val OID_GEN_CURRENT_PACKET_FILTER = 0x0001010E

    /** Directed, all-multicast and broadcast frames. */
    const val PACKET_FILTER = 0x0000000D

    const val STATUS_SUCCESS = 0
    private const val PACKET_HEADER = 44

    /** The receive transfer size TAKwave asks for; frames are small, so this is generous. */
    const val HOST_MAX_TRANSFER = 16_384

    /**
     * Brings the device up: INITIALIZE, query the MAC address, set the packet filter. Throws
     * [IllegalStateException] with a readable reason when the device refuses or does not answer.
     */
    fun initialize(control: RndisControl, timeoutMs: Long = 3_000): RndisDevice {
        var id = 1
        val init = ByteArray(24)
        le32(init, 0, INITIALIZE_MSG)
        le32(init, 4, init.size)
        le32(init, 8, id)
        le32(init, 12, 1) // Major version
        le32(init, 16, 0) // Minor version
        le32(init, 20, HOST_MAX_TRANSFER)
        val initReply = exchange(control, init, INITIALIZE_CMPLT, id, timeoutMs) ?: error("the radio did not answer the RNDIS initialize request")
        if (initReply.size < 52) error("the radio's RNDIS initialize reply is too short")
        val status = le32(initReply, 12)
        if (status != STATUS_SUCCESS) error("the radio refused RNDIS initialization (status 0x%08x)".format(status))
        val maxTransfer = le32(initReply, 36).let { if (it in 64..1_048_576) it else HOST_MAX_TRANSFER }

        id++
        val query = ByteArray(28)
        le32(query, 0, QUERY_MSG)
        le32(query, 4, query.size)
        le32(query, 8, id)
        le32(query, 12, OID_802_3_PERMANENT_ADDRESS)
        le32(query, 16, 0)
        le32(query, 20, 20)
        val queryReply = exchange(control, query, QUERY_CMPLT, id, timeoutMs) ?: error("the radio did not report its RNDIS MAC address")
        if (queryReply.size < 24 || le32(queryReply, 12) != STATUS_SUCCESS) error("the radio refused the RNDIS MAC address query")
        val len = le32(queryReply, 16)
        val offset = 8 + le32(queryReply, 20)
        if (len < 6 || offset < 24 || offset + 6 > queryReply.size) error("the radio's RNDIS MAC address reply is malformed")
        val mac = queryReply.copyOfRange(offset, offset + 6)

        id++
        val set = ByteArray(32)
        le32(set, 0, SET_MSG)
        le32(set, 4, set.size)
        le32(set, 8, id)
        le32(set, 12, OID_GEN_CURRENT_PACKET_FILTER)
        le32(set, 16, 4)
        le32(set, 20, 20)
        le32(set, 28, PACKET_FILTER)
        val setReply = exchange(control, set, SET_CMPLT, id, timeoutMs) ?: error("the radio did not accept the RNDIS packet filter")
        if (setReply.size < 16 || le32(setReply, 12) != STATUS_SUCCESS) error("the radio refused the RNDIS packet filter")
        return RndisDevice(mac, maxTransfer)
    }

    /** Sends [message] and waits for the reply of [type] to [id], answering keep-alives on the way. */
    private fun exchange(control: RndisControl, message: ByteArray, type: Int, id: Int, timeoutMs: Long): ByteArray? {
        control.send(message)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val reply = control.receive(left) ?: return null
            if (reply.size < 12) continue
            if (le32(reply, 0) == type && le32(reply, 8) == id) return reply
            answer(reply)?.let { control.send(it) }
        }
    }

    /**
     * What to send back for an unsolicited control message from the device (a keep-alive needs a
     * completion), or null when nothing is needed (status indications, stray completions).
     */
    fun answer(message: ByteArray): ByteArray? {
        if (message.size < 12 || le32(message, 0) != KEEPALIVE_MSG) return null
        val reply = ByteArray(16)
        le32(reply, 0, KEEPALIVE_CMPLT)
        le32(reply, 4, reply.size)
        le32(reply, 8, le32(message, 8))
        le32(reply, 12, STATUS_SUCCESS)
        return reply
    }

    /** HALT, sent before closing; the device does not answer it. */
    fun halt(): ByteArray = ByteArray(12).also {
        le32(it, 0, HALT_MSG)
        le32(it, 4, it.size)
        le32(it, 8, 0)
    }

    /** One Ethernet frame in a PACKET_MSG, ready for the bulk OUT endpoint. */
    fun wrap(frame: ByteArray): ByteArray {
        val m = ByteArray(PACKET_HEADER + frame.size)
        le32(m, 0, PACKET_MSG)
        le32(m, 4, m.size)
        le32(m, 8, PACKET_HEADER - 8) // Data offset, counted from byte 8.
        le32(m, 12, frame.size)
        frame.copyInto(m, PACKET_HEADER)
        return m
    }

    /**
     * The Ethernet frames in one bulk IN transfer, which holds one or more whole PACKET_MSGs.
     * Anything malformed ends the transfer; a trailing pad byte is ignored.
     */
    fun unwrap(transfer: ByteArray, offset: Int = 0, length: Int = transfer.size - offset): List<ByteArray> {
        val frames = ArrayList<ByteArray>(1)
        var i = offset
        val end = offset + length
        while (i + PACKET_HEADER <= end) {
            if (le32(transfer, i) != PACKET_MSG) break
            val msgLen = le32(transfer, i + 4)
            if (msgLen < PACKET_HEADER || msgLen > end - i) break
            val dataStart = i + 8 + le32(transfer, i + 8)
            val dataLen = le32(transfer, i + 12)
            if (dataLen <= 0 || dataStart < i + 8 || dataStart + dataLen > i + msgLen) break
            frames.add(transfer.copyOfRange(dataStart, dataStart + dataLen))
            i += msgLen
        }
        return frames
    }

    fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    fun le32(b: ByteArray, i: Int, v: Int) {
        b[i] = v.toByte()
        b[i + 1] = (v ushr 8).toByte()
        b[i + 2] = (v ushr 16).toByte()
        b[i + 3] = (v ushr 24).toByte()
    }
}
