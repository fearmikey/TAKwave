// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.sched

import com.fearmikey.takwave.core.codec.ChatMessage
import com.fearmikey.takwave.core.codec.MarkerMessage
import com.fearmikey.takwave.core.codec.PliMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class FakeClock(var now: Long = 1_000_000L) : Clock {
    override fun nowMs(): Long = now
    fun advance(ms: Long) { now += ms }
}

class SchedulingTest {
    private fun pli(uid: String = "ANDROID-0000000000000001", latE7: Int = 415_000_000, lonE7: Int = -713_000_000, speed: Int? = 0, course: Int? = 0) =
        PliMessage(uid, "NOVA", "a-f-G-U-C", latE7, lonE7, speedDms = speed, courseDeg = course)

    private fun chat(id: String, text: String = "hi") = ChatMessage(id, "uid", "NOVA", "All Chat Rooms", "All Chat Rooms", text)
    private fun marker(uid: String, name: String = "m") = MarkerMessage(uid, "b-m-p-s-m", name, 1, 2)

    // ---- PLI scheduler ----

    @Test
    fun firstReportGoesImmediatelyThenOnBaseInterval() {
        val clock = FakeClock()
        val s = PliScheduler(PliConfig())
        s.update(pli(), clock.now)
        assertEquals(1, s.due(clock.now).size)
        // ATAK keeps reporting every 3 s without moving: nothing until the base interval.
        var sent = 0
        repeat(19) {
            clock.advance(3_000)
            s.update(pli(), clock.now)
            sent += s.due(clock.now).size
        }
        assertEquals(0, sent) // 57 s elapsed
        clock.advance(3_000)
        s.update(pli(), clock.now)
        assertEquals(1, s.due(clock.now).size) // 60 s
    }

    @Test
    fun onlyLatestReportIsKept() {
        val clock = FakeClock()
        val s = PliScheduler(PliConfig())
        s.update(pli(latE7 = 1), clock.now)
        s.update(pli(latE7 = 2), clock.now)
        s.update(pli(latE7 = 3), clock.now)
        val due = s.due(clock.now)
        assertEquals(1, due.size)
        assertEquals(3, due[0].latE7)
    }

    @Test
    fun movementTriggersEarlyReportButNotBeforeMinimum() {
        val clock = FakeClock()
        val s = PliScheduler(PliConfig())
        s.update(pli(), clock.now)
        s.due(clock.now)
        // About 111 m north, 5 s later: too soon.
        val moved = pli(latE7 = 415_010_000)
        clock.advance(5_000)
        s.update(moved, clock.now)
        assertEquals(0, s.due(clock.now).size)
        // At 15 s the minimum interval has passed.
        clock.advance(10_000)
        s.update(moved, clock.now)
        assertEquals(listOf(moved), s.due(clock.now))
        // 30 m further is under the 50 m threshold.
        clock.advance(20_000)
        s.update(pli(latE7 = 415_012_700), clock.now)
        assertEquals(0, s.due(clock.now).size)
    }

    @Test
    fun headingChangeCountsOnlyWhileMoving() {
        val clock = FakeClock()
        val s = PliScheduler(PliConfig())
        s.update(pli(speed = 20, course = 350), clock.now)
        s.due(clock.now)
        clock.advance(16_000)
        // Stationary with a wandering compass: ignored.
        s.update(pli(speed = 0, course = 90), clock.now)
        assertEquals(0, s.due(clock.now).size)
        // Moving, 350 -> 15 degrees is only a 25 degree turn across north: ignored.
        s.update(pli(speed = 20, course = 15), clock.now)
        assertEquals(0, s.due(clock.now).size)
        // Moving, 350 -> 30 is a 40 degree turn: sent.
        s.update(pli(speed = 20, course = 30), clock.now)
        assertEquals(1, s.due(clock.now).size)
    }

    @Test
    fun silentClientStopsBeingReported() {
        val clock = FakeClock()
        val s = PliScheduler(PliConfig())
        s.update(pli(), clock.now)
        assertEquals(1, s.due(clock.now).size)
        // The client disconnects: no more updates, so nothing more goes out, ever.
        repeat(20) {
            clock.advance(60_000)
            assertEquals(0, s.due(clock.now).size)
        }
    }

    @Test
    fun intervalsAreConfigurableAndUidsIndependent() {
        val clock = FakeClock()
        val s = PliScheduler(PliConfig(baseIntervalS = 10, minIntervalS = 2, distanceM = 5.0))
        s.update(pli("ANDROID-00000000000000aa"), clock.now)
        s.update(pli("ANDROID-00000000000000bb"), clock.now)
        assertEquals(2, s.due(clock.now).size)
        clock.advance(10_000)
        s.update(pli("ANDROID-00000000000000aa"), clock.now)
        assertEquals(listOf("ANDROID-00000000000000aa"), s.due(clock.now).map { it.uid })
    }

    // ---- Queue ----

    @Test
    fun queuePriorityIsChatThenMarkerThenPli() {
        val q = TxQueue()
        q.offer(pli(), 0)
        q.offer(marker("m1"), 1)
        q.offer(chat("c1"), 2)
        q.offer(chat("c2"), 3)
        q.offer(marker("m2"), 4)
        val order = generateSequence { q.poll(10) }.map { it.message.key }.toList()
        assertEquals(listOf("c1", "c2", "m1", "m2", "ANDROID-0000000000000001"), order)
        assertNull(q.poll(10))
    }

    @Test
    fun newerPliAndMarkerReplaceQueuedOnes() {
        val q = TxQueue()
        q.offer(pli(latE7 = 1), 0)
        q.offer(pli(latE7 = 2), 5)
        q.offer(marker("m1", "old"), 6)
        q.offer(marker("m1", "new"), 7)
        q.offer(chat("c1", "same id"), 8)
        q.offer(chat("c1", "same id"), 9) // chats are never collapsed
        assertEquals(4, q.size)
        assertEquals("c1", q.poll(10)!!.message.key)
        assertEquals("c1", q.poll(10)!!.message.key)
        assertEquals("new", (q.poll(10)!!.message as MarkerMessage).name)
        assertEquals(2, (q.poll(10)!!.message as PliMessage).latE7)
    }

    @Test
    fun staleEntriesAreDropped() {
        val q = TxQueue()
        q.offer(chat("old"), 0)
        q.offer(chat("fresh"), 100_000)
        assertEquals("fresh", q.poll(120_001)!!.message.key)
        assertNull(q.poll(120_001))
        assertEquals(1, q.dropped)
        // Exactly two minutes old is still good.
        q.offer(chat("edge"), 0)
        assertEquals("edge", q.poll(120_000)!!.message.key)
    }

    @Test
    fun queueIsCappedAndShedsLeastImportantFirst() {
        val q = TxQueue(maxSize = 4)
        q.offer(pli("ANDROID-0000000000000001"), 0)
        q.offer(pli("ANDROID-0000000000000002"), 1)
        q.offer(marker("m1"), 2)
        q.offer(chat("c1"), 3)
        // Full. A chat pushes out the oldest PLI.
        assertTrue(q.offer(chat("c2"), 4))
        assertEquals(4, q.size)
        // Fill with chat; a PLI is then refused.
        assertTrue(q.offer(chat("c3"), 5))
        assertTrue(q.offer(chat("c4"), 6))
        assertFalse(q.offer(pli("ANDROID-0000000000000003"), 7))
        // A fifth chat displaces the oldest chat.
        assertTrue(q.offer(chat("c5"), 8))
        val keys = generateSequence { q.poll(9) }.map { it.message.key }.toList()
        assertEquals(listOf("c2", "c3", "c4", "c5"), keys)
    }

    // ---- Loop prevention ----

    @Test
    fun rfOriginatedTrafficIsNeverRetransmitted() {
        val g = LoopGuard()
        val heardPli = pli("ANDROID-00000000000000ff")
        g.markFromRf(heardPli, 0)
        g.markFromRf(chat("rf-chat"), 0)
        // Same uid with any content: blocked.
        assertTrue(g.isFromRf(heardPli.copy(latE7 = 5), 1000))
        assertTrue(g.isFromRf(chat("rf-chat", "edited"), 1000))
        // Local traffic is unaffected.
        assertFalse(g.isFromRf(pli("ANDROID-0000000000000001"), 1000))
        assertFalse(g.isFromRf(chat("local-chat"), 1000))
        // A PLI uid does not block a marker that happens to share the string.
        assertFalse(g.isFromRf(marker("ANDROID-00000000000000ff"), 1000))
    }

    @Test
    fun receivedMarkerIsBlockedUntilEditedLocally() {
        val g = LoopGuard()
        val m = marker("m1", "cache")
        g.markFromRf(m, 0)
        assertTrue(g.isFromRf(m.copy(), 1000))
        assertFalse(g.isFromRf(m.copy(name = "cache (empty)"), 1000))
    }

    @Test
    fun loopGuardEntriesExpireAndAreBounded() {
        val g = LoopGuard(ttlMs = 1000, maxEntries = 3)
        g.markFromRf(chat("a"), 0)
        assertTrue(g.isFromRf(chat("a"), 1000))
        assertFalse(g.isFromRf(chat("a"), 1001))
        for (i in 0 until 10) g.markFromRf(chat("k$i"), 2000)
        assertFalse(g.isFromRf(chat("k0"), 2001))
        assertTrue(g.isFromRf(chat("k9"), 2001))
    }

    // ---- CSMA ----

    @Test
    fun csmaDefersWhileBusyThenTransmits() {
        val clock = FakeClock()
        var busyUntil = clock.now + 500
        var polls = 0
        val csma = Csma(100, 255, Random(1), clock, { clock.advance(it) }) { polls++; clock.now < busyUntil }
        assertTrue(csma.awaitClear(10_000))
        assertTrue(clock.now >= busyUntil)
        assertTrue(polls > 10)

        // A channel that never clears gives up at the deadline.
        busyUntil = Long.MAX_VALUE
        val start = clock.now
        assertFalse(csma.awaitClear(2_000))
        assertTrue(clock.now - start in 2_000..2_100)
    }

    @Test
    fun csmaPersistenceControlsSlotWaiting() {
        // persistence 63 => p = 0.25 per slot, so the mean wait is about three slots.
        val clock = FakeClock()
        val csma = Csma(100, 63, Random(7), clock, { clock.advance(it) }) { false }
        val start = clock.now
        val runs = 4000
        repeat(runs) { assertTrue(csma.awaitClear(60_000)) }
        val meanSlots = (clock.now - start) / 100.0 / runs
        assertTrue("mean slots waited: $meanSlots", meanSlots in 2.6..3.4)

        // persistence 255 always transmits at once; 0 almost never does.
        val always = Csma(100, 255, Random(7), clock, { clock.advance(it) }) { false }
        val t = clock.now
        assertTrue(always.awaitClear(1000))
        assertEquals(t, clock.now)
    }

    @Test
    fun geoHelpers() {
        assertEquals(111_195.0, Geo.distanceM(0.0, 0.0, 1.0, 0.0), 50.0)
        assertEquals(0.0, Geo.distanceM(41.5, -71.3, 41.5, -71.3), 1e-6)
        // Across the antimeridian: 0.2 degrees of longitude at the equator.
        assertEquals(22_239.0, Geo.distanceM(0.0, 179.9, 0.0, -179.9), 20.0)
        assertEquals(0.0, Geo.bearingDeg(0.0, 0.0, 1.0, 0.0), 0.01)
        assertEquals(90.0, Geo.bearingDeg(0.0, 0.0, 0.0, 1.0), 0.01)
        assertEquals(180.0, Geo.bearingDeg(1.0, 0.0, 0.0, 0.0), 0.01)
        assertEquals(270.0, Geo.bearingDeg(0.0, 0.0, 0.0, -1.0), 0.01)
        assertEquals(20, Geo.headingDelta(350, 10))
        assertEquals(180, Geo.headingDelta(0, 180))
    }
}
