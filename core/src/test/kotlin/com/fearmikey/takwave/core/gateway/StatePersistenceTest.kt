// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.gateway

import com.fearmikey.takwave.core.ax25.Ax25Address
import com.fearmikey.takwave.core.codec.PacketCodec
import com.fearmikey.takwave.core.codec.PayloadCrypto
import com.fearmikey.takwave.core.codec.PliMessage
import com.fearmikey.takwave.core.cot.CotParser
import com.fearmikey.takwave.core.sched.FakeClock
import com.fearmikey.takwave.core.sched.RepeatConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class StatePersistenceTest {
    private val clock = FakeClock(1_800_000_000_000L)

    private fun station(call: String, config: RepeatConfig = RepeatConfig(), codec: PacketCodec = PacketCodec()) =
        Gateway(clock, Ax25Address(call), codec, repeatConfig = config, random = Random(3))

    private fun golden(name: String): String =
        javaClass.getResourceAsStream("/cot/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }.trimEnd()

    private fun drain(g: Gateway): List<TxItem> = generateSequence { g.pollFrame() }.toList()

    private fun pliFrom(call: String, n: Int): ByteArray {
        val g = station(call)
        assertTrue(g.enqueue(PliMessage("ANDROID-000000000000000$n", "S$n", "a-f-G-U-C", 415_000_000 + n, -713_000_000)))
        return drain(g).single().frame
    }

    @Test
    fun heldMessagesSurviveARestart() {
        val a = station("N0AAA")
        a.onRfFrame(pliFrom("N0BBB", 2))
        a.onClientEvent(golden("chat_all_atak.xml"))
        a.onClientEvent(golden("marker_spot_atak.xml"))
        assertEquals(2, drain(a).size)
        assertEquals(2, a.heldCount)
        val state = a.saveState()

        // Ten minutes later the app starts again.
        clock.advance(10 * 60_000L)
        val again = station("N0AAA")
        assertEquals(RestoreResult(held = 2, seen = 2), again.restoreState(state))
        assertEquals(2, again.heldCount)

        // B was in range when they were sent, so hearing B again triggers nothing...
        again.onRfFrame(pliFrom("N0BBB", 2))
        clock.advance(10_000)
        assertEquals(0, drain(again).size)
        // ...but C, newly in range, gets both, with the time they were written.
        again.onRfFrame(pliFrom("N0CCC", 3))
        clock.advance(10_000)
        val resent = drain(again)
        assertEquals(listOf(TxKind.RESEND, TxKind.RESEND), resent.map { it.kind })
        val c = station("N0CCC")
        val event = CotParser.parse(c.onRfFrame(resent.first().frame).xml!!)!!
        assertTrue(event.timeMs <= clock.now - 10 * 60_000L)
    }

    @Test
    fun repeatsAlreadySeenAreNotShownAgainAfterARestart() {
        val a = station("N0AAA")
        val relay = station("N0RLY", RepeatConfig(relay = true))
        a.onClientEvent(golden("chat_all_atak.xml"))
        val original = drain(a).single().frame
        val b = station("N0BBB")
        assertNotNull(b.onRfFrame(original).xml)
        relay.onRfFrame(original)
        clock.advance(10_000)
        val relayed = drain(relay).single().frame

        val restarted = station("N0BBB")
        restarted.restoreState(b.saveState())
        assertNull(restarted.onRfFrame(relayed).xml)
    }

    @Test
    fun aRelayKeepsWhatItHeldAndItsBudget() {
        val relay = station("N0RLY", RepeatConfig(relay = true, budgetPer10Min = 1))
        val a = station("N0AAA")
        a.onClientEvent(golden("chat_all_atak.xml"))
        relay.onRfFrame(drain(a).single().frame)
        a.onClientEvent(golden("marker_spot_atak.xml"))
        relay.onRfFrame(drain(a).single().frame)
        clock.advance(10_000)
        // Budget of one: the chat goes, the marker waits.
        assertEquals(1, drain(relay).size)

        val restarted = station("N0RLY", RepeatConfig(relay = true, budgetPer10Min = 1))
        assertEquals(2, restarted.restoreState(relay.saveState()).held)
        clock.advance(10_000)
        assertEquals("the budget spent before the restart still counts", 0, drain(restarted).size)
        clock.advance(10 * 60_000L)
        assertEquals(1, drain(restarted).size)
    }

    @Test
    fun switchedOffKindsAreNotRestored() {
        val relay = station("N0RLY", RepeatConfig(relay = true))
        val a = station("N0AAA")
        a.onClientEvent(golden("chat_all_atak.xml"))
        relay.onRfFrame(drain(a).single().frame)
        relay.onClientEvent(golden("marker_spot_atak.xml"))
        drain(relay)
        assertEquals(2, relay.heldCount)
        val noRelay = station("N0RLY", RepeatConfig(relay = false))
        assertEquals(1, noRelay.restoreState(relay.saveState()).held)
    }

    @Test
    fun heldMessagesAreDroppedWhenEncryptionWasSwitched() {
        val clear = station("WQAA11")
        clear.onClientEvent(golden("chat_all_atak.xml"))
        drain(clear)
        val encrypted = station("WQAA11", codec = PacketCodec(PayloadCrypto.fromPassphrase("fleet key".toCharArray())))
        val result = encrypted.restoreState(clear.saveState())
        assertEquals(0, result.held)
        assertEquals(1, result.seen)
        assertTrue(result.note!!.contains("switched on"))
        assertEquals(0, encrypted.heldCount)
    }

    @Test
    fun heldMessagesAreDroppedWhenCallsignHidingWasSwitched() {
        fun codec() = PacketCodec(PayloadCrypto.fromPassphrase("fleet key".toCharArray()))
        val hiding = Gateway(clock, Ax25Address("WQAA11"), codec(), random = Random(3), hideSource = true)
        hiding.onClientEvent(golden("chat_all_atak.xml"))
        drain(hiding)
        val state = hiding.saveState()

        val shown = station("WQAA11", codec = codec())
        val result = shown.restoreState(state)
        assertEquals(0, result.held)
        assertTrue(result.note!!.contains("callsign hiding was switched off"))

        // Same setting again: kept.
        val again = Gateway(clock, Ax25Address("WQAA11"), codec(), random = Random(3), hideSource = true)
        assertEquals(RestoreResult(held = 1, seen = 1), again.restoreState(state))
    }

    @Test
    fun aDamagedFileChangesNothing() {
        val a = station("N0AAA")
        a.onClientEvent(golden("chat_all_atak.xml"))
        drain(a)
        val state = a.saveState()
        for (cut in listOf(0, 3, 6, state.size / 2, state.size - 1)) {
            val fresh = station("N0AAA")
            val result = fresh.restoreState(state.copyOf(cut))
            assertEquals(0, result.held)
            assertNotNull(result.note)
            assertEquals(0, fresh.heldCount)
        }
        val garbage = ByteArray(200) { (it * 37).toByte() }
        assertNotNull(station("N0AAA").restoreState(garbage).note)
    }

    @Test
    fun oldMessagesExpireAcrossARestart() {
        val a = station("N0AAA")
        a.onClientEvent(golden("chat_all_atak.xml"))
        drain(a)
        val state = a.saveState()
        clock.advance(31 * 60_000L)
        assertEquals(0, station("N0AAA").restoreState(state).held)
    }
}
