// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.sched

import com.fearmikey.takwave.core.codec.ChatMessage
import com.fearmikey.takwave.core.codec.MarkerMessage
import com.fearmikey.takwave.core.codec.PliMessage
import com.fearmikey.takwave.core.codec.TakMessage
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Injected time source so that scheduling can be tested without waiting. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/** Great-circle helpers (haversine and initial bearing on a spherical Earth). */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from point 1 to point 2 in degrees, 0-360. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Smallest angle between two headings, 0-180. */
    fun headingDelta(a: Int, b: Int): Int {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }
}

data class PliConfig(
    /** Send at least this often while the client keeps reporting. */
    val baseIntervalS: Int = 60,
    /** Never send more often than this. */
    val minIntervalS: Int = 15,
    /** Send early after moving this far from the last transmitted position. */
    val distanceM: Double = 50.0,
    /** Send early after turning this much while moving. */
    val headingDeg: Int = 30,
    /** Below this speed (m/s) heading changes are ignored as GPS jitter. */
    val movingSpeedMps: Double = 0.5,
)

/**
 * Decides when a position report is worth air time. TAK clients report every few seconds; this
 * keeps only the newest report per uid and releases it on the base interval, or sooner (but not
 * before the minimum interval) when the station has moved or turned.
 */
class PliScheduler(private val config: PliConfig = PliConfig()) {
    private class Entry(var latest: PliMessage, var updatedAt: Long) {
        var fresh = true
        var lastSentAt: Long? = null
        var sent: PliMessage? = null
    }

    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun update(message: PliMessage, nowMs: Long) {
        val e = entries[message.uid]
        if (e == null) {
            entries[message.uid] = Entry(message, nowMs)
        } else {
            e.latest = message
            e.updatedAt = nowMs
            e.fresh = true
        }
    }

    /** Returns the reports that should be transmitted now and records them as sent. */
    @Synchronized
    fun due(nowMs: Long): List<PliMessage> {
        entries.values.removeIf { nowMs - it.updatedAt > FORGET_AFTER_MS }
        val out = ArrayList<PliMessage>()
        for (e in entries.values) {
            if (!e.fresh || !isDue(e, nowMs)) continue
            e.fresh = false
            e.lastSentAt = nowMs
            e.sent = e.latest
            out.add(e.latest)
        }
        return out
    }

    private fun isDue(e: Entry, nowMs: Long): Boolean {
        val last = e.lastSentAt ?: return true
        val sent = e.sent ?: return true
        val elapsed = nowMs - last
        if (elapsed >= config.baseIntervalS * 1000L) return true
        if (elapsed < config.minIntervalS * 1000L) return false
        val cur = e.latest
        if (Geo.distanceM(sent.lat, sent.lon, cur.lat, cur.lon) > config.distanceM) return true
        val moving = (cur.speedDms ?: 0) / 10.0 >= config.movingSpeedMps
        val c1 = sent.courseDeg
        val c2 = cur.courseDeg
        return moving && c1 != null && c2 != null && Geo.headingDelta(c1, c2) > config.headingDeg
    }

    private companion object {
        /** A client that has stopped reporting is forgotten after this long. */
        const val FORGET_AFTER_MS = 5 * 60 * 1000L
    }
}

/**
 * Transmit queue. Chat goes first, then markers, then PLI; first-in first-out within a class.
 * A newer PLI or marker replaces a queued one for the same uid. Entries older than [maxAgeMs]
 * are dropped instead of sent. When full, the least important oldest entry makes room.
 */
class TxQueue(private val maxSize: Int = 32, private val maxAgeMs: Long = 120_000) {
    data class Entry(val message: TakMessage, val enqueuedAtMs: Long)

    private val entries = ArrayList<Entry>()

    /** Count of entries discarded because they went stale or the queue overflowed. */
    var dropped = 0
        private set

    val size: Int
        @Synchronized get() = entries.size

    private fun priority(m: TakMessage): Int = when (m) {
        is ChatMessage -> 2
        is MarkerMessage -> 1
        is PliMessage -> 0
    }

    /** Returns false when the message was refused because the queue is full of more important traffic. */
    @Synchronized
    fun offer(message: TakMessage, nowMs: Long): Boolean {
        if (message !is ChatMessage) {
            val i = entries.indexOfFirst { it.message::class == message::class && it.message.key == message.key }
            if (i >= 0) {
                // Keep the original queue position but carry the newer content and a fresh age.
                entries[i] = Entry(message, nowMs)
                return true
            }
        }
        if (entries.size >= maxSize) {
            val victim = entries.withIndex().minWith(compareBy({ priority(it.value.message) }, { it.value.enqueuedAtMs }))
            if (priority(victim.value.message) > priority(message)) {
                dropped++
                return false
            }
            entries.removeAt(victim.index)
            dropped++
        }
        entries.add(Entry(message, nowMs))
        return true
    }

    /** Removes and returns the next message to send, or null when the queue is empty. */
    @Synchronized
    fun poll(nowMs: Long): Entry? {
        val before = entries.size
        entries.removeIf { nowMs - it.enqueuedAtMs > maxAgeMs }
        dropped += before - entries.size
        if (entries.isEmpty()) return null
        var best = 0
        for (i in 1 until entries.size) {
            if (priority(entries[i].message) > priority(entries[best].message)) best = i
        }
        return entries.removeAt(best)
    }

    @Synchronized
    fun clear() = entries.clear()
}

/**
 * Remembers what arrived from RF so that it is never transmitted again.
 *
 * PLI uids and chat message ids heard on the air are blocked outright. A marker heard on the
 * air is blocked only while its content is unchanged, so a local user can still edit a received
 * marker and share the edit.
 */
class LoopGuard(private val ttlMs: Long = 10 * 60 * 1000L, private val maxEntries: Int = 2048) {
    private class Seen(val message: TakMessage, val atMs: Long)

    private val seen = object : LinkedHashMap<String, Seen>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Seen>?): Boolean = size > maxEntries
    }

    private fun mapKey(m: TakMessage): String = when (m) {
        is PliMessage -> "P:${m.key}"
        is ChatMessage -> "C:${m.key}"
        is MarkerMessage -> "M:${m.key}"
    }

    @Synchronized
    fun markFromRf(message: TakMessage, nowMs: Long) {
        seen[mapKey(message)] = Seen(message, nowMs)
    }

    /** True when sending [message] would put RF-originated traffic back on the air. */
    @Synchronized
    fun isFromRf(message: TakMessage, nowMs: Long): Boolean {
        val key = mapKey(message)
        val s = seen[key] ?: return false
        if (nowMs - s.atMs > ttlMs) {
            seen.remove(key)
            return false
        }
        return message !is MarkerMessage || s.message == message
    }
}

/**
 * p-persistent CSMA channel access (the scheme KISS TNCs use). Wait for the channel to go
 * quiet, then transmit with probability (persistence + 1) / 256; otherwise wait one slot time
 * and try again.
 */
class Csma(
    private val slotTimeMs: Int = 100,
    private val persistence: Int = 63,
    private val random: java.util.Random = java.util.Random(),
    private val clock: Clock = Clock.SYSTEM,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val busy: () -> Boolean,
) {
    /** Blocks until it is this station's turn. Returns false if the channel never cleared within [maxWaitMs]. */
    fun awaitClear(maxWaitMs: Long): Boolean {
        val deadline = clock.nowMs() + maxWaitMs
        while (true) {
            while (busy()) {
                if (clock.nowMs() >= deadline) return false
                sleep(BUSY_POLL_MS)
            }
            if (random.nextInt(256) <= persistence) return true
            if (clock.nowMs() >= deadline) return false
            sleep(slotTimeMs.toLong().coerceAtLeast(1))
        }
    }

    private companion object {
        const val BUSY_POLL_MS = 10L
    }
}
