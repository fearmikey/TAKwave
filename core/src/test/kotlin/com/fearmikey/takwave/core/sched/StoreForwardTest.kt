// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.sched

import com.fearmikey.takwave.core.codec.ChatMessage
import com.fearmikey.takwave.core.codec.MarkerMessage
import com.fearmikey.takwave.core.codec.PliMessage
import com.fearmikey.takwave.core.codec.TakMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class StoreForwardTest {
    private val clock = FakeClock()
    private val everyone: (TakMessage) -> String? = { null }

    private fun chat(id: String) = ChatMessage(id, "uid", "NOVA", "All Chat Rooms", "All Chat Rooms", "hi")
    private fun marker(uid: String, name: String = "m") = MarkerMessage(uid, "b-m-p-s-m", name, 1, 2)
    private fun store(config: RepeatConfig = RepeatConfig()) = RepeatStore(config, Random(1))

    /** Longer than any start delay, shorter than every limit under test. */
    private val settle = RepeatStore.RELAY_DELAY_MS + RepeatStore.RELAY_JITTER_MS

    @Test
    fun ownMessageIsRepeatedOnceForAStationThatWasNotInRange() {
        val s = store()
        val m = chat("1")
        s.onOwnSent(m, setOf("B"), clock.now)
        // B was in range for the original: hearing it again changes nothing.
        s.onStationHeard("B", clock.now, everyone)
        clock.advance(10 * 60_000)
        assertNull(s.nextDue(clock.now, setOf("B")))

        s.onStationHeard("C", clock.now, everyone)
        assertNull("sent before the start delay", s.nextDue(clock.now, setOf("B", "C")))
        clock.advance(settle)
        val r = s.nextDue(clock.now, setOf("B", "C"))!!
        assertSame(m, r.message)
        assertFalse(r.relayed)
        // C has had its copy.
        s.onStationHeard("C", clock.now, everyone)
        clock.advance(10 * 60_000)
        assertNull(s.nextDue(clock.now, setOf("C")))
    }

    @Test
    fun repeatsOfOneMessageAreSpacedByTheMinimumGap() {
        val s = store(RepeatConfig(minGapMs = 120_000))
        s.onOwnSent(chat("1"), emptySet(), clock.now)
        clock.advance(10_000)
        s.onStationHeard("B", clock.now, everyone)
        clock.advance(100_000) // 110 s after the original
        assertNull(s.nextDue(clock.now, setOf("B")))
        clock.advance(10_000)
        assertEquals("1", s.nextDue(clock.now, setOf("B"))!!.message.key)
    }

    @Test
    fun aMessageIsRepeatedAtMostMaxRepeatsTimes() {
        val s = store(RepeatConfig(maxRepeats = 2, minGapMs = 60_000, budgetPer10Min = 100))
        s.onOwnSent(chat("1"), emptySet(), clock.now)
        var sent = 0
        for (station in listOf("B", "C", "D", "E")) {
            clock.advance(61_000)
            s.onStationHeard(station, clock.now, everyone)
            clock.advance(settle)
            if (s.nextDue(clock.now, setOf(station)) != null) sent++
        }
        assertEquals(2, sent)
        assertEquals(0, s.size)
    }

    @Test
    fun aHeldMessageExpires() {
        val s = store(RepeatConfig(holdMs = 30 * 60_000L))
        s.onOwnSent(chat("1"), emptySet(), clock.now)
        clock.advance(30 * 60_000L + 1)
        s.onStationHeard("B", clock.now, everyone)
        clock.advance(settle)
        assertNull(s.nextDue(clock.now, setOf("B")))
        assertEquals(0, s.size)
    }

    @Test
    fun theTenMinuteBudgetCapsAllRepeatsTogether() {
        val s = store(RepeatConfig(budgetPer10Min = 2))
        for (i in 1..4) s.onOwnSent(chat("$i"), emptySet(), clock.now)
        clock.advance(5 * 60_000)
        s.onStationHeard("B", clock.now, everyone)
        clock.advance(settle)
        var sent = generateSequence { s.nextDue(clock.now, setOf("B")) }.count()
        assertEquals(2, sent)
        // The rest go out once the window has moved on.
        clock.advance(RepeatStore.BUDGET_WINDOW_MS + 1)
        sent = generateSequence { s.nextDue(clock.now, setOf("B")) }.count()
        assertEquals(2, sent)
    }

    @Test
    fun aDirectChatIsOnlyRepeatedForItsRecipient() {
        val s = store()
        s.onOwnSent(chat("1"), emptySet(), clock.now)
        val onlyB: (TakMessage) -> String? = { "B" }
        clock.advance(5 * 60_000)
        s.onStationHeard("C", clock.now, onlyB)
        clock.advance(settle)
        assertNull(s.nextDue(clock.now, setOf("C")))
        s.onStationHeard("B", clock.now, onlyB)
        clock.advance(settle)
        assertEquals("1", s.nextDue(clock.now, setOf("B", "C"))!!.message.key)
    }

    @Test
    fun onlyTheNewestCopyOfAMarkerIsHeldAndPositionsNever() {
        val s = store()
        s.onOwnSent(PliMessage("u", "NOVA", "a-f-G-U-C", 1, 2), emptySet(), clock.now)
        assertEquals(0, s.size)
        s.onOwnSent(marker("m1", "old"), emptySet(), clock.now)
        s.onOwnSent(marker("m1", "new"), emptySet(), clock.now)
        assertEquals(1, s.size)
        clock.advance(5 * 60_000)
        s.onStationHeard("B", clock.now, everyone)
        clock.advance(settle)
        assertEquals("new", (s.nextDue(clock.now, setOf("B"))!!.message as MarkerMessage).name)
        // Someone else's edit of that marker makes our copy out of date.
        s.onRf(marker("m1", "edited by B"), "B", isNew = true, mayRelay = true, present = setOf("B"), nowMs = clock.now)
        assertEquals(0, s.size)
    }

    @Test
    fun relayPassesAMessageOnOnceThenOnlyForNewStations() {
        val s = store(RepeatConfig(relay = true))
        s.onRf(chat("1"), "A", isNew = true, mayRelay = true, present = setOf("A"), nowMs = clock.now)
        assertNull("relayed with no delay", s.nextDue(clock.now, setOf("A")))
        clock.advance(settle)
        assertTrue(s.nextDue(clock.now, setOf("A", "B"))!!.relayed)
        clock.advance(5 * 60_000)
        s.onStationHeard("A", clock.now, everyone)
        s.onStationHeard("B", clock.now, everyone)
        clock.advance(settle)
        assertNull(s.nextDue(clock.now, setOf("A", "B")))
        s.onStationHeard("D", clock.now, everyone)
        clock.advance(settle)
        assertTrue(s.nextDue(clock.now, setOf("D"))!!.relayed)
    }

    @Test
    fun relayIsOffByDefaultAndNeverTakesWhatItMayNot() {
        val off = store()
        off.onRf(chat("1"), "A", isNew = true, mayRelay = true, present = setOf("A"), nowMs = clock.now)
        assertEquals(0, off.size)
        val on = store(RepeatConfig(relay = true))
        // Already relayed by someone else, or already seen: not ours to pass on.
        on.onRf(chat("1"), "A", isNew = true, mayRelay = false, present = setOf("A"), nowMs = clock.now)
        on.onRf(chat("2"), "A", isNew = false, mayRelay = true, present = setOf("A"), nowMs = clock.now)
        assertEquals(0, on.size)
    }

    @Test
    fun hearingAnotherCopyCancelsAPendingRepeat() {
        val s = store(RepeatConfig(relay = true))
        s.onRf(chat("1"), "A", isNew = true, mayRelay = true, present = setOf("A"), nowMs = clock.now)
        clock.advance(1_000)
        // Another relay got there first.
        s.onRf(chat("1"), "C", isNew = false, mayRelay = false, present = setOf("A", "C"), nowMs = clock.now)
        clock.advance(settle)
        assertNull(s.nextDue(clock.now, setOf("A", "C")))
        assertEquals(1, s.size)
    }

    @Test
    fun theStoreHoldsALimitedNumberOfMessages() {
        val s = store(RepeatConfig(capacity = 3))
        for (i in 1..10) s.onOwnSent(chat("$i"), emptySet(), clock.now)
        assertEquals(3, s.size)
    }

    @Test
    fun seenCacheTellsRepeatsFromNewTraffic() {
        val seen = SeenCache()
        val t0 = clock.now
        assertEquals(SeenResult.NEW, seen.accept(chat("1"), t0, clock.now))
        assertEquals(SeenResult.REPEAT, seen.accept(chat("1"), t0, clock.now))
        // Position reports are never repeats.
        val pli = PliMessage("u", "NOVA", "a-f-G-U-C", 1, 2)
        assertEquals(SeenResult.NEW, seen.accept(pli, t0, clock.now))
        assertEquals(SeenResult.NEW, seen.accept(pli, t0, clock.now))
    }

    @Test
    fun anOlderMarkerVersionNeverReplacesANewerOne() {
        val seen = SeenCache()
        val t1 = clock.now
        assertEquals(SeenResult.NEW, seen.accept(marker("m", "v1"), t1, clock.now))
        clock.advance(60_000)
        val t2 = clock.now
        assertEquals(SeenResult.NEW, seen.accept(marker("m", "v2"), t2, clock.now))
        assertEquals(SeenResult.REPEAT, seen.accept(marker("m", "v2"), t2, clock.now))
        // A relay that missed the edit arrives later with v1, aged to its real time.
        clock.advance(5 * 60_000)
        assertEquals(SeenResult.OUTDATED, seen.accept(marker("m", "v1"), t1, clock.now))
        // The author moving it back is a new edit, written now.
        assertEquals(SeenResult.NEW, seen.accept(marker("m", "v1"), clock.now, clock.now))
    }

    @Test
    fun aHeldMarkerIsKeptWhenAnOlderVersionIsHeard() {
        val s = store()
        s.onOwnSent(marker("m1", "new"), emptySet(), clock.now)
        s.onRf(marker("m1", "old"), "B", isNew = false, mayRelay = false, present = setOf("B"), nowMs = clock.now, originMs = clock.now - 60_000)
        assertEquals(1, s.size)
    }

    @Test
    fun repeatsCarryTheOriginalTime() {
        val s = store(RepeatConfig(relay = true))
        val written = clock.now - 90_000
        s.onRf(chat("1"), "A", isNew = true, mayRelay = true, present = setOf("A"), nowMs = clock.now, originMs = written)
        clock.advance(settle)
        assertEquals(written, s.nextDue(clock.now, setOf("A"))!!.originMs)
    }

    @Test
    fun seenCacheForgetsAfterItsLifetime() {
        val seen = SeenCache(ttlMs = 60_000)
        assertEquals(SeenResult.NEW, seen.accept(chat("1"), clock.now, clock.now))
        clock.advance(60_001)
        assertEquals(SeenResult.NEW, seen.accept(chat("1"), clock.now, clock.now))
    }
}
