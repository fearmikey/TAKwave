// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.sched

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotScheduleTest {
    // 8 slots of 3 s: a 24 s cycle. Station in slot 2 owns 6.000-9.000 s of every cycle.
    private val schedule = SlotSchedule(SlotConfig(slotMs = 3000, slotCount = 8, slotIndex = 2, guardMs = 250))
    private val cycleStart = 24_000L * 1000 // an exact cycle boundary

    @Test
    fun transmitsOnlyInsideOwnSlot() {
        assertEquals(24_000L, schedule.cycleMs)
        assertEquals(0, schedule.slotAt(cycleStart))
        assertEquals(2, schedule.slotAt(cycleStart + 6_000))
        assertEquals(7, schedule.slotAt(cycleStart + 23_999))

        // Before the slot: wait for it.
        assertEquals(6_000L, schedule.waitMs(cycleStart, 700))
        assertEquals(1L, schedule.waitMs(cycleStart + 5_999, 700))
        // In the slot with room to finish before the guard: go.
        assertEquals(0L, schedule.waitMs(cycleStart + 6_000, 700))
        assertEquals(0L, schedule.waitMs(cycleStart + 8_050, 700)) // ends at 8.750 = slot end - guard
        // In the slot but it would run into the guard: wait a whole cycle for the next one.
        assertEquals(24_000L - 2_051, schedule.waitMs(cycleStart + 8_051, 700))
        // After the slot.
        assertEquals(21_000L, schedule.waitMs(cycleStart + 9_000, 700))
        assertEquals(1L, schedule.waitMs(cycleStart + 24_000 + 5_999, 700))
    }

    @Test
    fun slotsOfDifferentStationsNeverOverlap() {
        val stations = (0 until 8).map { SlotSchedule(SlotConfig(3000, 8, it, 250)) }
        // Step through two cycles; at every instant at most one station may start, and whatever
        // it starts ends inside its own slot.
        for (t in 0 until 48_000 step 7) {
            val now = cycleStart + t
            val allowed = stations.filter { it.waitMs(now, 900) == 0L }
            assertTrue("two stations allowed at $t", allowed.size <= 1)
            for (s in allowed) {
                assertEquals(s.config.slotIndex, s.slotAt(now))
                assertEquals(s.config.slotIndex, s.slotAt(now + 900 + 249))
            }
        }
    }

    @Test
    fun clockErrorUpToTheGuardIsHarmless() {
        val a = SlotSchedule(SlotConfig(3000, 4, 0, 250))
        val b = SlotSchedule(SlotConfig(3000, 4, 1, 250))
        val skew = 240L // station A's clock runs 240 ms slow
        for (t in 0 until 24_000 step 5) {
            val trueNow = cycleStart + t
            if (a.waitMs(trueNow - skew, 1000) == 0L) {
                // A transmits for 1000 ms of true time starting now; B must not start during that.
                for (dt in 0..1000 step 50) assertTrue(b.waitMs(trueNow + dt, 1000) != 0L)
            }
        }
    }

    @Test
    fun oversizeTransmissionStartsOnlyAtSlotStart() {
        // 4 s of audio cannot fit a 3 s slot.
        assertEquals(0L, schedule.waitMs(cycleStart + 6_000, 4_000))
        assertEquals(0L, schedule.waitMs(cycleStart + 6_200, 4_000))
        assertTrue(schedule.waitMs(cycleStart + 6_201, 4_000) > 0)
    }

    @Test
    fun autoSlotIsStableAndInRange() {
        for (count in listOf(1, 2, 8, 13, 64)) {
            for (call in listOf("N0CALL", "W1AW-9", "KD9XYZ-15", "K1A")) {
                val s = SlotSchedule.autoSlot(call, count)
                assertTrue(s in 0 until count)
                assertEquals(s, SlotSchedule.autoSlot(call.lowercase(), count))
            }
        }
        // Different callsigns do spread out.
        val used = (0 until 200).map { SlotSchedule.autoSlot("N${it}ABC", 8) }.toSet()
        assertEquals(8, used.size)
    }

    @Test
    fun invalidConfigIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SlotConfig(slotMs = 100) }
        assertThrows(IllegalArgumentException::class.java) { SlotConfig(slotCount = 0) }
        assertThrows(IllegalArgumentException::class.java) { SlotConfig(slotCount = 4, slotIndex = 4) }
        assertThrows(IllegalArgumentException::class.java) { SlotConfig(slotMs = 1000, guardMs = 1000) }
    }

    @Test
    fun channelAccessWaitsForSlotAndForCarrier() {
        val clock = FakeClock(cycleStart)
        var busyUntil = 0L
        val access = ChannelAccess(
            slots = schedule, clock = clock, sleep = { clock.advance(it) },
        ) { clock.now < busyUntil }

        // Idle channel: released at the start of slot 2, not before.
        assertTrue(access.await(700, 30_000))
        assertEquals(cycleStart + 6_000, clock.now)

        // Someone is still transmitting when our slot opens: wait for them, then go in-slot.
        clock.now = cycleStart + 24_000
        busyUntil = cycleStart + 24_000 + 6_800
        assertTrue(access.await(700, 30_000))
        assertTrue(clock.now >= busyUntil)
        assertEquals(2, schedule.slotAt(clock.now))

        // The channel stays busy past the point where we could still finish in time: the
        // transmission moves to our slot in the next cycle.
        clock.now = cycleStart + 48_000
        busyUntil = cycleStart + 48_000 + 8_500
        assertTrue(access.await(700, 30_000))
        assertEquals(cycleStart + 72_000 + 6_000, clock.now)

        // A channel that never clears: give up.
        busyUntil = Long.MAX_VALUE
        assertFalse(access.await(700, 5_000))
    }

    @Test
    fun channelAccessWithoutSlotsIsPlainCsma() {
        val clock = FakeClock()
        val access = ChannelAccess(slotTimeMs = 100, persistence = 255, clock = clock, sleep = { clock.advance(it) }) { false }
        val t = clock.now
        assertTrue(access.await(700, 1_000))
        assertEquals(t, clock.now)
    }
}
