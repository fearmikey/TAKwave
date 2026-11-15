// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.codec

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlin.math.roundToInt

/** Internal signal for malformed input; always caught inside [PacketCodec.decode]. */
internal class CodecException(message: String) : Exception(message)

/** Limits and constants of the over-air format. See docs/PROTOCOL.md. */
object Wire {
    const val MAGIC = 0x54
    const val VERSION = 0
    const val MAX_VERSION = 15

    const val TYPE_PLI = 1
    const val TYPE_CHAT = 2
    const val TYPE_MARKER = 3
    const val TYPE_EMERGENCY = 4
    const val TYPE_MASK = 0x0F
    const val FLAG_ENCRYPTED = 0x80
    /** Bits 6-4 of byte 1 carry the cipher suite of an encrypted packet. */
    const val SUITE_SHIFT = 4
    const val SUITE_MASK = 0x07
    /** Optional extension byte after a message body (PROTOCOL.md 4.4). Bits 3-0: how many times the packet was relayed. */
    const val EXT_HOPS_MASK = 0x0F
    /** Extension bit 4: a uint16 age in seconds follows the extension byte. */
    const val EXT_AGE = 0x10
    /** Extension bit 5: a one-byte position report counter follows (after the age, when both are present). */
    const val EXT_SEQ = 0x20
    const val MAX_AGE_S = 0xFFFF
    /** Extension byte plus age; kept free in every chat so a repeat never has to shorten the text. */
    const val EXT_MAX_SIZE = 3

    /** AX.25 default maximum information field. */
    const val MAX_PAYLOAD = 256

    const val MAX_UID = 48
    const val MAX_CALLSIGN = 20
    const val MAX_COT_TYPE = 32
    const val MAX_ROOM = 32
    const val MAX_CHAT_TEXT = 200
    const val MAX_MARKER_NAME = 32
    const val MAX_REMARKS = 80
    const val MAX_ALERT_CALLSIGN = 32
    const val MAX_ALERT_TEXT = 20

    const val ID_RAW = 0
    const val ID_ANDROID = 1
    const val ID_UUID_LOWER = 2
    const val ID_UUID_UPPER = 3

    const val ROOM_ALL = 0
    const val ROOM_NAMED = 1
    const val ROOM_ID_AND_NAME = 2

    const val MARKER_HAS_REMARKS = 0x01
    const val MARKER_HAS_COLOR = 0x02

    /** Emergency flag bit 0: the alert uid is not `<sender uid>-9-1-1` and follows as an Identifier. */
    const val ALERT_LITERAL_UID = 0x01

    const val UNKNOWN_U16 = 0xFFFF
    const val UNKNOWN_U8 = 0xFF
    const val UNKNOWN_HAE = -32768

    /** CoT type table. Index + 1 is the on-air code; code 0 means a literal string follows. Append only. */
    val COT_TYPES = listOf(
        "a-f-G-U-C", "a-f-G-U-C-I", "a-f-G-E-V-C", "a-f-G", "a-h-G", "a-n-G", "a-u-G",
        "a-f-A", "a-h-A", "a-n-A", "a-u-A", "a-f-S", "a-h-S", "a-n-S", "a-u-S",
        "b-m-p-s-m", "b-m-p-w", "b-m-p-w-GOTO", "b-m-p-c", "b-m-p-s-p-i", "b-m-p-s-p-loc", "b-m-p-s-p-op",
    )

    /** Truncates [s] to at most [maxBytes] of UTF-8 without splitting a code point. */
    fun truncateUtf8(s: String, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + n > maxBytes) break
            bytes += n
            i += Character.charCount(cp)
        }
        return s.substring(0, i)
    }
}

internal class ByteWriter {
    private val out = ByteArrayOutputStream(96)
    val size: Int get() = out.size()

    fun u8(v: Int) = out.write(v and 0xFF)
    fun u16(v: Int) { u8(v ushr 8); u8(v) }
    fun i32(v: Int) { u8(v ushr 24); u8(v ushr 16); u8(v ushr 8); u8(v) }
    fun bytes(b: ByteArray) = out.write(b, 0, b.size)

    fun string(s: String, maxBytes: Int) {
        val b = Wire.truncateUtf8(s, minOf(maxBytes, 255)).toByteArray(Charsets.UTF_8)
        u8(b.size)
        bytes(b)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

internal class ByteReader(private val data: ByteArray, private var pos: Int = 0) {
    val remaining: Int get() = data.size - pos

    private fun need(n: Int) {
        if (n < 0 || remaining < n) throw CodecException("truncated")
    }

    fun u8(): Int { need(1); return data[pos++].toInt() and 0xFF }
    fun u16(): Int = (u8() shl 8) or u8()
    fun i16(): Int = u16().toShort().toInt()
    fun i32(): Int = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()

    fun bytes(n: Int): ByteArray {
        need(n)
        val b = data.copyOfRange(pos, pos + n)
        pos += n
        return b
    }

    fun string(maxBytes: Int): String {
        val len = u8()
        if (len > maxBytes) throw CodecException("string too long")
        val raw = bytes(len)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(raw)).toString()
        } catch (e: CharacterCodingException) {
            throw CodecException("invalid UTF-8")
        }
    }
}

/**
 * Encodes [TakMessage]s to the compact over-air payload and back. The payload is the AX.25
 * information field. When [crypto] is set, everything after the two header bytes is sealed
 * by the configured [PayloadCipher].
 */
class PacketCodec(private val crypto: PayloadCipher? = null) {

    /** Description of the active cipher for the UI, or null when sending in the clear. */
    val cipherDescription: String? get() = crypto?.description

    /**
     * @param hops 0 for the station's own traffic; 1 when relaying a message heard from another station.
     * @param ageS for a repeat, seconds since the author first sent the message; null for a first transmission.
     * @param seq the station's position report counter (0-255); only position reports carry it.
     * The extension (PROTOCOL.md 4.4) is appended only when one of these is set.
     */
    fun encode(message: TakMessage, hops: Int = 0, ageS: Int? = null, seq: Int? = null): ByteArray {
        val overhead = if (crypto != null) crypto.overhead + 1 else 0
        val w = ByteWriter()
        val type = when (message) {
            is PliMessage -> { writePli(w, message); Wire.TYPE_PLI }
            // Room is always kept for the extension, so a repeat carries exactly the same text.
            is ChatMessage -> { writeChat(w, message, Wire.MAX_PAYLOAD - 2 - overhead - Wire.EXT_MAX_SIZE); Wire.TYPE_CHAT }
            is MarkerMessage -> { writeMarker(w, message); Wire.TYPE_MARKER }
            is EmergencyMessage -> { writeEmergency(w, message); Wire.TYPE_EMERGENCY }
        }
        if (hops > 0 || ageS != null || seq != null) {
            w.u8(
                hops.coerceIn(0, Wire.EXT_HOPS_MASK) or
                    (if (ageS != null) Wire.EXT_AGE else 0) or
                    (if (seq != null) Wire.EXT_SEQ else 0),
            )
            if (ageS != null) w.u16(ageS.coerceIn(0, Wire.MAX_AGE_S))
            if (seq != null) w.u8(seq)
        }
        val body = w.toByteArray()
        val version = (Wire.MAGIC + Wire.VERSION).toByte()
        if (crypto == null) {
            val out = ByteArray(2 + body.size)
            out[0] = version
            out[1] = type.toByte()
            body.copyInto(out, 2)
            return out
        }
        val header = byteArrayOf(version, (Wire.FLAG_ENCRYPTED or (crypto.suite shl Wire.SUITE_SHIFT)).toByte())
        val plain = ByteArray(1 + body.size)
        plain[0] = type.toByte()
        body.copyInto(plain, 1)
        return header + crypto.seal(plain, header)
    }

    fun decode(payload: ByteArray): DecodeResult {
        return try {
            decodeOrThrow(payload)
        } catch (e: CodecException) {
            DecodeResult.Error(e.message ?: "malformed")
        } catch (e: Exception) {
            // Defensive: nothing in a received frame may crash the receiver.
            DecodeResult.Error("malformed (${e.javaClass.simpleName})")
        }
    }

    private fun decodeOrThrow(payload: ByteArray): DecodeResult {
        if (payload.size < 3) return DecodeResult.Error("too short")
        if (payload.size > Wire.MAX_PAYLOAD * 2) return DecodeResult.Error("too long")
        val b0 = payload[0].toInt() and 0xFF
        if (b0 < Wire.MAGIC || b0 > Wire.MAGIC + Wire.MAX_VERSION) return DecodeResult.Error("not a TAKwave packet")
        val version = b0 - Wire.MAGIC
        if (version != Wire.VERSION) return DecodeResult.Error("unsupported version $version")
        val b1 = payload[1].toInt() and 0xFF
        val encrypted = b1 and Wire.FLAG_ENCRYPTED != 0
        val type: Int
        val reader: ByteReader
        if (encrypted) {
            val c = crypto ?: return DecodeResult.Error("encrypted packet but no key configured")
            val suite = (b1 ushr Wire.SUITE_SHIFT) and Wire.SUITE_MASK
            if (suite != c.suite) return DecodeResult.Error("encrypted with cipher suite $suite, this station is set up for suite ${c.suite}")
            if (!c.canDecrypt) return DecodeResult.Error("encrypted packet but no private key loaded (send-only)")
            val plain = c.open(payload, 2, payload.size - 2, payload.copyOfRange(0, 2))
                ?: return DecodeResult.Error("decryption failed (wrong key or corrupt)")
            if (plain.isEmpty()) return DecodeResult.Error("too short")
            type = plain[0].toInt() and Wire.TYPE_MASK
            reader = ByteReader(plain, 1)
        } else {
            type = b1 and Wire.TYPE_MASK
            reader = ByteReader(payload, 2)
        }
        val message = when (type) {
            Wire.TYPE_PLI -> readPli(reader)
            Wire.TYPE_CHAT -> readChat(reader)
            Wire.TYPE_MARKER -> readMarker(reader)
            Wire.TYPE_EMERGENCY -> readEmergency(reader)
            else -> return DecodeResult.Error("unknown message type $type")
        }
        // The first byte after the body, when present, is the extension byte. Anything after it is ignored.
        var hops = 0
        var ageS = 0
        var seq: Int? = null
        if (reader.remaining > 0) {
            val ext = reader.u8()
            hops = ext and Wire.EXT_HOPS_MASK
            if (ext and Wire.EXT_AGE != 0) ageS = reader.u16()
            if (ext and Wire.EXT_SEQ != 0) seq = reader.u8()
        }
        return DecodeResult.Ok(message, encrypted, hops, ageS, seq)
    }

    // ---- PLI ----

    private fun writePli(w: ByteWriter, m: PliMessage) {
        writeId(w, m.uid)
        writeCotType(w, m.cotType)
        w.string(m.callsign, Wire.MAX_CALLSIGN)
        w.i32(m.latE7)
        w.i32(m.lonE7)
        w.u16(m.haeM?.coerceIn(-32767, 32767) ?: Wire.UNKNOWN_HAE)
        w.u16(m.ceM?.coerceIn(0, 65534) ?: Wire.UNKNOWN_U16)
        w.u16(m.speedDms?.coerceIn(0, 65534) ?: Wire.UNKNOWN_U16)
        w.u16(m.courseDeg?.let { ((it % 360) + 360) % 360 } ?: Wire.UNKNOWN_U16)
        w.u8(m.team.ordinal)
        w.u8(m.role.ordinal)
        w.u8(m.battery?.coerceIn(0, 100) ?: Wire.UNKNOWN_U8)
        w.u8(m.intervalS?.let { (it / 5.0).roundToInt().coerceIn(1, 254) } ?: 0)
    }

    private fun readPli(r: ByteReader): PliMessage {
        val uid = readId(r)
        val type = readCotType(r)
        val callsign = r.string(Wire.MAX_CALLSIGN)
        val lat = readLat(r)
        val lon = readLon(r)
        val hae = r.i16().takeIf { it != Wire.UNKNOWN_HAE }
        val ce = r.u16().takeIf { it != Wire.UNKNOWN_U16 }
        val speed = r.u16().takeIf { it != Wire.UNKNOWN_U16 }
        val course = r.u16().takeIf { it < 360 }
        val team = Team.fromCode(r.u8())
        val role = Role.fromCode(r.u8())
        val battery = r.u8().takeIf { it <= 100 }
        val interval = r.u8().takeIf { it in 1..254 }?.times(5)
        return PliMessage(uid, callsign, type, lat, lon, hae, ce, speed, course, team, role, battery, interval)
    }

    // ---- Chat ----

    private fun writeChat(w: ByteWriter, m: ChatMessage, maxBody: Int) {
        writeId(w, m.messageId)
        writeId(w, m.senderUid)
        w.string(m.senderCallsign, Wire.MAX_CALLSIGN)
        when {
            m.roomId == ChatMessage.ALL_CHAT_ROOMS && m.roomName == ChatMessage.ALL_CHAT_ROOMS -> w.u8(Wire.ROOM_ALL)
            m.roomId == m.roomName && m.roomName.toByteArray(Charsets.UTF_8).size <= Wire.MAX_ROOM -> {
                w.u8(Wire.ROOM_NAMED)
                w.string(m.roomName, Wire.MAX_ROOM)
            }
            else -> {
                w.u8(Wire.ROOM_ID_AND_NAME)
                writeId(w, m.roomId)
                w.string(m.roomName, Wire.MAX_ROOM)
            }
        }
        // Whatever is left of the frame budget (after the length byte) goes to the text.
        w.string(m.text, minOf(Wire.MAX_CHAT_TEXT, maxOf(0, maxBody - w.size - 1)))
    }

    private fun readChat(r: ByteReader): ChatMessage {
        val msgId = readId(r)
        val senderUid = readId(r)
        val callsign = r.string(Wire.MAX_CALLSIGN)
        val roomId: String
        val roomName: String
        when (r.u8()) {
            Wire.ROOM_ALL -> { roomId = ChatMessage.ALL_CHAT_ROOMS; roomName = ChatMessage.ALL_CHAT_ROOMS }
            Wire.ROOM_NAMED -> { roomName = r.string(Wire.MAX_ROOM); roomId = roomName }
            Wire.ROOM_ID_AND_NAME -> { roomId = readId(r); roomName = r.string(Wire.MAX_ROOM) }
            else -> throw CodecException("unknown chat room code")
        }
        val text = r.string(Wire.MAX_CHAT_TEXT)
        return ChatMessage(msgId, senderUid, callsign, roomId, roomName, text)
    }

    // ---- Marker ----

    private fun writeMarker(w: ByteWriter, m: MarkerMessage) {
        writeId(w, m.uid)
        writeCotType(w, m.cotType)
        val remarks = m.remarks?.takeIf { it.isNotEmpty() }
        w.u8((if (remarks != null) Wire.MARKER_HAS_REMARKS else 0) or (if (m.colorArgb != null) Wire.MARKER_HAS_COLOR else 0))
        w.string(m.name, Wire.MAX_MARKER_NAME)
        w.i32(m.latE7)
        w.i32(m.lonE7)
        w.u16(m.haeM?.coerceIn(-32767, 32767) ?: Wire.UNKNOWN_HAE)
        if (remarks != null) w.string(remarks, Wire.MAX_REMARKS)
        if (m.colorArgb != null) w.i32(m.colorArgb)
    }

    private fun readMarker(r: ByteReader): MarkerMessage {
        val uid = readId(r)
        val type = readCotType(r)
        val flags = r.u8()
        val name = r.string(Wire.MAX_MARKER_NAME)
        val lat = readLat(r)
        val lon = readLon(r)
        val hae = r.i16().takeIf { it != Wire.UNKNOWN_HAE }
        val remarks = if (flags and Wire.MARKER_HAS_REMARKS != 0) r.string(Wire.MAX_REMARKS) else null
        val color = if (flags and Wire.MARKER_HAS_COLOR != 0) r.i32() else null
        return MarkerMessage(uid, type, name, lat, lon, hae, remarks, color)
    }

    // ---- Emergency ----

    private fun writeEmergency(w: ByteWriter, m: EmergencyMessage) {
        writeId(w, m.senderUid)
        w.u8(m.kind.ordinal)
        val conventional = m.uid == m.senderUid + EmergencyMessage.UID_SUFFIX
        w.u8(if (conventional) 0 else Wire.ALERT_LITERAL_UID)
        if (!conventional) writeId(w, m.uid)
        w.string(m.callsign, Wire.MAX_ALERT_CALLSIGN)
        w.string(m.text, Wire.MAX_ALERT_TEXT)
        w.i32(m.latE7)
        w.i32(m.lonE7)
        w.u16(m.haeM?.coerceIn(-32767, 32767) ?: Wire.UNKNOWN_HAE)
    }

    private fun readEmergency(r: ByteReader): EmergencyMessage {
        val senderUid = readId(r)
        val kind = EmergencyKind.fromCode(r.u8())
        val flags = r.u8()
        val uid = if (flags and Wire.ALERT_LITERAL_UID != 0) readId(r) else senderUid + EmergencyMessage.UID_SUFFIX
        val callsign = r.string(Wire.MAX_ALERT_CALLSIGN)
        val text = r.string(Wire.MAX_ALERT_TEXT)
        val lat = readLat(r)
        val lon = readLon(r)
        val hae = r.i16().takeIf { it != Wire.UNKNOWN_HAE }
        return EmergencyMessage(uid, senderUid, kind, callsign, text, lat, lon, hae)
    }

    // ---- Shared field encodings ----

    private fun readLat(r: ByteReader): Int {
        val v = r.i32()
        if (v < -900_000_000 || v > 900_000_000) throw CodecException("latitude out of range")
        return v
    }

    private fun readLon(r: ByteReader): Int {
        val v = r.i32()
        if (v < -1_800_000_000 || v > 1_800_000_000) throw CodecException("longitude out of range")
        return v
    }

    private fun writeCotType(w: ByteWriter, type: String) {
        val idx = Wire.COT_TYPES.indexOf(type)
        if (idx >= 0) {
            w.u8(idx + 1)
        } else {
            w.u8(0)
            w.string(type, Wire.MAX_COT_TYPE)
        }
    }

    private fun readCotType(r: ByteReader): String {
        val code = r.u8()
        if (code == 0) return r.string(Wire.MAX_COT_TYPE)
        return Wire.COT_TYPES.getOrNull(code - 1) ?: throw CodecException("unknown CoT type code $code")
    }

    /** Identifiers are usually "ANDROID-<16 hex>" or a UUID; both pack into raw bytes. */
    private fun writeId(w: ByteWriter, id: String) {
        when {
            ANDROID_ID.matches(id) -> {
                w.u8(Wire.ID_ANDROID)
                w.bytes(hexToBytes(id.substring(8)))
            }
            UUID_LOWER.matches(id) -> {
                w.u8(Wire.ID_UUID_LOWER)
                w.bytes(hexToBytes(id.replace("-", "")))
            }
            UUID_UPPER.matches(id) -> {
                w.u8(Wire.ID_UUID_UPPER)
                w.bytes(hexToBytes(id.replace("-", "")))
            }
            else -> {
                w.u8(Wire.ID_RAW)
                w.string(id, Wire.MAX_UID)
            }
        }
    }

    private fun readId(r: ByteReader): String = when (r.u8()) {
        Wire.ID_RAW -> r.string(Wire.MAX_UID)
        Wire.ID_ANDROID -> "ANDROID-" + bytesToHex(r.bytes(8))
        Wire.ID_UUID_LOWER -> uuidString(bytesToHex(r.bytes(16)))
        Wire.ID_UUID_UPPER -> uuidString(bytesToHex(r.bytes(16))).uppercase(Locale.ROOT)
        else -> throw CodecException("unknown id encoding")
    }

    private fun uuidString(hex: String): String =
        "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((Character.digit(hex[it * 2], 16) shl 4) or Character.digit(hex[it * 2 + 1], 16)).toByte() }

    private fun bytesToHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            sb.append(HEX[(x.toInt() ushr 4) and 0xF]).append(HEX[x.toInt() and 0xF])
        }
        return sb.toString()
    }

    companion object {
        private const val HEX = "0123456789abcdef"
        private val ANDROID_ID = Regex("ANDROID-[0-9a-f]{16}")
        private val UUID_LOWER = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val UUID_UPPER = Regex("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}")

        fun toHex(b: ByteArray): String {
            val sb = StringBuilder(b.size * 3)
            for ((i, x) in b.withIndex()) {
                if (i > 0) sb.append(' ')
                sb.append(HEX[(x.toInt() ushr 4) and 0xF]).append(HEX[x.toInt() and 0xF])
            }
            return sb.toString()
        }
    }
}
