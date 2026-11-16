// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.sched

import com.fearmikey.takwave.core.ax25.Crc16

/**
 * Time-slotted transmit (a simple TDMA overlay).
 *
 * UTC time is cut into repeating cycles of [slotCount] slots of [slotMs] each, aligned to the
 * Unix epoch, so every station with a correct clock (network time or GNSS) agrees on where the
 * slots are without exchanging anything over the air. A station only starts transmitting inside
 * its own slot, and only if the transmission will finish [guardMs] before the slot ends. The
 * guard absorbs clock error between stations: with the default 250 ms, two clocks may disagree
 * by up to a quarter of a second before neighboring slots can touch.
 *
 * Carrier sensing still applies inside the slot, so a station whose clock is wrong, or one that
 * is not using slots at all, is deferred to rather than transmitted over.
 */
data class SlotConfig(
    val slotMs: Int = 3000,
    val slotCount: Int = 8,
    /** This station's slot, 0 until [slotCount]. */
    val slotIndex: Int = 0,
    val guardMs: Int = 250,
) {
    init {
        require(slotMs >= 500) { "slot length must be at least 500 ms" }
        require(slotCount in 1..64) { "slot count must be 1 to 64" }
        require(slotIndex in 0 until slotCount) { "slot index must be 0 to ${slotCount - 1}" }
        require(guardMs in 0 until slotMs) { "guard time must be shorter than the slot" }
    }
}

class SlotSchedule(val config: SlotConfig) {
    val cycleMs: Long = config.slotMs.toLong() * config.slotCount

    /** The slot that is current at [nowMs]. */
    fun slotAt(nowMs: Long): Int = (Math.floorMod(nowMs, cycleMs) / config.slotMs).toInt()

    /**
     * How long to wait, from [nowMs], before a transmission lasting [txMs] may begin.
     * Zero means "start now".
     */
    fun waitMs(nowMs: Long, txMs: Long): Long {
        val pos = Math.floorMod(nowMs, cycleMs)
        val start = config.slotIndex.toLong() * config.slotMs
        val end = start + config.slotMs
        if (pos in start until end) {
            val fits = txMs + config.guardMs <= config.slotMs
            if (fits) {
                if (pos + txMs + config.guardMs <= end) return 0
            } else if (pos - start <= LATE_START_MS) {
                // Longer than a slot can hold: the only sensible place is the very start of our
                // own slot. It will overrun into the next one; carrier sensing covers that.
                return 0
            }
        }
        val wait = Math.floorMod(start - pos, cycleMs)
        return if (wait == 0L) cycleMs else wait
    }

    companion object {
        /** An oversize transmission may start this late into its slot. */
        const val LATE_START_MS = 200L

        /**
         * A stable default slot derived from the callsign, so stations spread out without any
         * coordination. Two callsigns can land on the same slot; assign slots by hand to be sure.
         */
        fun autoSlot(callsign: String, slotCount: Int): Int =
            Crc16.compute(callsign.uppercase().toByteArray(Charsets.US_ASCII)) % slotCount
    }
}

/**
 * Decides when the soft modem may key up: plain p-persistent CSMA, or, when a [slots] schedule
 * is set, "own slot and channel clear".
 */
class ChannelAccess(
    private val slotTimeMs: Int = 100,
    private val persistence: Int = 63,
    private val slots: SlotSchedule? = null,
    random: java.util.Random = java.util.Random(),
    private val clock: Clock = Clock.SYSTEM,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val busy: () -> Boolean,
) {
    private val csma = Csma(slotTimeMs, persistence, random, clock, sleep, busy)

    /**
     * Blocks until a transmission of [txMs] may start. Returns false when no opportunity came
     * within [maxWaitMs] (plus one slot cycle when slots are in use).
     */
    fun await(txMs: Long, maxWaitMs: Long): Boolean {
        val schedule = slots ?: return csma.awaitClear(maxWaitMs)
        val deadline = clock.nowMs() + maxWaitMs + schedule.cycleMs
        while (true) {
            val now = clock.nowMs()
            if (now >= deadline) return false
            val wait = schedule.waitMs(now, txMs)
            when {
                wait > 0 -> sleep(minOf(wait, MAX_NAP_MS))
                busy() -> sleep(BUSY_POLL_MS)
                // Our slot and nobody is transmitting. No random back-off: the slot is ours.
                else -> return true
            }
        }
    }

    private companion object {
        const val MAX_NAP_MS = 100L
        const val BUSY_POLL_MS = 10L
    }
}
