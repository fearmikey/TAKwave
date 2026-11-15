// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.stats

import com.fearmikey.takwave.core.sched.Clock

/** Things that are counted on the channel. */
enum class ChannelEvent {
    /** A TAKwave packet received and handed to the TAK clients. */
    RX_DELIVERED,
    /** A valid TAKwave packet that was not delivered: a duplicate, a repeat already seen, or our own echo. */
    RX_REPEAT,
    /** A good AX.25 frame that is not TAKwave traffic (APRS, other packet users). */
    RX_OTHER,
    /** A TAKwave packet that could not be read (malformed, wrong key, unsupported version). */
    RX_INVALID,
    /** A frame that only arrived because FX.25 error correction repaired it. */
    RX_REPAIRED,
    /** A frame this station transmitted for the first time. */
    TX,
    /** A re-sent or relayed frame (store and forward). */
    TX_REPEAT,
    /** A frame that could not be sent (no clear channel in time, transmitter fault). */
    TX_FAILED,
    /** Queued traffic discarded because it went stale or the queue overflowed. */
    QUEUE_DROPPED,
}

/** Channel statistics over the last [windowMs] (or since the start, if that is shorter). */
data class ChannelHealth(
    val windowMs: Long,
    /** Share of listening time with a packet on the channel; null when the backend cannot tell (KISS TNC). */
    val busyFraction: Float?,
    /** Time this station spent transmitting. */
    val ownAirtimeMs: Long,
    val counts: Map<ChannelEvent, Int>,
) {
    /** Share of the window that this station spent transmitting. */
    val ownAirtimeFraction: Float get() = if (windowMs <= 0) 0f else (ownAirtimeMs.toFloat() / windowMs).coerceIn(0f, 1f)

    operator fun get(event: ChannelEvent): Int = counts[event] ?: 0

    companion object {
        /** Above this, collisions become likely; the UI suggests slowing down or using time slots. */
        const val BUSY_WARNING = 0.5f
        /** Above this share of air time, one station is crowding the others out. */
        const val AIRTIME_WARNING = 0.2f
    }
}

/**
 * Rolling channel statistics, kept in buckets so that old activity falls out of the window.
 * Thread-safe; fed by the transmit, receive and status threads.
 */
class ChannelStats(
    private val clock: Clock,
    private val windowMs: Long = 10 * 60_000L,
    private val bucketMs: Long = 10_000L,
) {
    private class Bucket(events: Int) {
        var id = Long.MIN_VALUE
        var listenMs = 0L
        var busyMs = 0L
        var airMs = 0L
        val counts = IntArray(events)

        fun reset(newId: Long) {
            id = newId
            listenMs = 0
            busyMs = 0
            airMs = 0
            counts.fill(0)
        }
    }

    private val buckets = Array((windowMs / bucketMs).toInt().coerceAtLeast(1)) { Bucket(ChannelEvent.entries.size) }
    private val startMs = clock.nowMs()

    private fun current(): Bucket {
        val id = Math.floorDiv(clock.nowMs(), bucketMs)
        val b = buckets[Math.floorMod(id, buckets.size.toLong()).toInt()]
        if (b.id != id) b.reset(id)
        return b
    }

    /** [elapsedMs] of listening, with or without a packet on the channel. Not called while transmitting. */
    @Synchronized
    fun sampleChannel(elapsedMs: Long, busy: Boolean) {
        if (elapsedMs <= 0) return
        val b = current()
        b.listenMs += elapsedMs
        if (busy) b.busyMs += elapsedMs
    }

    /** A frame went out, keying the transmitter for [airtimeMs]. */
    @Synchronized
    fun recordTx(airtimeMs: Long, repeat: Boolean) {
        val b = current()
        b.airMs += airtimeMs.coerceAtLeast(0)
        b.counts[(if (repeat) ChannelEvent.TX_REPEAT else ChannelEvent.TX).ordinal]++
    }

    @Synchronized
    fun count(event: ChannelEvent, n: Int = 1) {
        if (n > 0) current().counts[event.ordinal] += n
    }

    @Synchronized
    fun snapshot(): ChannelHealth {
        val now = clock.nowMs()
        val newest = Math.floorDiv(now, bucketMs)
        var listen = 0L
        var busy = 0L
        var air = 0L
        val counts = IntArray(ChannelEvent.entries.size)
        for (b in buckets) {
            if (b.id <= newest - buckets.size || b.id > newest) continue
            listen += b.listenMs
            busy += b.busyMs
            air += b.airMs
            for (i in counts.indices) counts[i] += b.counts[i]
        }
        val span = minOf(windowMs, (now - startMs).coerceAtLeast(1))
        return ChannelHealth(
            windowMs = span,
            busyFraction = if (listen > 0) (busy.toFloat() / listen).coerceIn(0f, 1f) else null,
            ownAirtimeMs = air,
            counts = ChannelEvent.entries.associateWith { counts[it.ordinal] },
        )
    }
}
