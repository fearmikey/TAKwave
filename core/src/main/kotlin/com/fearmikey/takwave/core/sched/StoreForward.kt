// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.sched

import com.fearmikey.takwave.core.codec.ChatMessage
import com.fearmikey.takwave.core.codec.DecodeResult
import com.fearmikey.takwave.core.codec.EmergencyMessage
import com.fearmikey.takwave.core.codec.MarkerMessage
import com.fearmikey.takwave.core.codec.PacketCodec
import com.fearmikey.takwave.core.codec.PliMessage
import com.fearmikey.takwave.core.codec.TakMessage
import com.fearmikey.takwave.core.codec.Wire
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException

/**
 * Limits for store and forward. Every one of them exists to keep repeats from crowding out
 * fresh traffic on a 1200 baud channel.
 */
data class RepeatConfig(
    /** Hold this station's own chat and markers and send them again when a new station is heard. */
    val resendOwn: Boolean = true,
    /** Hold chat, markers and emergency alerts heard from other stations and pass them on. */
    val relay: Boolean = false,
    /** A held message is discarded this long after it was first sent (or first heard, for a relay). */
    val holdMs: Long = 30 * 60_000L,
    /** How many times this station transmits one held message, not counting its author's first transmission. */
    val maxRepeats: Int = 3,
    /** Shortest time between two transmissions of the same message that this station makes or hears. */
    val minGapMs: Long = 120_000L,
    /** Most repeats (re-sends and relays together) this station transmits in any ten minutes. */
    val budgetPer10Min: Int = 6,
    /** A station counts as in range for this long after it was last heard. */
    val presenceMs: Long = 180_000L,
    /** Most messages held at once, counted separately for own and relayed messages. */
    val capacity: Int = 16,
)

private fun repeatKey(m: TakMessage): String? = when (m) {
    is ChatMessage -> "C:${m.key}"
    is MarkerMessage -> "M:${m.key}"
    is EmergencyMessage -> "E:${m.key}"
    is PliMessage -> null
}

/** Order in which due repeats go out: alerts, then chat, then markers. */
private fun repeatRank(m: TakMessage): Int = when (m) {
    is EmergencyMessage -> 0
    is ChatMessage -> 1
    else -> 2
}

/**
 * Saves held messages in their clear over-air encoding, which every message type already has,
 * so the file format follows the protocol without a second serializer to keep in step.
 */
internal object HeldMessageIo {
    private val codec = PacketCodec()
    private const val MAX_ITEMS = 4096

    fun write(out: DataOutput, m: TakMessage) {
        val b = codec.encode(m)
        out.writeShort(b.size)
        out.write(b)
    }

    fun read(input: DataInput): TakMessage {
        val n = input.readUnsignedShort()
        if (n < 3 || n > Wire.MAX_PAYLOAD * 2) throw IOException("bad message length $n")
        val b = ByteArray(n)
        input.readFully(b)
        return (codec.decode(b) as? DecodeResult.Ok)?.message ?: throw IOException("unreadable message")
    }

    fun count(input: DataInput): Int {
        val n = input.readInt()
        if (n !in 0..MAX_ITEMS) throw IOException("bad count $n")
        return n
    }

    fun writeOptionalLong(out: DataOutput, v: Long?) {
        out.writeBoolean(v != null)
        out.writeLong(v ?: 0L)
    }

    fun readOptionalLong(input: DataInput): Long? {
        val present = input.readBoolean()
        val v = input.readLong()
        return if (present) v else null
    }
}

enum class SeenResult {
    /** Not seen before (or a newer version of a marker): deliver it. */
    NEW,
    /** Already seen: do not deliver it again. */
    REPEAT,
    /** An older version of a marker than the one already seen: do not deliver it. */
    OUTDATED,
}

/**
 * Remembers which chat messages, markers and alerts this station has already seen, whether they
 * came from its own TAK clients or from RF, so that a repeat is not shown twice.
 *
 * A chat message is identified by its message id. A marker or alert is a repeat when it equals
 * the newest version seen for its uid, and outdated when it was written earlier than that
 * version, so a relay that missed an edit (or a cancel) cannot bring the old version back. Times
 * are when the author first sent the message, on this station's clock (reception time minus the
 * packet's age).
 */
class SeenCache(private val ttlMs: Long = 3 * 60 * 60_000L, private val maxEntries: Int = 1024) {
    private class Seen(var latest: TakMessage, var originMs: Long, var atMs: Long)

    /** One saved entry, parsed but not yet applied. */
    internal class Saved(val message: TakMessage, val originMs: Long, val atMs: Long)

    private val seen = object : LinkedHashMap<String, Seen>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Seen>?): Boolean = size > maxEntries
    }

    /** Records [message], written at [originMs]. Position reports are always [SeenResult.NEW]. */
    @Synchronized
    fun accept(message: TakMessage, originMs: Long, nowMs: Long): SeenResult {
        val key = repeatKey(message) ?: return SeenResult.NEW
        val s = seen[key]
        if (s == null || nowMs - s.atMs > ttlMs) {
            seen[key] = Seen(message, originMs, nowMs)
            return SeenResult.NEW
        }
        s.atMs = nowMs
        if (message is ChatMessage || s.latest == message) return SeenResult.REPEAT
        if (originMs < s.originMs) return SeenResult.OUTDATED
        s.latest = message
        s.originMs = originMs
        return SeenResult.NEW
    }

    @Synchronized
    internal fun writeTo(out: DataOutput) {
        out.writeInt(seen.size)
        for (s in seen.values) {
            HeldMessageIo.write(out, s.latest)
            out.writeLong(s.originMs)
            out.writeLong(s.atMs)
        }
    }

    internal fun read(input: DataInput): List<Saved> =
        List(HeldMessageIo.count(input)) { Saved(HeldMessageIo.read(input), input.readLong(), input.readLong()) }

    /** Adds saved entries that have not expired by [nowMs]. Returns how many were kept. */
    @Synchronized
    internal fun restore(saved: List<Saved>, nowMs: Long): Int {
        var kept = 0
        for (s in saved) {
            val key = repeatKey(s.message) ?: continue
            if (nowMs - s.atMs > ttlMs || seen.containsKey(key)) continue
            seen[key] = Seen(s.message, s.originMs, s.atMs)
            kept++
        }
        return kept
    }
}

/**
 * Store and forward: holds chat messages, markers and emergency alerts and decides when one is
 * worth sending again. Position reports are never held; a stored position is a wrong position.
 *
 * Two kinds of message are held:
 *  * the station's own chat and markers, sent again when a station is heard that was not in
 *    range before (the station's own alerts are repeated by [EmergencyBeacon] instead);
 *  * (relay stations only) messages heard directly from their author, passed on once after a
 *    short random delay and again when a station is heard that was not in range before.
 *
 * Every transmission is subject to the limits in [RepeatConfig]. Hearing anyone else send the
 * same message cancels a pending repeat. The caller serializes access (the Gateway lock).
 */
class RepeatStore(private val config: RepeatConfig = RepeatConfig(), private val random: java.util.Random = java.util.Random()) {
    /**
     * A message to transmit now. [relayed] is true when it was written by another station;
     * [originMs] is when its author first sent it, for the age field.
     */
    class Repeat(val message: TakMessage, val relayed: Boolean, val originMs: Long)

    /** [createdMs] starts the hold time; [originMs] is when the author first sent the message. */
    private class Entry(val message: TakMessage, val relayed: Boolean, val createdMs: Long, val originMs: Long) {
        /** Stations that were in range for some transmission of this message. */
        val covered = HashSet<String>()
        var repeats = 0
        var lastSentMs: Long? = null
        var dueAtMs: Long? = null
    }

    private val entries = LinkedHashMap<String, Entry>()
    private val sentTimes = ArrayDeque<Long>()

    /** Everything that was held, parsed but not yet applied. */
    internal class Saved(val entries: List<SavedEntry>, val sentTimes: List<Long>)

    internal class SavedEntry(
        val message: TakMessage,
        val relayed: Boolean,
        val createdMs: Long,
        val originMs: Long,
        val repeats: Int,
        val lastSentMs: Long?,
        val dueAtMs: Long?,
        val covered: Set<String>,
    )

    val size: Int get() = entries.size

    private fun add(key: String, entry: Entry) {
        val sameKind = entries.entries.filter { it.value.relayed == entry.relayed }
        if (sameKind.size >= config.capacity) entries.remove(sameKind.first().key)
        entries[key] = entry
    }

    /** The station just transmitted its own [message] for the first time; [present] are the stations in range. */
    fun onOwnSent(message: TakMessage, present: Set<String>, nowMs: Long) {
        if (!config.resendOwn) return
        // The station's own alert is already repeated on a timer while it is active.
        if (message is EmergencyMessage) return
        val key = repeatKey(message) ?: return
        val existing = entries[key]
        if (existing != null && !existing.relayed && existing.message == message) {
            // The client sent the same thing again; that transmission did the job of a repeat.
            existing.covered.addAll(present)
            existing.lastSentMs = nowMs
            existing.dueAtMs = null
            return
        }
        entries.remove(key)
        add(key, Entry(message, relayed = false, createdMs = nowMs, originMs = nowMs).apply {
            covered.addAll(present)
            lastSentMs = nowMs
        })
    }

    /**
     * A chat message or marker was heard on RF from station [from].
     *
     * @param isNew false when this station had already seen the message (or a newer version of it)
     * @param mayRelay true when the message came straight from its author and may be passed on
     * @param originMs when the author first sent it (reception time minus the packet's age)
     */
    fun onRf(
        message: TakMessage,
        from: String,
        isNew: Boolean,
        mayRelay: Boolean,
        present: Set<String>,
        nowMs: Long,
        originMs: Long = nowMs,
    ) {
        val key = repeatKey(message) ?: return
        val existing = entries[key]
        if (existing != null) {
            if (message is ChatMessage || existing.message == message) {
                // Someone else just put this message on the air: no need for ours right now.
                // (For an alert, the author's 30-second repeats keep a relay quiet.)
                existing.covered.addAll(present)
                existing.lastSentMs = nowMs
                existing.dueAtMs = null
                return
            }
            // An older version of the marker or alert is on the air; ours is still the one worth repeating.
            if (originMs < existing.originMs) return
            // A newer version (an edit, a cancel) is on the air; the held one is out of date.
            entries.remove(key)
        }
        if (!config.relay || !isNew || !mayRelay) return
        add(key, Entry(message, relayed = true, createdMs = nowMs, originMs = originMs).apply {
            covered.add(from)
            dueAtMs = nowMs + RELAY_DELAY_MS + random.nextInt(RELAY_JITTER_MS)
        })
    }

    /**
     * Station [source] was just heard. Held messages that it has not had a chance to receive
     * are scheduled for a repeat. [targetOf] returns the one station a message is meant for
     * (a direct chat whose recipient is known), or null when it is for everyone.
     */
    fun onStationHeard(source: String, nowMs: Long, targetOf: (TakMessage) -> String?) {
        for (e in entries.values) {
            if (e.dueAtMs != null || source in e.covered) continue
            val target = targetOf(e.message)
            if (target != null && target != source) continue
            val delay = if (e.relayed) RELAY_DELAY_MS + random.nextInt(RELAY_JITTER_MS) else OWN_DELAY_MS + random.nextInt(OWN_JITTER_MS)
            e.dueAtMs = maxOf(nowMs + delay, (e.lastSentMs ?: 0L) + config.minGapMs)
        }
    }

    /** Returns the next held message to transmit and records the transmission, or null. Alerts go first, then chat, then markers. */
    fun nextDue(nowMs: Long, present: Set<String>): Repeat? {
        entries.values.removeIf { nowMs - it.createdMs > config.holdMs }
        while (sentTimes.isNotEmpty() && nowMs - sentTimes.first() > BUDGET_WINDOW_MS) sentTimes.removeFirst()
        if (sentTimes.size >= config.budgetPer10Min) return null
        val pick = entries.entries
            .filter { val due = it.value.dueAtMs; due != null && due <= nowMs }
            .minWithOrNull(compareBy({ repeatRank(it.value.message) }, { it.value.dueAtMs }))
            ?: return null
        val e = pick.value
        e.repeats++
        e.lastSentMs = nowMs
        e.dueAtMs = null
        e.covered.addAll(present)
        sentTimes.addLast(nowMs)
        if (e.repeats >= config.maxRepeats) entries.remove(pick.key)
        return Repeat(e.message, e.relayed, e.originMs)
    }

    internal fun writeTo(out: DataOutput) {
        out.writeInt(entries.size)
        for (e in entries.values) {
            HeldMessageIo.write(out, e.message)
            out.writeBoolean(e.relayed)
            out.writeLong(e.createdMs)
            out.writeLong(e.originMs)
            out.writeInt(e.repeats)
            HeldMessageIo.writeOptionalLong(out, e.lastSentMs)
            HeldMessageIo.writeOptionalLong(out, e.dueAtMs)
            out.writeInt(e.covered.size)
            for (c in e.covered) out.writeUTF(c)
        }
        out.writeInt(sentTimes.size)
        for (t in sentTimes) out.writeLong(t)
    }

    internal fun read(input: DataInput): Saved {
        val saved = List(HeldMessageIo.count(input)) {
            val message = HeldMessageIo.read(input)
            val relayed = input.readBoolean()
            val createdMs = input.readLong()
            val originMs = input.readLong()
            val repeats = input.readInt()
            val lastSentMs = HeldMessageIo.readOptionalLong(input)
            val dueAtMs = HeldMessageIo.readOptionalLong(input)
            val covered = List(HeldMessageIo.count(input)) { input.readUTF() }.toSet()
            SavedEntry(message, relayed, createdMs, originMs, repeats, lastSentMs, dueAtMs, covered)
        }
        val times = List(HeldMessageIo.count(input)) { input.readLong() }
        return Saved(saved, times)
    }

    /**
     * Takes back what was held before a restart, under the current configuration: kinds that are
     * now switched off, messages past the hold time and messages already repeated enough are
     * dropped, and the capacity applies. Returns how many messages are held afterward.
     */
    internal fun restore(saved: Saved, nowMs: Long): Int {
        for (s in saved.entries) {
            val key = repeatKey(s.message) ?: continue
            if (if (s.relayed) !config.relay else !config.resendOwn) continue
            if (s.message is EmergencyMessage && !s.relayed) continue
            if (nowMs - s.createdMs > config.holdMs || s.repeats >= config.maxRepeats) continue
            if (entries.containsKey(key)) continue
            add(key, Entry(s.message, s.relayed, s.createdMs, s.originMs).apply {
                covered.addAll(s.covered)
                repeats = s.repeats
                lastSentMs = s.lastSentMs
                dueAtMs = s.dueAtMs
            })
        }
        // The budget counts transmissions made before the restart too.
        for (t in saved.sentTimes.sorted()) {
            if (nowMs - t <= BUDGET_WINDOW_MS && t <= nowMs) sentTimes.addLast(t)
        }
        return entries.size
    }

    companion object {
        const val BUDGET_WINDOW_MS = 10 * 60_000L
        /** The author waits this long (plus jitter) after hearing a new station, so that station's own traffic gets out first. */
        const val OWN_DELAY_MS = 2_000L
        const val OWN_JITTER_MS = 3_000
        /** Relays wait longer and at random, so the author goes first and two relays rarely both transmit. */
        const val RELAY_DELAY_MS = 4_000L
        const val RELAY_JITTER_MS = 6_000
    }
}
