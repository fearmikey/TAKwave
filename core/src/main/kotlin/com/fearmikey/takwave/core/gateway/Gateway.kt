// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.gateway

import com.fearmikey.takwave.core.ax25.Ax25Address
import com.fearmikey.takwave.core.ax25.Ax25Frame
import com.fearmikey.takwave.core.codec.ChatMessage
import com.fearmikey.takwave.core.codec.DecodeResult
import com.fearmikey.takwave.core.codec.MarkerMessage
import com.fearmikey.takwave.core.codec.PacketCodec
import com.fearmikey.takwave.core.codec.PliMessage
import com.fearmikey.takwave.core.codec.TakMessage
import com.fearmikey.takwave.core.cot.Classified
import com.fearmikey.takwave.core.cot.CotBuilder
import com.fearmikey.takwave.core.cot.CotMapper
import com.fearmikey.takwave.core.cot.CotParser
import com.fearmikey.takwave.core.sched.Clock
import com.fearmikey.takwave.core.sched.LoopGuard
import com.fearmikey.takwave.core.sched.PliConfig
import com.fearmikey.takwave.core.sched.PliScheduler
import com.fearmikey.takwave.core.sched.TxQueue

/** A one-line human-readable description for the packet log. */
fun TakMessage.summary(): String = when (this) {
    is PliMessage -> "PLI $callsign %.5f,%.5f".format(java.util.Locale.ROOT, lat, lon)
    is ChatMessage -> "CHAT $senderCallsign > $roomName: $text"
    is MarkerMessage -> "MARKER $name ($cotType) %.5f,%.5f".format(java.util.Locale.ROOT, lat, lon)
}

/** What happened to one CoT event received from a TAK client. */
data class ClientOutcome(
    /** Forward the original XML to the other connected clients. */
    val relay: Boolean,
    /** XML to send back to the originating client (a ping reply), if any. */
    val reply: String? = null,
    /** True when the event was accepted for RF (queued, or handed to the PLI scheduler). */
    val acceptedForRf: Boolean = false,
    /** Why the event was not accepted for RF; for the debug log. */
    val note: String? = null,
)

/** A frame ready for the transport. */
class TxItem(val frame: ByteArray, val message: TakMessage, val enqueuedAtMs: Long)

/** What happened to one frame received from RF. */
data class RfOutcome(
    /** CoT XML to deliver to every TAK client, or null when the frame was dropped. */
    val xml: String?,
    val message: TakMessage? = null,
    val source: String? = null,
    val encrypted: Boolean = false,
    /** Why the frame was dropped. */
    val note: String? = null,
)

/** A station heard on the air, keyed by its AX.25 source address. */
data class HeardStation(
    val source: String,
    val takCallsign: String?,
    val lastHeardMs: Long,
    val packets: Int,
    val lat: Double?,
    val lon: Double?,
)

/**
 * The heart of TAKwave: decides what client traffic goes on the air, turns it into AX.25 frames,
 * and turns received frames back into CoT. It owns the PLI scheduler, the transmit queue, and
 * loop prevention. It does no I/O, so the whole CoT path is unit-testable.
 *
 * All methods are thread-safe.
 */
class Gateway(
    private val clock: Clock,
    /** The station's own AX.25 address. Null means "not configured": nothing is ever queued for RF. */
    private val source: Ax25Address?,
    private val codec: PacketCodec = PacketCodec(),
    private val pliConfig: PliConfig = PliConfig(),
    queueSize: Int = 32,
) {
    private val scheduler = PliScheduler(pliConfig)
    private val queue = TxQueue(queueSize)
    private val loopGuard = LoopGuard()
    private val heard = LinkedHashMap<String, HeardStation>()
    private val rfPositions = HashMap<String, Pair<Double, Double>>()
    private val recentRx = ArrayDeque<Pair<Int, Long>>()

    /** The most recent position reported by a local TAK client, for distance and bearing. */
    @Volatile
    var localPosition: Pair<Double, Double>? = null
        private set

    val queueDepth: Int get() = queue.size

    // ---- TAK client -> RF ----

    fun onClientEvent(xml: String): ClientOutcome {
        val event = CotParser.parse(xml) ?: return ClientOutcome(relay = false, note = "malformed CoT dropped")
        val now = clock.nowMs()
        return when (val c = CotMapper.classify(event, pliConfig.baseIntervalS)) {
            is Classified.Ping -> ClientOutcome(relay = false, reply = CotBuilder.pong(now), note = "ping answered locally")
            is Classified.Drop -> ClientOutcome(relay = true, note = c.reason)
            is Classified.Send -> offer(c.message, now)
        }
    }

    @Synchronized
    private fun offer(message: TakMessage, now: Long): ClientOutcome {
        if (message is PliMessage) localPosition = message.lat to message.lon
        if (loopGuard.isFromRf(message, now)) return ClientOutcome(relay = true, note = "originated from RF, not retransmitted")
        if (source == null) return ClientOutcome(relay = true, note = "no callsign configured, not transmitting")
        if (message is PliMessage) {
            scheduler.update(message, now)
            return ClientOutcome(relay = true, acceptedForRf = true)
        }
        val ok = queue.offer(message, now)
        return ClientOutcome(relay = true, acceptedForRf = ok, note = if (ok) null else "transmit queue full")
    }

    /** Queues a message directly, bypassing classification (used for the calibration test PLI). */
    @Synchronized
    fun enqueue(message: TakMessage): Boolean = source != null && queue.offer(message, clock.nowMs())

    /** Returns the next frame to transmit, or null when there is nothing to send right now. */
    @Synchronized
    fun pollFrame(): TxItem? {
        val src = source ?: return null
        val now = clock.nowMs()
        for (pli in scheduler.due(now)) queue.offer(pli, now)
        val entry = queue.poll(now) ?: return null
        val frame = Ax25Frame(DESTINATION, src, codec.encode(entry.message)).encode()
        return TxItem(frame, entry.message, entry.enqueuedAtMs)
    }

    // ---- RF -> TAK clients ----

    @Synchronized
    fun onRfFrame(frameBytes: ByteArray): RfOutcome {
        val now = clock.nowMs()
        val frame = Ax25Frame.decode(frameBytes) ?: return RfOutcome(null, note = "not a valid AX.25 frame")
        val src = frame.source.toString()
        if (!frame.isUi || frame.pid != Ax25Frame.PID_NO_LAYER3 || frame.destination.call != DESTINATION.call) {
            return RfOutcome(null, source = src, note = "not a TAKwave frame (to ${frame.destination})")
        }
        if (frame.source == source) return RfOutcome(null, source = src, note = "own transmission heard")
        if (isDuplicate(frame.info, now)) return RfOutcome(null, source = src, note = "duplicate")
        val decoded = codec.decode(frame.info)
        if (decoded is DecodeResult.Error) return RfOutcome(null, source = src, note = decoded.reason)
        val message = (decoded as DecodeResult.Ok).message

        loopGuard.markFromRf(message, now)
        val previous = heard[src]
        var lat = previous?.lat
        var lon = previous?.lon
        var takCallsign = previous?.takCallsign
        when (message) {
            is PliMessage -> {
                lat = message.lat
                lon = message.lon
                takCallsign = message.callsign
                if (rfPositions.size > MAX_TRACKED) rfPositions.clear()
                rfPositions[message.uid] = message.lat to message.lon
            }
            is ChatMessage -> takCallsign = takCallsign ?: message.senderCallsign
            is MarkerMessage -> {}
        }
        if (heard.size >= MAX_TRACKED && previous == null) heard.remove(heard.keys.first())
        heard[src] = HeardStation(src, takCallsign, now, (previous?.packets ?: 0) + 1, lat, lon)

        val xml = when (message) {
            is ChatMessage -> {
                val pos = rfPositions[message.senderUid]
                CotBuilder.chat(message, now, pos?.first, pos?.second)
            }
            else -> CotBuilder.build(message, now)
        }
        return RfOutcome(xml, message, src, decoded.encrypted)
    }

    private fun isDuplicate(info: ByteArray, now: Long): Boolean {
        while (recentRx.isNotEmpty() && now - recentRx.first().second > DUPLICATE_WINDOW_MS) recentRx.removeFirst()
        val hash = info.contentHashCode()
        if (recentRx.any { it.first == hash }) return true
        recentRx.addLast(hash to now)
        return false
    }

    /** Stations heard so far, most recent first. */
    @Synchronized
    fun heardStations(): List<HeardStation> = heard.values.sortedByDescending { it.lastHeardMs }

    companion object {
        val DESTINATION = Ax25Address("TAKWV")
        /** Identical payloads inside this window are treated as digipeated or multipath copies. */
        const val DUPLICATE_WINDOW_MS = 5_000L
        private const val MAX_TRACKED = 256
    }
}
