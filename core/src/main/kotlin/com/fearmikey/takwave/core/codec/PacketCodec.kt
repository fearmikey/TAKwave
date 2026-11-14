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
    const val TYPE_MASK = 0x0F
    const val FLAG_ENCRYPTED = 0x80

    /** AX.25 default maximum information field. */
    const val MAX_PAYLOAD = 256

    const val MAX_UID = 48
    const val MAX_CALLSIGN = 20
    const val MAX_COT_TYPE = 32
    const val MAX_ROOM = 32
    const val MAX_CHAT_TEXT = 200
    const val MAX_MARKER_NAME = 32
    const val MAX_REMARKS = 80

    const val ID_RAW = 0
    const val ID_ANDROID = 1
    const val ID_UUID_LOWER = 2
    const val ID_UUID_UPPER = 3

    const val ROOM_ALL = 0
    const val ROOM_NAMED = 1
    const val ROOM_ID_AND_NAME = 2

    const val MARKER_HAS_REMARKS = 0x01
    const val MARKER_HAS_COLOR = 0x02

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
 * with AES-256-GCM.
 */
class PacketCodec(private val crypto: PayloadCrypto? = null) {

    fun encode(message: TakMessage): ByteArray {
        val overhead = if (crypto != null) PayloadCrypto.OVERHEAD + 1 else 0
        val w = ByteWriter()
        val type = when (message) {
            is PliMessage -> { writePli(w, message); Wire.TYPE_PLI }
            is ChatMessage -> { writeChat(w, message, Wire.MAX_PAYLOAD - 2 - overhead); Wire.TYPE_CHAT }
            is MarkerMessage -> { writeMarker(w, message); Wire.TYPE_MARKER }
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
        val header = byteArrayOf(version, Wire.FLAG_ENCRYPTED.toByte())
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
            else -> return DecodeResult.Error("unknown message type $type")
        }
        return DecodeResult.Ok(message, encrypted)
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
