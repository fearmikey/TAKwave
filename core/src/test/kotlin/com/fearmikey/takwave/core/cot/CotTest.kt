// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.cot

import com.fearmikey.takwave.core.codec.ChatMessage
import com.fearmikey.takwave.core.codec.DecodeResult
import com.fearmikey.takwave.core.codec.MarkerMessage
import com.fearmikey.takwave.core.codec.PacketCodec
import com.fearmikey.takwave.core.codec.PliMessage
import com.fearmikey.takwave.core.codec.Role
import com.fearmikey.takwave.core.codec.TakMessage
import com.fearmikey.takwave.core.codec.Team
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CotTest {
    private val now = Instant.parse("2026-11-14T18:00:00Z").toEpochMilli()
    private val codec = PacketCodec()

    private fun golden(name: String): String =
        javaClass.getResourceAsStream("/cot/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }.trimEnd()

    /** Client XML -> classify -> encode -> decode -> rebuilt XML, exactly as the two stations would do it. */
    private fun overTheAir(inputName: String, pliIntervalS: Int? = 60): Pair<TakMessage, String> {
        val event = CotParser.parse(golden(inputName))
        assertNotNull("parse failed for $inputName", event)
        val classified = CotMapper.classify(event!!, pliIntervalS)
        assertTrue("$inputName classified as $classified", classified is Classified.Send)
        val sent = (classified as Classified.Send).message
        val decoded = codec.decode(codec.encode(sent))
        assertTrue(decoded is DecodeResult.Ok)
        val received = (decoded as DecodeResult.Ok).message
        assertEquals(sent, received)
        return received to CotBuilder.build(received, now)
    }

    @Test
    fun pliGolden() {
        val (msg, xml) = overTheAir("pli_atak.xml")
        assertEquals(
            PliMessage(
                uid = "ANDROID-589520ccfcd20f01", callsign = "NOVA", cotType = "a-f-G-U-C",
                latE7 = 415_012_345, lonE7 = -713_098_765, haeM = 24, ceM = 10, speedDms = 12, courseDeg = 45,
                team = Team.CYAN, role = Role.TEAM_MEMBER, battery = 98, intervalS = 60,
            ),
            msg,
        )
        assertEquals(golden("rx_pli.xml"), xml)
    }

    @Test
    fun pliStaleDefaultsToFiveMinutes() {
        val (_, xml) = overTheAir("pli_atak.xml", pliIntervalS = null)
        val e = CotParser.parse(xml)!!
        assertEquals(now + 5 * 60_000, e.staleMs)
        assertEquals(now, e.timeMs)
    }

    @Test
    fun chatGolden() {
        val (msg, xml) = overTheAir("chat_all_atak.xml")
        assertEquals(
            ChatMessage(
                messageId = "563040b9-2ac9-4af3-9e01-4cb2b05d98ea", senderUid = "ANDROID-589520ccfcd20f01",
                senderCallsign = "NOVA", roomId = "All Chat Rooms", roomName = "All Chat Rooms",
                text = "Rally at <CP2> & hold",
            ),
            msg,
        )
        assertEquals(golden("rx_chat_all.xml"), xml)
    }

    @Test
    fun directChatGolden() {
        val (msg, xml) = overTheAir("chat_direct_atak.xml")
        msg as ChatMessage
        assertEquals("ANDROID-00112233445566ff", msg.roomId)
        assertEquals("BRAVO", msg.roomName)
        assertEquals(golden("rx_chat_direct.xml"), xml)
    }

    @Test
    fun rebuiltChatHasEverythingAtakNeeds() {
        val (_, xml) = overTheAir("chat_all_atak.xml")
        val e = CotParser.parse(xml)!!
        assertEquals("b-t-f", e.type)
        assertEquals("GeoChat.ANDROID-589520ccfcd20f01.All Chat Rooms.563040b9-2ac9-4af3-9e01-4cb2b05d98ea", e.uid)
        assertEquals("All Chat Rooms", e.chat!!.id)
        assertEquals("All Chat Rooms", e.chat!!.chatroom)
        assertEquals("NOVA", e.chat!!.senderCallsign)
        assertEquals("563040b9-2ac9-4af3-9e01-4cb2b05d98ea", e.chat!!.messageId)
        assertEquals("ANDROID-589520ccfcd20f01", e.chat!!.senderUid)
        assertEquals("ANDROID-589520ccfcd20f01", e.linkUid)
        assertEquals("Rally at <CP2> & hold", e.remarks)
        assertTrue(xml.contains("source=\"BAO.F.ATAK.ANDROID-589520ccfcd20f01\""))
        assertTrue(xml.contains("to=\"All Chat Rooms\""))
        assertTrue(xml.contains("time=\"2026-11-14T18:00:00.000Z\">"))
    }

    @Test
    fun markerGoldens() {
        val (spot, spotXml) = overTheAir("marker_spot_atak.xml")
        assertEquals(
            MarkerMessage(
                uid = "9405e320-9356-41c4-8449-f46990aa17f8", cotType = "b-m-p-s-m", name = "R 1",
                latE7 = 415_000_000, lonE7 = -713_000_000, haeM = 12, remarks = "water cache", colorArgb = -65536,
            ),
            spot,
        )
        assertEquals(golden("rx_marker_spot.xml"), spotXml)

        val (atom, atomXml) = overTheAir("marker_atom_atak.xml")
        atom as MarkerMessage
        assertEquals("a-h-G", atom.cotType)
        assertNull(atom.haeM)
        assertNull(atom.remarks)
        assertEquals(golden("rx_marker_atom.xml"), atomXml)
    }

    @Test
    fun rebuiltEventsAreRecognizedAsFromRf() {
        for (name in listOf("rx_pli.xml", "rx_chat_all.xml", "rx_chat_direct.xml", "rx_marker_spot.xml", "rx_marker_atom.xml")) {
            val e = CotParser.parse(golden(name))
            assertNotNull(name, e)
            assertTrue(e!!.fromRf)
            assertTrue(CotMapper.classify(e) is Classified.Drop)
        }
    }

    @Test
    fun classification() {
        fun classify(xml: String) = CotMapper.classify(CotParser.parse(xml)!!)
        val point = "<point lat=\"1\" lon=\"2\" hae=\"0\" ce=\"1\" le=\"1\"/>"
        assertEquals(Classified.Ping, classify("<event version=\"2.0\" uid=\"x-ping\" type=\"t-x-c-t\" how=\"m-g\">$point</event>"))
        // Someone else's track relayed by the client, a route, a shape, a file transfer: all dropped.
        assertTrue(classify("<event uid=\"t1\" type=\"a-f-A\" how=\"m-g\">$point<detail><contact callsign=\"X\"/></detail></event>") is Classified.Drop)
        assertTrue(classify("<event uid=\"r1\" type=\"b-m-r\" how=\"h-e\">$point</event>") is Classified.Drop)
        assertTrue(classify("<event uid=\"s1\" type=\"u-d-f\" how=\"h-e\">$point</event>") is Classified.Drop)
        assertTrue(classify("<event uid=\"f1\" type=\"b-f-t-r\" how=\"h-e\">$point</event>") is Classified.Drop)
        assertTrue(classify("<event uid=\"c1\" type=\"b-t-f\" how=\"h-e\">$point</event>") is Classified.Drop)
        // A waypoint is a point marker.
        assertTrue(classify("<event uid=\"w1\" type=\"b-m-p-w\" how=\"h-g-i-g-o\">$point</event>") is Classified.Send)
    }

    @Test
    fun builderEscapesHostileStrings() {
        val nasty = PliMessage(
            uid = "u\"><x y=\"", callsign = "<&'\">\u0001\n", cotType = "a-f-G\"", latE7 = 0, lonE7 = 0,
        )
        val xml = CotBuilder.pli(nasty, now)
        val e = CotParser.parse(xml)
        assertNotNull(e)
        assertEquals("u\"><x y=\"", e!!.uid)
        assertEquals("<&'\">\n", e.callsign)

        val chat = ChatMessage("id", "uid", "cs", "room", "room", "</remarks><evil/>&#x0; ]]> 🚀")
        val ce = CotParser.parse(CotBuilder.chat(chat, now))!!
        assertEquals("</remarks><evil/>&#x0; ]]> 🚀", ce.remarks)
    }

    // ---- Hardening ----

    @Test
    fun xxeAndEntityBombsAreRejected() {
        val event = "<event version=\"2.0\" uid=\"x\" type=\"a-f-G\" how=\"m-g\"><point lat=\"1\" lon=\"2\" hae=\"0\" ce=\"1\" le=\"1\"/><detail><contact callsign=\"&xxe;\"/></detail></event>"
        val xxe = "<?xml version=\"1.0\"?><!DOCTYPE event [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>$event"
        assertNull(CotParser.parse(xxe))
        val http = "<?xml version=\"1.0\"?><!DOCTYPE event SYSTEM \"http://203.0.113.1/evil.dtd\">$event"
        assertNull(CotParser.parse(http))
        val bomb = "<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;&lol;&lol;\">]>" +
            event.replace("&xxe;", "&lol2;")
        assertNull(CotParser.parse(bomb))
        assertNull(CotParser.parse(xxe.replace("DOCTYPE", "doctype")))
        // An undeclared entity with no DTD at all is simply malformed.
        assertNull(CotParser.parse(event))
        // The same event with a harmless value parses.
        assertNotNull(CotParser.parse(event.replace("&xxe;", "ok &amp; fine")))
    }

    @Test
    fun malformedAndOversizedEventsAreDropped() {
        assertNull(CotParser.parse(""))
        assertNull(CotParser.parse("not xml at all"))
        assertNull(CotParser.parse("<event uid=\"a\" type=\"a-f-G\"><point lat=\"1\" lon=\"2\"/>"))
        assertNull(CotParser.parse("<notevent uid=\"a\" type=\"a-f-G\"><point lat=\"1\" lon=\"2\"/></notevent>"))
        assertNull(CotParser.parse("<event type=\"a-f-G\"><point lat=\"1\" lon=\"2\"/></event>"))
        assertNull(CotParser.parse("<event uid=\"a\" type=\"a-f-G\"></event>"))
        assertNull(CotParser.parse("<event uid=\"a\" type=\"a-f-G\"><point lat=\"91\" lon=\"2\"/></event>"))
        assertNull(CotParser.parse("<event uid=\"a\" type=\"a-f-G\"><point lat=\"NaN\" lon=\"2\"/></event>"))
        assertNull(CotParser.parse("<event uid=\"a\" type=\"a-f-G\"><point lat=\"1\" lon=\"abc\"/></event>"))
        val deep = "<event uid=\"a\" type=\"a-f-G\"><point lat=\"1\" lon=\"2\"/><detail>" +
            "<a>".repeat(5000) + "</a>".repeat(5000) + "</detail></event>"
        assertNull(CotParser.parse(deep))
        val big = "<event uid=\"a\" type=\"a-f-G\"><point lat=\"1\" lon=\"2\"/><detail><remarks>" +
            "x".repeat(70_000) + "</remarks></detail></event>"
        assertNull(CotParser.parse(big))
    }

    // ---- Stream splitting ----

    private fun split(splitter: CotStreamSplitter, bytes: ByteArray, chunk: Int): List<String> {
        val out = ArrayList<String>()
        var p = 0
        while (p < bytes.size) {
            val n = minOf(chunk, bytes.size - p)
            splitter.feed(bytes, p, n) { out.add(it) }
            p += n
        }
        return out
    }

    @Test
    fun streamSplitsOnEventBoundariesAtAnyChunking() {
        val a = golden("pli_atak.xml")
        val b = golden("chat_all_atak.xml")
        val c = golden("marker_spot_atak.xml").replace("water cache", "eau — 水 🚰")
        val stream = (a + "\n" + b + c + "\n\n").toByteArray(Charsets.UTF_8)
        for (chunk in listOf(1, 2, 3, 7, 64, 1000, stream.size)) {
            val events = split(CotStreamSplitter(), stream, chunk)
            assertEquals("chunk size $chunk", 3, events.size)
            assertTrue(events.all { it.startsWith("<event") && it.endsWith("</event>") })
            assertEquals("NOVA", CotParser.parse(events[0])!!.callsign)
            assertEquals("Rally at <CP2> & hold", CotParser.parse(events[1])!!.remarks)
            assertEquals("eau — 水 🚰", CotParser.parse(events[2])!!.remarks)
        }
    }

    @Test
    fun oversizedEventIsDroppedAndStreamRecovers() {
        val good = golden("pli_atak.xml")
        val huge = "<event uid=\"big\" type=\"a-f-G\"><detail><remarks>" + "A".repeat(200_000) + "</remarks></detail></event>"
        val stream = (good + huge + good + huge + good).toByteArray(Charsets.UTF_8)
        for (chunk in listOf(1, 13, 4096, stream.size)) {
            val splitter = CotStreamSplitter()
            val events = split(splitter, stream, chunk)
            assertEquals("chunk size $chunk", 3, events.size)
            assertEquals(2, splitter.droppedOversize)
            assertTrue(events.all { CotParser.parse(it)?.callsign == "NOVA" })
        }
    }

    @Test
    fun junkBetweenEventsIsIgnored() {
        val good = golden("pli_atak.xml")
        val stream = ("garbage</event>\u0000\u0001" + good + "more junk </event>" + good).toByteArray(Charsets.UTF_8)
        val events = split(CotStreamSplitter(), stream, 5)
        assertEquals(2, events.size)
    }
}
