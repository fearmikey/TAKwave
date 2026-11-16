// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.gateway

import com.fearmikey.takwave.core.ax25.Ax25Address
import com.fearmikey.takwave.core.ax25.Ax25Frame
import com.fearmikey.takwave.core.codec.ChatMessage
import com.fearmikey.takwave.core.codec.DecodeResult
import com.fearmikey.takwave.core.codec.PacketCodec
import com.fearmikey.takwave.core.codec.PayloadCrypto
import com.fearmikey.takwave.core.codec.PliMessage
import com.fearmikey.takwave.core.codec.Wire
import com.fearmikey.takwave.core.cot.CotParser
import com.fearmikey.takwave.core.sched.FakeClock
import com.fearmikey.takwave.core.sched.RepeatConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class StoreForwardGatewayTest {
    private val clock = FakeClock()
    private val relayOn = RepeatConfig(relay = true)

    private fun station(call: String, config: RepeatConfig = RepeatConfig(), codec: PacketCodec = PacketCodec()) =
        Gateway(clock, Ax25Address(call), codec, repeatConfig = config, random = Random(1))

    private val a = station("N0AAA")
    private val b = station("N0BBB")
    private val c = station("N0CCC")

    private fun golden(name: String): String =
        javaClass.getResourceAsStream("/cot/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }.trimEnd()

    private fun drain(g: Gateway): List<TxItem> = generateSequence { g.pollFrame() }.toList()

    /** One position report from [g], as a frame. Each station reports its own uid. */
    private fun pliFrom(g: Gateway, n: Int): ByteArray {
        assertTrue(g.enqueue(PliMessage("ANDROID-000000000000000$n", "S$n", "a-f-G-U-C", 415_000_000 + n, -713_000_000)))
        return drain(g).single().frame
    }

    @Test
    fun ownChatIsSentAgainWhenAStationComesIntoRange() {
        a.onRfFrame(pliFrom(b, 2))
        a.onClientEvent(golden("chat_all_atak.xml"))
        val first = drain(a).single()
        assertEquals(TxKind.NEW, first.kind)
        assertNotNull(b.onRfFrame(first.frame).xml)
        assertEquals(1, a.heldCount)

        // Nothing is repeated while only the stations that were in range are heard.
        clock.advance(5 * 60_000)
        a.onRfFrame(pliFrom(b, 2))
        clock.advance(10_000)
        assertEquals(0, drain(a).size)

        // C shows up.
        a.onRfFrame(pliFrom(c, 3))
        assertEquals("repeated without waiting", 0, drain(a).size)
        clock.advance(10_000)
        val again = drain(a).single()
        assertEquals(TxKind.RESEND, again.kind)
        assertEquals("N0AAA", Ax25Frame.decode(again.frame)!!.source.toString())
        assertEquals("Rally at <CP2> & hold", CotParser.parse(c.onRfFrame(again.frame).xml!!)!!.remarks)
        // B already has it: not delivered to B's TAK clients a second time.
        val repeatAtB = b.onRfFrame(again.frame)
        assertNull(repeatAtB.xml)
        assertEquals("already received", repeatAtB.note)

        // C has it now; hearing C again triggers nothing.
        clock.advance(5 * 60_000)
        a.onRfFrame(pliFrom(c, 3))
        clock.advance(10_000)
        assertEquals(0, drain(a).size)
    }

    @Test
    fun resendCanBeSwitchedOff() {
        val quiet = station("N0QQQ", RepeatConfig(resendOwn = false))
        quiet.onClientEvent(golden("chat_all_atak.xml"))
        assertEquals(1, drain(quiet).size)
        clock.advance(5 * 60_000)
        quiet.onRfFrame(pliFrom(c, 3))
        clock.advance(10_000)
        assertEquals(0, drain(quiet).size)
        assertEquals(0, quiet.heldCount)
    }

    @Test
    fun relayCarriesChatBetweenStationsThatCannotHearEachOther() {
        val relay = station("N0RLY", relayOn)
        a.onClientEvent(golden("chat_all_atak.xml"))
        val original = drain(a).single().frame
        assertNotNull(relay.onRfFrame(original).xml)
        assertEquals("relayed without waiting", 0, drain(relay).size)
        clock.advance(10_000)
        val item = drain(relay).single()
        assertEquals(TxKind.RELAY, item.kind)

        // It goes out under the relay's own callsign, marked as relayed once.
        val frame = Ax25Frame.decode(item.frame)!!
        assertEquals("N0RLY", frame.source.toString())
        assertEquals(1, (PacketCodec().decode(frame.info) as DecodeResult.Ok).hops)

        val atB = b.onRfFrame(item.frame)
        assertTrue(atB.relayed)
        assertEquals("Rally at <CP2> & hold", CotParser.parse(atB.xml!!)!!.remarks)
        // The relay is in B's heard list, but not under the author's TAK callsign.
        assertNull(b.heardStations().single().takCallsign)

        // The author hears the relay: it is not shown its own message again.
        assertNull(a.onRfFrame(item.frame).xml)
        // And the relay does not send it a second time for the stations that were there.
        clock.advance(5 * 60_000)
        assertEquals(0, drain(relay).size)
    }

    @Test
    fun aRelayedPacketIsNeverRelayedAgain() {
        val relay1 = station("N0RL1", relayOn)
        val relay2 = station("N0RL2", relayOn)
        a.onClientEvent(golden("chat_all_atak.xml"))
        relay1.onRfFrame(drain(a).single().frame)
        clock.advance(10_000)
        val relayed = drain(relay1).single().frame
        // relay2 never heard the author, only the relay.
        assertNotNull(relay2.onRfFrame(relayed).xml)
        clock.advance(30 * 60_000)
        relay2.onRfFrame(pliFrom(c, 3))
        clock.advance(10_000)
        assertEquals(0, drain(relay2).size)
        assertEquals(0, relay2.heldCount)
    }

    @Test
    fun twoRelaysDoNotBothPassOnTheSameMessage() {
        val relay1 = station("N0RL1", relayOn)
        val relay2 = station("N0RL2", relayOn)
        a.onClientEvent(golden("chat_all_atak.xml"))
        val original = drain(a).single().frame
        relay1.onRfFrame(original)
        relay2.onRfFrame(original)
        clock.advance(10_000)
        val relayed = drain(relay1).single().frame
        // relay2 hears relay1's copy before its own turn.
        assertNull(relay2.onRfFrame(relayed).xml)
        assertEquals(0, drain(relay2).size)
    }

    @Test
    fun positionReportsAreNeverRelayedOrHeld() {
        val relay = station("N0RLY", relayOn)
        assertNotNull(relay.onRfFrame(pliFrom(a, 1)).xml)
        clock.advance(10_000)
        assertEquals(0, drain(relay).size)
        assertEquals(0, relay.heldCount)
    }

    @Test
    fun aStationWithoutRelayNeverPassesAnythingOn() {
        a.onClientEvent(golden("chat_all_atak.xml"))
        a.onClientEvent(golden("marker_spot_atak.xml"))
        for (item in drain(a)) assertNotNull(b.onRfFrame(item.frame).xml)
        clock.advance(10_000)
        b.onRfFrame(pliFrom(c, 3))
        clock.advance(10_000)
        assertEquals(0, drain(b).size)
        assertEquals(0, b.heldCount)
    }

    @Test
    fun anEncryptingRelayDoesNotPassOnClearTraffic() {
        val key = "part 90 fleet key"
        val relay = station("WQRL11", relayOn, PacketCodec(PayloadCrypto.fromPassphrase(key.toCharArray())))
        a.onClientEvent(golden("chat_all_atak.xml"))
        assertNotNull(relay.onRfFrame(drain(a).single().frame).xml)
        clock.advance(10_000)
        assertEquals(0, drain(relay).size)

        // Encrypted traffic is relayed encrypted. (A fresh relay: the first one has already seen this message id.)
        val relay2 = station("WQRL22", relayOn, PacketCodec(PayloadCrypto.fromPassphrase(key.toCharArray())))
        val sender = station("WQAA11", codec = PacketCodec(PayloadCrypto.fromPassphrase(key.toCharArray())))
        sender.onClientEvent(golden("chat_all_atak.xml"))
        assertNotNull(relay2.onRfFrame(drain(sender).single().frame).xml)
        clock.advance(10_000)
        val item = drain(relay2).single()
        val info = Ax25Frame.decode(item.frame)!!.info
        assertTrue(info[1].toInt() and Wire.FLAG_ENCRYPTED != 0)
        assertNull(b.onRfFrame(item.frame).xml)
    }

    @Test
    fun hiddenSourceTrafficIsOnlyRelayedByAHidingRelay() {
        val key = "part 90 fleet key"
        fun codec() = PacketCodec(PayloadCrypto.fromPassphrase(key.toCharArray()))
        val sender = Gateway(clock, Ax25Address("WQAA11"), codec(), repeatConfig = RepeatConfig(), random = Random(1), hideSource = true)
        sender.onClientEvent(golden("chat_all_atak.xml"))
        val frame = drain(sender).single().frame

        // An encrypting relay that shows its callsign would reveal more than the author chose to: no relay.
        val openRelay = station("WQRL11", relayOn, codec())
        assertNotNull(openRelay.onRfFrame(frame).xml)
        clock.advance(10_000)
        assertEquals(0, drain(openRelay).size)

        // A hiding relay passes it on, with its own callsign sealed inside.
        val hidingRelay = Gateway(clock, Ax25Address("WQRL22"), codec(), repeatConfig = relayOn, random = Random(1), hideSource = true)
        assertNotNull(hidingRelay.onRfFrame(frame).xml)
        clock.advance(10_000)
        val item = drain(hidingRelay).single()
        assertEquals(TxKind.RELAY, item.kind)
        assertEquals(Gateway.HIDDEN_SOURCE, Ax25Frame.decode(item.frame)!!.source)
        val heard = station("WQCC33", codec = codec()).onRfFrame(item.frame)
        assertEquals("WQRL22", heard.source)
        assertTrue(heard.relayed && heard.hiddenSource)
        assertEquals("Rally at <CP2> & hold", CotParser.parse(heard.xml!!)!!.remarks)
    }

    @Test
    fun theLongestChatStillFitsWithTheExtension() {
        val long = ChatMessage(
            "0f8fad5b-d9cb-469f-a165-70867728950e", "ANDROID-589520ccfcd20f01", "NOVA",
            "All Chat Rooms", "All Chat Rooms", "x".repeat(400),
        )
        for (codec in listOf(PacketCodec(), PacketCodec(PayloadCrypto.fromPassphrase("k".toCharArray())))) {
            val own = codec.encode(long)
            val resent = codec.encode(long, ageS = 1500)
            val relayed = codec.encode(long, hops = 1, ageS = 70_000)
            assertEquals(own.size + 3, relayed.size)
            assertTrue(relayed.size <= Wire.MAX_PAYLOAD)
            val d0 = codec.decode(own) as DecodeResult.Ok
            val d1 = codec.decode(resent) as DecodeResult.Ok
            val d2 = codec.decode(relayed) as DecodeResult.Ok
            assertEquals(0 to 0, d0.hops to d0.ageS)
            assertEquals(0 to 1500, d1.hops to d1.ageS)
            // Ages beyond the field are capped, not wrapped.
            assertEquals(1 to Wire.MAX_AGE_S, d2.hops to d2.ageS)
            // A repeat never has to shorten the text.
            assertEquals(d0.message, d1.message)
            assertEquals(d0.message, d2.message)
        }
    }

    @Test
    fun firstTransmissionsCarryNoExtension() {
        val m = ChatMessage("i", "u", "NOVA", "All Chat Rooms", "All Chat Rooms", "hi")
        val codec = PacketCodec()
        assertEquals(codec.encode(m).size, codec.encode(m, hops = 0, ageS = null).size)
        // A truncated age field is a malformed packet.
        val broken = codec.encode(m) + byteArrayOf(Wire.EXT_AGE.toByte(), 0x01)
        assertTrue(codec.decode(broken) is DecodeResult.Error)
    }

    @Test
    fun aResentChatShowsTheTimeItWasWritten() {
        a.onRfFrame(pliFrom(b, 2))
        a.onClientEvent(golden("chat_all_atak.xml"))
        val writtenAt = clock.now
        b.onRfFrame(drain(a).single().frame)
        clock.advance(20 * 60_000)
        a.onRfFrame(pliFrom(c, 3))
        clock.advance(10_000)
        val again = drain(a).single()
        val decoded = PacketCodec().decode(Ax25Frame.decode(again.frame)!!.info) as DecodeResult.Ok
        assertEquals(0, decoded.hops)
        assertEquals(20 * 60 + 10, decoded.ageS)

        val rx = c.onRfFrame(again.frame)
        assertEquals(20 * 60 + 10, rx.ageS)
        val event = CotParser.parse(rx.xml!!)!!
        assertEquals(writtenAt, event.timeMs)
        assertEquals(writtenAt, event.startMs)
        assertTrue(rx.xml.contains("time=\"${com.fearmikey.takwave.core.cot.CotBuilder.time(writtenAt)}\">"))
        // Stale still counts from arrival, so the late copy is not stale on arrival.
        assertEquals(clock.now + com.fearmikey.takwave.core.cot.CotBuilder.DEFAULT_STALE_MS, event.staleMs)
    }

    @Test
    fun aRelayedChatShowsTheTimeItWasWritten() {
        val relay = station("N0RLY", relayOn)
        a.onClientEvent(golden("chat_all_atak.xml"))
        val writtenAt = clock.now
        relay.onRfFrame(drain(a).single().frame)
        clock.advance(10_000)
        val item = drain(relay).single()
        val event = CotParser.parse(b.onRfFrame(item.frame).xml!!)!!
        // Within the one second the age field resolves.
        assertTrue(event.timeMs in (writtenAt - 1000)..writtenAt)
    }

    @Test
    fun aRelayThatMissedAnEditCannotRevertAMarker() {
        val relay = station("N0RLY", relayOn)
        a.onClientEvent(golden("marker_spot_atak.xml"))
        val v1 = drain(a).single().frame
        relay.onRfFrame(v1)
        // A moves the marker 30 s later; the relay misses it, C hears it.
        clock.advance(30_000)
        val edited = golden("marker_spot_atak.xml").replace(Regex("lat=\"[-0-9.]+\""), "lat=\"41.6\"")
        assertTrue(a.onClientEvent(edited).acceptedForRf)
        val v2 = drain(a).single().frame
        assertNotNull(c.onRfFrame(v2).xml)
        // Now the relay passes on its old copy, and C hears it.
        clock.advance(10_000)
        val stale = drain(relay).single()
        val rx = c.onRfFrame(stale.frame)
        assertNull(rx.xml)
        assertEquals("older than the version already received", rx.note)
        // A station that never saw v2 still gets v1, with its real time.
        val event = CotParser.parse(b.onRfFrame(stale.frame).xml!!)!!
        assertTrue(event.timeMs < clock.now - 30_000)
    }
}
