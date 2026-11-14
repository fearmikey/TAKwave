// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PacketCodecTest {
    private val codec = PacketCodec()

    private val pli = PliMessage(
        uid = "ANDROID-589520ccfcd20f01",
        callsign = "NOVA",
        cotType = "a-f-G-U-C",
        latE7 = 415_012_345,
        lonE7 = -713_098_765,
        haeM = 24,
        ceM = 10,
        speedDms = 12,
        courseDeg = 45,
        team = Team.CYAN,
        role = Role.TEAM_MEMBER,
        battery = 98,
        intervalS = 60,
    )

    private val chat = ChatMessage(
        messageId = "563040b9-2ac9-4af3-9e01-4cb2b05d98ea",
        senderUid = "ANDROID-589520ccfcd20f01",
        senderCallsign = "NOVA",
        roomId = ChatMessage.ALL_CHAT_ROOMS,
        roomName = ChatMessage.ALL_CHAT_ROOMS,
        text = "at the trailhead, moving north",
    )

    private val marker = MarkerMessage(
        uid = "9405e320-9356-41c4-8449-f46990aa17f8",
        cotType = "b-m-p-s-m",
        name = "R 1",
        latE7 = 415_000_000,
        lonE7 = -713_000_000,
        haeM = 12,
        remarks = "water cache",
        colorArgb = -65536,
    )

    private fun roundTrip(m: TakMessage, c: PacketCodec = codec): TakMessage {
        val r = c.decode(c.encode(m))
        assertTrue("decode failed: $r", r is DecodeResult.Ok)
        return (r as DecodeResult.Ok).message
    }

    @Test
    fun pliRoundTripAndSize() {
        val bytes = codec.encode(pli)
        assertEquals(0x54, bytes[0].toInt())
        assertEquals(Wire.TYPE_PLI, bytes[1].toInt())
        assertTrue("PLI payload is ${bytes.size} bytes", bytes.size <= 50)
        assertEquals(pli, roundTrip(pli))

        // UUID-style uid (iTAK / WinTAK) with a longer callsign still fits the budget.
        val uuidPli = pli.copy(uid = "6B3E1A2C-0F4D-4E55-9A7B-1C2D3E4F5A6B", callsign = "BRAVO-06")
        assertTrue(codec.encode(uuidPli).size <= 50)
        assertEquals(uuidPli, roundTrip(uuidPli))
    }

    @Test
    fun pliEdgeCases() {
        val cases = listOf(
            pli.copy(latE7 = 900_000_000, lonE7 = 1_800_000_000),
            pli.copy(latE7 = -900_000_000, lonE7 = -1_800_000_000),
            pli.copy(latE7 = -338_688_000, lonE7 = 1_512_093_000),
            pli.copy(latE7 = 0, lonE7 = 0),
            pli.copy(haeM = null, ceM = null, speedDms = null, courseDeg = null, battery = null, intervalS = null),
            pli.copy(haeM = -420, speedDms = 65534, courseDeg = 359, battery = 0, intervalS = 15),
            pli.copy(team = Team.UNKNOWN, role = Role.UNKNOWN),
            pli.copy(team = Team.BROWN, role = Role.K9),
            pli.copy(uid = "S-1-5-21-custom-uid", cotType = "a-f-G-U-C-X-custom"),
            pli.copy(callsign = "Ünïcødé-🚁"),
            pli.copy(callsign = ""),
        )
        for (c in cases) assertEquals(c, roundTrip(c))

        // Out-of-range values are clamped to the representable range rather than wrapped.
        val clamped = roundTrip(pli.copy(haeM = 99_999, ceM = 9_999_999, battery = 150)) as PliMessage
        assertEquals(32767, clamped.haeM)
        assertEquals(65534, clamped.ceM)
        assertEquals(100, clamped.battery)

        // Over-long strings are truncated on a code-point boundary.
        val long = roundTrip(pli.copy(callsign = "é".repeat(40))) as PliMessage
        assertEquals("é".repeat(10), long.callsign)
    }

    @Test
    fun everyTeamAndRoleSurvives() {
        for (t in Team.entries) for (r in Role.entries) {
            val m = roundTrip(pli.copy(team = t, role = r)) as PliMessage
            assertEquals(t, m.team)
            assertEquals(r, m.role)
        }
        assertEquals(15, Team.entries.size) // 14 colours plus UNKNOWN
        assertEquals(Team.DARK_BLUE, Team.fromCotName("Dark Blue"))
        assertEquals(Role.FORWARD_OBSERVER, Role.fromCotName("Forward Observer"))
        assertEquals(Team.UNKNOWN, Team.fromCotName("Chartreuse"))
    }

    @Test
    fun chatRoundTrip() {
        assertEquals(chat, roundTrip(chat))
        val named = chat.copy(roomId = "Recon", roomName = "Recon")
        assertEquals(named, roundTrip(named))
        val direct = chat.copy(roomId = "ANDROID-00112233445566ff", roomName = "BRAVO")
        assertEquals(direct, roundTrip(direct))
        val unicode = chat.copy(text = "Привет — 你好 — 🚩 rally at 0600")
        assertEquals(unicode, roundTrip(unicode))
        val empty = chat.copy(text = "")
        assertEquals(empty, roundTrip(empty))
    }

    @Test
    fun chatTextIsCappedAndFrameFitsAx25() {
        val long = chat.copy(text = "x".repeat(500))
        val bytes = codec.encode(long)
        assertTrue(bytes.size <= Wire.MAX_PAYLOAD)
        assertEquals("x".repeat(200), (roundTrip(long) as ChatMessage).text)

        // Multi-byte text must be cut on a character boundary.
        val emoji = chat.copy(text = "🚩".repeat(100))
        val got = (roundTrip(emoji) as ChatMessage).text
        assertEquals("🚩".repeat(50), got)

        // Worst case: every field at its maximum still fits one AX.25 information field.
        val worst = ChatMessage(
            messageId = "m".repeat(80), senderUid = "u".repeat(80), senderCallsign = "c".repeat(40),
            roomId = "r".repeat(80), roomName = "n".repeat(80), text = "t".repeat(400),
        )
        val worstBytes = codec.encode(worst)
        assertTrue("worst-case chat is ${worstBytes.size} bytes", worstBytes.size <= Wire.MAX_PAYLOAD)
        assertTrue(codec.decode(worstBytes) is DecodeResult.Ok)
    }

    @Test
    fun markerRoundTrip() {
        assertEquals(marker, roundTrip(marker))
        val bare = marker.copy(remarks = null, colorArgb = null, haeM = null, cotType = "a-u-G")
        assertEquals(bare, roundTrip(bare))
        val longRemarks = roundTrip(marker.copy(remarks = "r".repeat(300))) as MarkerMessage
        assertEquals("r".repeat(80), longRemarks.remarks)
        val poles = marker.copy(latE7 = -900_000_000, lonE7 = 1_800_000_000, name = "Südpol ❄")
        assertEquals(poles, roundTrip(poles))
    }

    @Test
    fun truncatedInputIsRejectedWithoutThrowing() {
        for (m in listOf(pli, chat, marker)) {
            val bytes = codec.encode(m)
            for (len in 0 until bytes.size) {
                val r = codec.decode(bytes.copyOf(len))
                assertTrue("prefix of length $len decoded", r is DecodeResult.Error)
            }
        }
    }

    @Test
    fun garbageNeverThrows() {
        val rnd = Random(42)
        val keyed = PacketCodec(PayloadCrypto("garbage test".toCharArray()))
        repeat(50_000) {
            val b = ByteArray(rnd.nextInt(300))
            rnd.nextBytes(b)
            if (b.size >= 2 && it % 2 == 0) {
                b[0] = 0x54
                b[1] = (1 + rnd.nextInt(3)).toByte()
            }
            codec.decode(b)
            if (it % 50 == 0) keyed.decode(b)
        }
        // Bit flips in valid packets must also be handled.
        for (m in listOf(pli, chat, marker)) {
            val good = codec.encode(m)
            for (i in good.indices) for (bit in 0 until 8) {
                val bad = good.copyOf()
                bad[i] = (bad[i].toInt() xor (1 shl bit)).toByte()
                codec.decode(bad)
            }
        }
    }

    @Test
    fun rejectsWrongMagicVersionAndType() {
        val good = codec.encode(pli)
        assertTrue(codec.decode(good.copyOf().also { it[0] = 0x41 }) is DecodeResult.Error)
        assertTrue(codec.decode(good.copyOf().also { it[0] = 0x55 }) is DecodeResult.Error)
        assertTrue(codec.decode(good.copyOf().also { it[1] = 0x09 }) is DecodeResult.Error)
        assertTrue(codec.decode(ByteArray(0)) is DecodeResult.Error)
        // Out-of-range coordinates are refused rather than passed to the map.
        val badLat = codec.encode(pli.copy(latE7 = 900_000_001))
        assertTrue(codec.decode(badLat) is DecodeResult.Error)
    }

    @Test
    fun encryption() {
        val keyed = PacketCodec(PayloadCrypto("correct horse battery staple".toCharArray()))
        for (m in listOf(pli, chat, marker, chat.copy(text = "y".repeat(400)))) {
            val sealed = keyed.encode(m)
            assertEquals(0x54, sealed[0].toInt())
            assertEquals(Wire.FLAG_ENCRYPTED, sealed[1].toInt() and 0xFF)
            assertTrue(sealed.size <= Wire.MAX_PAYLOAD)
            val r = keyed.decode(sealed)
            assertTrue(r is DecodeResult.Ok && r.encrypted)
            // Random nonce: the same message never encrypts to the same bytes twice.
            assertNotEquals(sealed.toList(), keyed.encode(m).toList())
            // No key, wrong key, tampering: all refused.
            assertTrue(codec.decode(sealed) is DecodeResult.Error)
            assertTrue(PacketCodec(PayloadCrypto("wrong".toCharArray())).decode(sealed) is DecodeResult.Error)
            val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
            assertTrue(keyed.decode(tampered) is DecodeResult.Error)
        }
        assertEquals(pli, roundTrip(pli, keyed))
        // The plaintext must not be visible in the sealed packet.
        val sealed = keyed.encode(chat)
        assertTrue(!String(sealed, Charsets.ISO_8859_1).contains("trailhead"))
        // A station with a key still reads clear packets, and reports them as unencrypted.
        val clear = keyed.decode(codec.encode(pli))
        assertTrue(clear is DecodeResult.Ok && !clear.encrypted)
    }
}
