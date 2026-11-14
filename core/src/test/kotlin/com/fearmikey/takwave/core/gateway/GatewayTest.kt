// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.gateway

import com.fearmikey.takwave.core.ax25.Ax25Address
import com.fearmikey.takwave.core.ax25.Ax25Frame
import com.fearmikey.takwave.core.codec.ChatMessage
import com.fearmikey.takwave.core.codec.PacketCodec
import com.fearmikey.takwave.core.codec.PayloadCrypto
import com.fearmikey.takwave.core.codec.PliMessage
import com.fearmikey.takwave.core.cot.CotParser
import com.fearmikey.takwave.core.sched.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class GatewayTest {
    private val clock = FakeClock()
    private val a = Gateway(clock, Ax25Address("N0AAA", 1))
    private val b = Gateway(clock, Ax25Address("N0BBB", 2))

    private fun golden(name: String): String =
        javaClass.getResourceAsStream("/cot/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }.trimEnd()

    private fun drain(g: Gateway): List<TxItem> = generateSequence { g.pollFrame() }.toList()

    @Test
    fun pliTravelsFromOneClientToTheOther() {
        val out = a.onClientEvent(golden("pli_atak.xml"))
        assertTrue(out.relay && out.acceptedForRf)
        val frames = drain(a)
        assertEquals(1, frames.size)

        val frame = Ax25Frame.decode(frames[0].frame)!!
        assertEquals("TAKWV", frame.destination.toString())
        assertEquals("N0AAA-1", frame.source.toString())
        assertEquals(0xF0, frame.pid)
        assertTrue(frame.isUi)
        assertEquals(0x54, frame.info[0].toInt())

        val rx = b.onRfFrame(frames[0].frame)
        assertNotNull(rx.xml)
        assertEquals("N0AAA-1", rx.source)
        val event = CotParser.parse(rx.xml!!)!!
        assertEquals("ANDROID-589520ccfcd20f01", event.uid)
        assertEquals("a-f-G-U-C", event.type)
        assertEquals("NOVA", event.callsign)
        assertEquals(41.5012345, event.lat, 1e-7)
        assertEquals(-71.3098765, event.lon, 1e-7)
        assertTrue(event.fromRf)
        // Stale is three base intervals (3 x 60 s) after reception.
        assertEquals(clock.now + 180_000, event.staleMs)

        val heard = b.heardStations()
        assertEquals(1, heard.size)
        assertEquals("N0AAA-1", heard[0].source)
        assertEquals("NOVA", heard[0].takCallsign)
        assertEquals(1, heard[0].packets)
        assertEquals(41.5012345, heard[0].lat!!, 1e-7)
    }

    @Test
    fun rfTrafficIsNeverRetransmitted() {
        // A's PLI, chat and marker arrive at B over RF.
        for (name in listOf("pli_atak.xml", "chat_all_atak.xml", "marker_spot_atak.xml")) a.onClientEvent(golden(name))
        val delivered = drain(a).map { b.onRfFrame(it.frame).xml!! }
        assertEquals(3, delivered.size)

        // B's TAK client echoes every one of them back to the server.
        for (xml in delivered) {
            val out = b.onClientEvent(xml)
            assertFalse("echo accepted for RF", out.acceptedForRf)
        }
        // Even with the <__takwave> marker stripped (another client re-serialising the event),
        // the uid / message id memory still blocks it.
        for (xml in delivered) {
            val out = b.onClientEvent(xml.replace("<__takwave rx=\"1\"/>", "<takv platform=\"ATAK-CIV\"/><archive/>"))
            assertFalse("stripped echo accepted for RF", out.acceptedForRf)
        }
        clock.advance(61_000)
        assertEquals(0, drain(b).size)
        assertEquals(0, b.queueDepth)
    }

    @Test
    fun ownTransmissionAndForeignFramesAreIgnored() {
        a.onClientEvent(golden("chat_all_atak.xml"))
        val frame = drain(a).single().frame
        assertNull(a.onRfFrame(frame).xml)
        assertEquals("own transmission heard", a.onRfFrame(frame).note)

        // An APRS position packet on the same channel.
        val aprs = Ax25Frame(Ax25Address("APRS"), Ax25Address("W1AW", 9), "!4130.00N/07118.00W-".toByteArray()).encode()
        assertNull(b.onRfFrame(aprs).xml)
        // Right destination, wrong PID.
        val wrongPid = Ax25Frame(Ax25Address("TAKWV"), Ax25Address("W1AW"), byteArrayOf(0x54, 1, 0), pid = 0xCF).encode()
        assertNull(b.onRfFrame(wrongPid).xml)
        assertTrue(b.heardStations().isEmpty())
    }

    @Test
    fun garbageFramesNeverThrowOrReachClients() {
        val rnd = Random(9)
        repeat(20_000) {
            val info = ByteArray(rnd.nextInt(120))
            rnd.nextBytes(info)
            if (info.size > 2) { info[0] = 0x54; info[1] = (rnd.nextInt(4)).toByte() }
            val frame = if (it % 3 == 0) info else Ax25Frame(Ax25Address("TAKWV"), Ax25Address("N0XXX"), info).encode()
            val out = b.onRfFrame(frame)
            // Anything that does get through must be well-formed CoT.
            if (out.xml != null) assertNotNull(CotParser.parse(out.xml!!))
        }
    }

    @Test
    fun duplicateFramesAreSuppressedBriefly() {
        a.onClientEvent(golden("chat_all_atak.xml"))
        val frame = drain(a).single().frame
        assertNotNull(b.onRfFrame(frame).xml)
        assertEquals("duplicate", b.onRfFrame(frame).note)
        // A stationary station's identical PLI a minute later must still be delivered.
        clock.advance(Gateway.DUPLICATE_WINDOW_MS + 1)
        assertNotNull(b.onRfFrame(frame).xml)
        assertEquals(2, b.heardStations().single().packets)
    }

    @Test
    fun pingsAreAnsweredLocallyAndNeverTransmitted() {
        val ping = "<event version=\"2.0\" uid=\"ANDROID-589520ccfcd20f01-ping\" type=\"t-x-c-t\" time=\"2026-11-14T18:00:00Z\" " +
            "start=\"2026-11-14T18:00:00Z\" stale=\"2026-11-14T18:00:10Z\" how=\"m-g\"><point lat=\"0.0\" lon=\"0.0\" hae=\"0.0\" ce=\"9999999\" le=\"9999999\"/></event>"
        val out = a.onClientEvent(ping)
        assertFalse(out.relay)
        assertFalse(out.acceptedForRf)
        assertEquals("t-x-c-t-r", CotParser.parse(out.reply!!)!!.type)
        assertEquals(0, drain(a).size)
    }

    @Test
    fun nothingIsQueuedWithoutACallsign() {
        val unconfigured = Gateway(clock, null)
        for (name in listOf("pli_atak.xml", "chat_all_atak.xml", "marker_spot_atak.xml")) {
            val out = unconfigured.onClientEvent(golden(name))
            assertTrue(out.relay) // still relayed between local clients
            assertFalse(out.acceptedForRf)
        }
        assertNull(unconfigured.pollFrame())
        assertFalse(unconfigured.enqueue(ChatMessage("i", "u", "c", "r", "r", "t")))
        // Its local position is still tracked for the Heard list.
        assertNotNull(unconfigured.localPosition)
    }

    @Test
    fun chatJumpsAheadOfPliAndPliIsThrottled() {
        a.onClientEvent(golden("pli_atak.xml"))
        a.onClientEvent(golden("marker_spot_atak.xml"))
        a.onClientEvent(golden("chat_all_atak.xml"))
        val kinds = drain(a).map { it.message::class.simpleName }
        assertEquals(listOf("ChatMessage", "MarkerMessage", "PliMessage"), kinds)

        // ATAK repeats its PLI every few seconds; only one more goes out, at the base interval.
        var sent = 0
        repeat(30) {
            clock.advance(3_000)
            a.onClientEvent(golden("pli_atak.xml"))
            sent += drain(a).size
        }
        assertEquals(1, sent)
    }

    @Test
    fun malformedClientXmlIsDroppedNotRelayed() {
        val out = a.onClientEvent("<event uid=\"x\" type=\"a-f-G\"><point lat=\"1\"")
        assertFalse(out.relay)
        assertFalse(out.acceptedForRf)
        // Unsupported but valid CoT is relayed between clients, just not sent on RF.
        val shape = a.onClientEvent("<event uid=\"s\" type=\"u-d-f\" how=\"h-e\"><point lat=\"1\" lon=\"2\" hae=\"0\" ce=\"1\" le=\"1\"/></event>")
        assertTrue(shape.relay)
        assertFalse(shape.acceptedForRf)
        assertNotNull(shape.note)
    }

    @Test
    fun encryptedStationsInteroperateOnlyWithTheSameKey() {
        val key = "part 90 fleet key"
        val ea = Gateway(clock, Ax25Address("WQAA11"), PacketCodec(PayloadCrypto(key.toCharArray())))
        val eb = Gateway(clock, Ax25Address("WQBB22"), PacketCodec(PayloadCrypto(key.toCharArray())))
        ea.onClientEvent(golden("chat_all_atak.xml"))
        val frame = drain(ea).single().frame
        // The AX.25 header stays readable; the text does not.
        assertEquals("WQAA11", Ax25Frame.decode(frame)!!.source.toString())
        assertFalse(String(frame, Charsets.ISO_8859_1).contains("Rally"))
        val ok = eb.onRfFrame(frame)
        assertTrue(ok.encrypted)
        assertEquals("Rally at <CP2> & hold", CotParser.parse(ok.xml!!)!!.remarks)
        // A station without the key drops it.
        assertNull(b.onRfFrame(frame).xml)
    }

    @Test
    fun receivedChatUsesSendersLastKnownPosition() {
        a.onClientEvent(golden("pli_atak.xml"))
        b.onRfFrame(drain(a).single().frame)
        a.onClientEvent(golden("chat_all_atak.xml"))
        val chat = CotParser.parse(b.onRfFrame(drain(a).single().frame).xml!!)!!
        assertEquals(41.5012345, chat.lat, 1e-7)
        assertEquals(-71.3098765, chat.lon, 1e-7)
    }

    @Test
    fun summariesAreReadable() {
        assertEquals("PLI NOVA 41.50000,-71.30000", PliMessage("u", "NOVA", "a-f-G-U-C", 415_000_000, -713_000_000).summary())
        assertEquals("CHAT NOVA > All Chat Rooms: hi", ChatMessage("i", "u", "NOVA", "All Chat Rooms", "All Chat Rooms", "hi").summary())
    }
}
