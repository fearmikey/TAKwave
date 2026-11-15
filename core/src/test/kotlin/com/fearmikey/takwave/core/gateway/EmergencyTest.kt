// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.gateway

import com.fearmikey.takwave.core.ax25.Ax25Address
import com.fearmikey.takwave.core.ax25.Ax25Frame
import com.fearmikey.takwave.core.codec.DecodeResult
import com.fearmikey.takwave.core.codec.EmergencyKind
import com.fearmikey.takwave.core.codec.EmergencyMessage
import com.fearmikey.takwave.core.codec.PacketCodec
import com.fearmikey.takwave.core.codec.PliMessage
import com.fearmikey.takwave.core.cot.Classified
import com.fearmikey.takwave.core.cot.CotMapper
import com.fearmikey.takwave.core.cot.CotParser
import com.fearmikey.takwave.core.sched.FakeClock
import com.fearmikey.takwave.core.sched.RepeatConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class EmergencyTest {
    private val clock = FakeClock()
    private val codec = PacketCodec()

    private fun station(call: String, config: RepeatConfig = RepeatConfig()) =
        Gateway(clock, Ax25Address(call), repeatConfig = config, random = Random(2))

    private val a = station("N0AAA")
    private val b = station("N0BBB")
    private val c = station("N0CCC")

    private fun golden(name: String): String =
        javaClass.getResourceAsStream("/cot/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }.trimEnd()

    private fun drain(g: Gateway): List<TxItem> = generateSequence { g.pollFrame() }.toList()

    private fun decoded(item: TxItem): DecodeResult.Ok = codec.decode(Ax25Frame.decode(item.frame)!!.info) as DecodeResult.Ok

    private fun pliFrom(g: Gateway, n: Int): ByteArray {
        assertTrue(g.enqueue(PliMessage("ANDROID-000000000000000$n", "S$n", "a-f-G-U-C", 415_000_000 + n, -713_000_000)))
        return drain(g).single().frame
    }

    private val alert: EmergencyMessage
        get() = (CotMapper.classify(CotParser.parse(golden("emergency_911_atak.xml"))!!) as Classified.Send).message as EmergencyMessage

    @Test
    fun atakAlertIsClassifiedAndSurvivesTheAir() {
        val m = alert
        assertEquals("ANDROID-589520ccfcd20f01-9-1-1", m.uid)
        assertEquals("ANDROID-589520ccfcd20f01", m.senderUid)
        assertEquals(EmergencyKind.ALERT_911, m.kind)
        assertEquals("NOVA-Alert", m.callsign)
        assertEquals("NOVA", m.text)
        assertEquals(415_012_345, m.latE7)
        assertEquals(12, m.haeM)

        val payload = codec.encode(m)
        // The conventional uid is not sent twice: header, packed sender uid, kind, flags, two strings, position.
        assertEquals(2 + 9 + 1 + 1 + 11 + 5 + 10, payload.size)
        assertEquals(m, (codec.decode(payload) as DecodeResult.Ok).message)

        val cancel = CotMapper.classify(CotParser.parse(golden("emergency_cancel_atak.xml"))!!) as Classified.Send
        assertEquals(EmergencyKind.CANCEL, (cancel.message as EmergencyMessage).kind)
        assertEquals(m.uid, cancel.message.key)
    }

    @Test
    fun anUnusualUidIsSentLiterally() {
        val m = alert.copy(uid = "alert-from-wintak-7")
        assertEquals(m, (codec.decode(codec.encode(m)) as DecodeResult.Ok).message)
    }

    @Test
    fun anUnknownAlertKindIsShownRatherThanDropped() {
        val payload = codec.encode(alert)
        // Byte 2 is the uid kind, 3-10 the packed uid, 11 the alert kind.
        payload[11] = 99
        assertEquals(EmergencyKind.CUSTOM, ((codec.decode(payload) as DecodeResult.Ok).message as EmergencyMessage).kind)
    }

    @Test
    fun rebuiltAlertLooksLikeAtaksAndIsNotSentBack() {
        a.onClientEvent(golden("emergency_911_atak.xml"))
        val rx = b.onRfFrame(drain(a).single().frame)
        val xml = rx.xml!!
        val e = CotParser.parse(xml)!!
        assertEquals("b-a-o-tbl", e.type)
        assertEquals("ANDROID-589520ccfcd20f01-9-1-1", e.uid)
        assertEquals("ANDROID-589520ccfcd20f01", e.linkUid)
        assertEquals("NOVA-Alert", e.callsign)
        val em = e.emergency!!
        assertEquals("911 Alert", em.type)
        assertEquals("NOVA", em.text)
        assertFalse(em.cancel)
        assertTrue(e.fromRf)
        // ATAK echoing it back does not put it on the air again.
        assertFalse(b.onClientEvent(xml).acceptedForRf)
        assertEquals(0, drain(b).size)

        val h = b.heardStations().single()
        assertEquals("NOVA", h.takCallsign)
        assertEquals(41.5012345, h.lat!!, 1e-7)
        assertEquals("N0AAA", b.heardAlerts().single().source)
    }

    @Test
    fun alertsGoBeforeEverythingElse() {
        a.onClientEvent(golden("chat_all_atak.xml"))
        a.onClientEvent(golden("marker_spot_atak.xml"))
        a.onClientEvent(golden("emergency_911_atak.xml"))
        val sent = drain(a)
        assertEquals(3, sent.size)
        assertTrue(sent.first().message is EmergencyMessage)
    }

    @Test
    fun anActiveAlertIsRepeatedUntilCancelled() {
        assertTrue(a.onClientEvent(golden("emergency_911_atak.xml")).acceptedForRf)
        val first = drain(a).single()
        assertNotNull(b.onRfFrame(first.frame).xml)
        assertEquals(1, a.ownAlerts().size)

        // ATAK reporting the same alert again does not add transmissions.
        clock.advance(5_000)
        a.onClientEvent(golden("emergency_911_atak.xml"))
        assertEquals(0, drain(a).size)

        clock.advance(25_000)
        val repeat = drain(a).single()
        assertEquals(TxKind.NEW, repeat.kind)
        // The author's repeats keep the alert fresh in ATAK, so they are delivered again.
        assertNotNull(b.onRfFrame(repeat.frame).xml)

        // A moved position goes out with the next repeat.
        val moved = golden("emergency_911_atak.xml").replace("lat=\"41.5012345\"", "lat=\"41.6\"")
        a.onClientEvent(moved)
        clock.advance(30_000)
        assertEquals(416_000_000, (decoded(drain(a).single()).message as EmergencyMessage).latE7)

        // The cancel goes out at once, replaces the alert, and is repeated twice more.
        clock.advance(10_000)
        a.onClientEvent(golden("emergency_cancel_atak.xml"))
        val cancels = ArrayList<TxItem>()
        repeat(5) {
            cancels += drain(a)
            clock.advance(30_000)
        }
        assertEquals(3, cancels.size)
        assertTrue(cancels.all { !(it.message as EmergencyMessage).active })
        assertTrue(a.ownAlerts().isEmpty())

        val rx = b.onRfFrame(cancels.first().frame)
        assertTrue(rx.xml!!.contains("<emergency cancel=\"true\">NOVA</emergency>"))
        assertTrue(b.heardAlerts().isEmpty())

        // ATAK repeating its cancel does not start the count again.
        a.onClientEvent(golden("emergency_cancel_atak.xml"))
        assertEquals(0, drain(a).size)
    }

    @Test
    fun anAlertThatTheClientStoppedReportingIsDropped() {
        a.onClientEvent(golden("emergency_911_atak.xml"))
        assertEquals(1, drain(a).size)
        clock.advance(31 * 60_000L)
        assertEquals(0, drain(a).size)
        assertTrue(a.ownAlerts().isEmpty())
    }

    @Test
    fun relaysPassAlertsOnAndACancelCannotBeUndone() {
        val relay = station("N0RLY", RepeatConfig(relay = true))
        a.onClientEvent(golden("emergency_911_atak.xml"))
        val original = drain(a).single().frame
        assertNotNull(relay.onRfFrame(original).xml)
        assertNotNull(b.onRfFrame(original).xml)
        clock.advance(10_000)
        val relayed = drain(relay).single()
        assertEquals(TxKind.RELAY, relayed.kind)
        assertEquals(1, decoded(relayed).hops)

        // C only hears the relay.
        assertNotNull(c.onRfFrame(relayed.frame).xml)
        assertTrue(c.heardAlerts().single().relayed)
        // B already has it from the author: a relayed copy is not delivered again.
        assertNull(b.onRfFrame(relayed.frame).xml)

        // The author cancels; C hears the cancel, the relay does not.
        clock.advance(10_000)
        a.onClientEvent(golden("emergency_cancel_atak.xml"))
        val cancel = drain(a).single { !(it.message as EmergencyMessage).active }
        assertNotNull(c.onRfFrame(cancel.frame).xml)
        assertTrue(c.heardAlerts().isEmpty())

        // Much later a new station shows up near the relay, which passes on its old copy of the alert.
        clock.advance(3 * 60_000L)
        relay.onRfFrame(pliFrom(station("N0DDD"), 4))
        clock.advance(10_000)
        val stale = drain(relay).single()
        val atC = c.onRfFrame(stale.frame)
        assertNull("an old alert must not undo the cancel", atC.xml)
        assertEquals(RxKind.REPEAT, atC.kind)
        assertTrue(c.heardAlerts().isEmpty())
    }

    @Test
    fun aRelayNeverRelaysItsOwnAlertOrARelayedOne() {
        val relay1 = station("N0RL1", RepeatConfig(relay = true))
        val relay2 = station("N0RL2", RepeatConfig(relay = true))
        relay1.onClientEvent(golden("emergency_911_atak.xml"))
        assertEquals(1, drain(relay1).size)
        assertEquals(0, relay1.heldCount)

        a.onClientEvent(golden("emergency_911_atak.xml").replace("ANDROID-589520ccfcd20f01", "ANDROID-0000000000000042"))
        relay1.onRfFrame(drain(a).single().frame)
        clock.advance(10_000)
        val relayed = drain(relay1).filter { it.kind == TxKind.RELAY }.single()
        assertNotNull(relay2.onRfFrame(relayed.frame).xml)
        clock.advance(10_000)
        assertTrue(drain(relay2).isEmpty())
    }
}
