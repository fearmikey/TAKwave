// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.gateway

import com.fearmikey.takwave.core.ax25.Ax25Address
import com.fearmikey.takwave.core.ax25.Ax25Frame
import com.fearmikey.takwave.core.codec.ChatMessage
import com.fearmikey.takwave.core.codec.DecodeResult
import com.fearmikey.takwave.core.codec.EmergencyMessage
import com.fearmikey.takwave.core.codec.MarkerMessage
import com.fearmikey.takwave.core.codec.PacketCodec
import com.fearmikey.takwave.core.codec.PliMessage
import com.fearmikey.takwave.core.codec.TakMessage
import com.fearmikey.takwave.core.cot.Classified
import com.fearmikey.takwave.core.cot.CotBuilder
import com.fearmikey.takwave.core.cot.CotMapper
import com.fearmikey.takwave.core.cot.CotParser
import com.fearmikey.takwave.core.sched.Clock
import com.fearmikey.takwave.core.sched.EmergencyBeacon
import com.fearmikey.takwave.core.sched.EmergencyConfig
import com.fearmikey.takwave.core.sched.LoopGuard
import com.fearmikey.takwave.core.sched.PliConfig
import com.fearmikey.takwave.core.sched.PliScheduler
import com.fearmikey.takwave.core.sched.RepeatConfig
import com.fearmikey.takwave.core.sched.RepeatStore
import com.fearmikey.takwave.core.sched.SeenCache
import com.fearmikey.takwave.core.sched.SeenResult
import com.fearmikey.takwave.core.sched.TxQueue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** A one-line human-readable description for the packet log. */
fun TakMessage.summary(): String = when (this) {
    is PliMessage -> "PLI $callsign %.5f,%.5f".format(java.util.Locale.ROOT, lat, lon)
    is ChatMessage -> "CHAT $senderCallsign > $roomName: $text"
    is MarkerMessage -> "MARKER $name ($cotType) %.5f,%.5f".format(java.util.Locale.ROOT, lat, lon)
    is EmergencyMessage ->
        if (active) "EMERGENCY ${kind.label} $text %.5f,%.5f".format(java.util.Locale.ROOT, lat, lon)
        else "EMERGENCY cancelled $text"
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

/** Why a frame is being transmitted. */
enum class TxKind {
    /** Fresh traffic from a local TAK client. */
    NEW,
    /** This station's own earlier message, sent again for a station that has come into range. */
    RESEND,
    /** A message written by another station, passed on by this one. */
    RELAY,
}

/** A frame ready for the transport. */
class TxItem(val frame: ByteArray, val message: TakMessage, val enqueuedAtMs: Long, val kind: TxKind = TxKind.NEW)

/** How a received frame was handled, for the channel statistics. */
enum class RxKind {
    /** Delivered to the TAK clients. */
    DELIVERED,
    /** Valid TAKwave traffic not delivered again: a duplicate, an old repeat, or our own echo. */
    REPEAT,
    /** Not TAKwave traffic. */
    OTHER,
    /** TAKwave traffic that could not be read. */
    INVALID,
}

/** What happened to one frame received from RF. */
data class RfOutcome(
    /** CoT XML to deliver to every TAK client, or null when the frame was dropped. */
    val xml: String?,
    val message: TakMessage? = null,
    val source: String? = null,
    val encrypted: Boolean = false,
    /** Why the frame was dropped. */
    val note: String? = null,
    /** True when [source] passed the message on for another station. */
    val relayed: Boolean = false,
    /** Seconds since the author first sent the message; 0 for a first transmission. */
    val ageS: Int = 0,
    val kind: RxKind = if (xml != null) RxKind.DELIVERED else RxKind.INVALID,
)

/** A station heard on the air, keyed by its AX.25 source address. */
data class HeardStation(
    val source: String,
    val takCallsign: String?,
    val lastHeardMs: Long,
    val packets: Int,
    val lat: Double?,
    val lon: Double?,
    /** Position reports received from this station that carried its report counter. */
    val pliReceived: Int = 0,
    /** Position reports from this station that never arrived, counted from gaps in its report counter. */
    val pliMissed: Int = 0,
) {
    /** Share of this station's position reports that arrived, or null until enough have been seen. */
    val pliReception: Float?
        get() = if (pliReceived < 5) null else pliReceived.toFloat() / (pliReceived + pliMissed)
}

/** An emergency alert heard on the air that has not been cancelled. */
data class HeardAlert(
    val message: EmergencyMessage,
    /** The station it was heard from (a relay, when [relayed]). */
    val source: String,
    val relayed: Boolean,
    val lastHeardMs: Long,
    /** When the author raised (or last changed) it, on this station's clock. */
    val originMs: Long,
)

/** What [Gateway.restoreState] took back from the previous run. */
data class RestoreResult(val held: Int, val seen: Int, val note: String? = null)

/**
 * The heart of TAKwave: decides what client traffic goes on the air, turns it into AX.25 frames,
 * and turns received frames back into CoT. It owns the PLI scheduler, the transmit queue, loop
 * prevention, and store and forward. It does no I/O, so the whole CoT path is unit-testable.
 *
 * Traffic that a TAK client echoes back after it arrived from RF is never transmitted. The
 * only RF traffic that goes back on the air is what [RepeatStore] releases: chat, markers and
 * emergency alerts, on a relay station, within the limits of [RepeatConfig].
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
    private val repeatConfig: RepeatConfig = RepeatConfig(),
    random: java.util.Random = java.util.Random(),
    emergencyConfig: EmergencyConfig = EmergencyConfig(),
) {
    private val scheduler = PliScheduler(pliConfig)
    private val beacon = EmergencyBeacon(emergencyConfig)
    private val queue = TxQueue(queueSize)
    private val loopGuard = LoopGuard()
    private val repeats = RepeatStore(repeatConfig, random)
    private val seen = SeenCache()
    private val heard = LinkedHashMap<String, HeardStation>()
    private val rfPositions = HashMap<String, Pair<Double, Double>>()
    /** TAK uid -> AX.25 source of the station that reports it, to aim direct chats. */
    private val uidSource = HashMap<String, String>()
    private val recentRx = ArrayDeque<Pair<Int, Long>>()
    /** This station's position report counter (PROTOCOL.md 4.4), and the last one heard from each station. */
    private var pliSeq = 0
    private val lastSeq = HashMap<String, Int>()
    private val rfAlerts = LinkedHashMap<String, HeardAlert>()

    /** Chat messages and markers currently held for a possible repeat. */
    val heldCount: Int
        @Synchronized get() = repeats.size

    /** Stations heard recently enough to count as in range. */
    private fun present(now: Long): Set<String> =
        heard.values.filter { now - it.lastHeardMs <= repeatConfig.presenceMs }.mapTo(HashSet()) { it.source }

    /** The most recent position reported by a local TAK client, for distance and bearing. */
    @Volatile
    var localPosition: Pair<Double, Double>? = null
        private set

    val queueDepth: Int get() = queue.size

    /** Queued messages discarded so far because they went stale or the queue overflowed. */
    val queueDropped: Int get() = queue.dropped

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
        if (message is EmergencyMessage) {
            beacon.update(message, now)
            // Remember our own alert, so a relayed copy of it is not delivered back to the clients.
            seen.accept(message, originMs = now, nowMs = now)
            return ClientOutcome(relay = true, acceptedForRf = true)
        }
        if (message is PliMessage) {
            scheduler.update(message, now)
            return ClientOutcome(relay = true, acceptedForRf = true)
        }
        val ok = queue.offer(message, now)
        // Remember our own traffic, so a relayed copy of it is not delivered back to the clients.
        if (ok) seen.accept(message, originMs = now, nowMs = now)
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
        for (alert in beacon.due(now)) queue.offer(alert, now)
        for (pli in scheduler.due(now)) queue.offer(pli, now)
        val entry = queue.poll(now)
        if (entry != null) {
            repeats.onOwnSent(entry.message, present(now), now)
            // Position reports carry a counter so receivers can tell how many they missed.
            val seq = if (entry.message is PliMessage) pliSeq.also { pliSeq = (pliSeq + 1) and 0xFF } else null
            val frame = Ax25Frame(DESTINATION, src, codec.encode(entry.message, seq = seq)).encode()
            return TxItem(frame, entry.message, entry.enqueuedAtMs)
        }
        // Repeats only use air time that fresh traffic does not need.
        val repeat = repeats.nextDue(now, present(now)) ?: return null
        // The age lets receivers show the time the message was written, not the time this copy arrived.
        val ageS = ((now - repeat.originMs) / 1000).toInt()
        val frame = Ax25Frame(DESTINATION, src, codec.encode(repeat.message, if (repeat.relayed) 1 else 0, ageS)).encode()
        return TxItem(frame, repeat.message, now, if (repeat.relayed) TxKind.RELAY else TxKind.RESEND)
    }

    // ---- RF -> TAK clients ----

    @Synchronized
    fun onRfFrame(frameBytes: ByteArray): RfOutcome {
        val now = clock.nowMs()
        val frame = Ax25Frame.decode(frameBytes) ?: return RfOutcome(null, note = "not a valid AX.25 frame", kind = RxKind.OTHER)
        val src = frame.source.toString()
        if (!frame.isUi || frame.pid != Ax25Frame.PID_NO_LAYER3 || frame.destination.call != DESTINATION.call) {
            return RfOutcome(null, source = src, note = "not a TAKwave frame (to ${frame.destination})", kind = RxKind.OTHER)
        }
        if (frame.source == source) return RfOutcome(null, source = src, note = "own transmission heard", kind = RxKind.REPEAT)
        if (isDuplicate(frame.info, now)) return RfOutcome(null, source = src, note = "duplicate", kind = RxKind.REPEAT)
        val decoded = codec.decode(frame.info)
        if (decoded is DecodeResult.Error) return RfOutcome(null, source = src, note = decoded.reason)
        val ok = decoded as DecodeResult.Ok
        val message = ok.message
        // A relayed frame carries the relay's callsign, but its content describes someone else.
        val relayed = ok.hops > 0
        // When the author first sent it, on our clock. No clock agreement between stations is needed.
        val originMs = now - ok.ageS * 1000L
        // A repeat of something already seen is not delivered twice, and an older version of a
        // marker never replaces a newer one.
        val seenResult = seen.accept(message, originMs, now)

        // An outdated marker must not displace the newer version in the loop guard either.
        if (seenResult != SeenResult.OUTDATED) loopGuard.markFromRf(message, now)
        val previous = heard[src]
        var lat = previous?.lat
        var lon = previous?.lon
        var takCallsign = previous?.takCallsign
        var pliReceived = previous?.pliReceived ?: 0
        var pliMissed = previous?.pliMissed ?: 0
        val seq = ok.seq
        if (!relayed && message is PliMessage && seq != null) {
            val last = lastSeq[src]
            if (last != null) {
                val gap = (seq - last - 1) and 0xFF
                // A very large gap is a restart or a long absence rather than loss on the link.
                if (gap in 1..MAX_SEQ_GAP) pliMissed += gap
            }
            if (lastSeq.size > MAX_TRACKED) lastSeq.clear()
            lastSeq[src] = seq
            pliReceived++
        }
        if (!relayed) when (message) {
            is PliMessage -> {
                lat = message.lat
                lon = message.lon
                takCallsign = message.callsign
                if (rfPositions.size > MAX_TRACKED) rfPositions.clear()
                rfPositions[message.uid] = message.lat to message.lon
                if (uidSource.size > MAX_TRACKED) uidSource.clear()
                uidSource[message.uid] = src
            }
            is ChatMessage -> takCallsign = takCallsign ?: message.senderCallsign
            is MarkerMessage -> {}
            is EmergencyMessage -> {
                if (message.latE7 != 0 || message.lonE7 != 0) {
                    lat = message.lat
                    lon = message.lon
                }
                takCallsign = takCallsign ?: message.text.ifEmpty { null }
            }
        }
        if (heard.size >= MAX_TRACKED && previous == null) heard.remove(heard.keys.first())
        heard[src] = HeardStation(src, takCallsign, now, (previous?.packets ?: 0) + 1, lat, lon, pliReceived, pliMissed)

        if (message is EmergencyMessage && seenResult != SeenResult.OUTDATED) {
            if (message.active) rfAlerts[message.uid] = HeardAlert(message, src, relayed, now, originMs)
            else rfAlerts.remove(message.uid)
            if (rfAlerts.size > MAX_TRACKED) rfAlerts.remove(rfAlerts.keys.first())
        }

        // Store and forward: hearing a message tells us it does not need repeating right now.
        if (message !is PliMessage) {
            // Only pass on what came straight from its author, and never change how it is protected:
            // clear traffic is not relayed by an encrypting station, nor the other way round.
            val mayRelay = !relayed && source != null && ok.encrypted == (codec.cipherDescription != null)
            repeats.onRf(message, src, seenResult == SeenResult.NEW, mayRelay, present(now), now, originMs)
        }
        repeats.onStationHeard(src, now) { m -> (m as? ChatMessage)?.let { uidSource[it.roomId] } }
        when (seenResult) {
            SeenResult.REPEAT -> {
                // The author's own repeats of an active alert are delivered, so the alert stays fresh in ATAK.
                val refresh = message is EmergencyMessage && message.active && !relayed
                if (!refresh) return RfOutcome(null, message, src, ok.encrypted, "already received", relayed, ok.ageS, RxKind.REPEAT)
            }
            SeenResult.OUTDATED -> return RfOutcome(
                null, message, src, ok.encrypted, "older than the version already received", relayed, ok.ageS, RxKind.REPEAT,
            )
            SeenResult.NEW -> {}
        }

        val xml = when (message) {
            is ChatMessage -> {
                val pos = rfPositions[message.senderUid]
                CotBuilder.chat(message, now, pos?.first, pos?.second, originMs)
            }
            else -> CotBuilder.build(message, now, originMs)
        }
        return RfOutcome(xml, message, src, ok.encrypted, relayed = relayed, ageS = ok.ageS)
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

    /** Alerts heard on the air within the last half hour and not cancelled, most recent first. */
    @Synchronized
    fun heardAlerts(): List<HeardAlert> {
        val now = clock.nowMs()
        return rfAlerts.values.filter { now - it.lastHeardMs <= ALERT_SHOW_MS }.sortedByDescending { it.lastHeardMs }
    }

    /** Alerts raised by a local TAK client that this station is sending. */
    @Synchronized
    fun ownAlerts(): List<EmergencyMessage> = beacon.active()

    // ---- Persistence ----

    /** Held messages and the seen-cache, so a restart neither loses them nor shows repeats twice. */
    @Synchronized
    fun saveState(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(STATE_MAGIC)
            out.writeByte(STATE_VERSION)
            out.writeBoolean(codec.cipherDescription != null)
            repeats.writeTo(out)
            seen.writeTo(out)
        }
        return bytes.toByteArray()
    }

    /**
     * Takes back what [saveState] wrote in an earlier run. Nothing changes unless the whole state
     * reads correctly. Held messages are dropped when encryption was switched on or off since,
     * so a message is never sent with a different protection than it had (PROTOCOL.md 9).
     */
    @Synchronized
    fun restoreState(state: ByteArray): RestoreResult {
        return try {
            val input = DataInputStream(ByteArrayInputStream(state))
            if (input.readInt() != STATE_MAGIC) return RestoreResult(0, 0, "not a TAKwave state file")
            if (input.readUnsignedByte() != STATE_VERSION) return RestoreResult(0, 0, "saved by a different TAKwave version")
            val wasProtected = input.readBoolean()
            val held = repeats.read(input)
            val seenSaved = seen.read(input)
            val now = clock.nowMs()
            val sameProtection = wasProtected == (codec.cipherDescription != null)
            val heldCount = if (sameProtection) repeats.restore(held, now) else 0
            val seenCount = seen.restore(seenSaved, now)
            val note = if (!sameProtection && held.entries.isNotEmpty()) {
                "encryption was switched ${if (wasProtected) "off" else "on"} since; held messages discarded"
            } else {
                null
            }
            RestoreResult(heldCount, seenCount, note)
        } catch (e: Exception) {
            RestoreResult(0, 0, "saved state unreadable (${e.message ?: e.javaClass.simpleName})")
        }
    }

    companion object {
        val DESTINATION = Ax25Address("TAKWV")
        /** Identical payloads inside this window are treated as digipeated or multipath copies. */
        const val DUPLICATE_WINDOW_MS = 5_000L
        private const val MAX_TRACKED = 256
        /** Larger gaps in a station's report counter are not counted as lost reports. */
        private const val MAX_SEQ_GAP = 32
        private const val ALERT_SHOW_MS = 30 * 60_000L
        private const val STATE_MAGIC = 0x54574653 // "TWFS"
        private const val STATE_VERSION = 1
    }
}
