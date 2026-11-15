// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.cot

import com.fearmikey.takwave.core.codec.ChatMessage
import com.fearmikey.takwave.core.codec.EmergencyMessage
import com.fearmikey.takwave.core.codec.MarkerMessage
import com.fearmikey.takwave.core.codec.PliMessage
import com.fearmikey.takwave.core.codec.Role
import com.fearmikey.takwave.core.codec.TakMessage
import com.fearmikey.takwave.core.codec.Team
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Rebuilds CoT XML for messages received from RF. The element layout follows what ATAK-CIV
 * itself emits, so the events render the same as ones that arrived over a network.
 * Every event gets a `<__takwave rx="1"/>` detail so it can be recognised if it comes back.
 */
object CotBuilder {
    const val DEFAULT_STALE_MS = 5 * 60 * 1000L
    /** An active alert is refreshed every 30 s by its author; this keeps it on the map through a few missed copies. */
    const val EMERGENCY_STALE_MS = 10 * 60 * 1000L
    private const val HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val RX_MARK = "<__takwave rx=\"1\"/>"
    private val TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    /**
     * [originMs] is when the author first sent the message (earlier than [nowMs] for a re-sent or
     * relayed copy). It becomes the event time; the stale time always counts from [nowMs], so a
     * late copy is not already stale when it arrives. Position reports are never repeated.
     */
    fun build(message: TakMessage, nowMs: Long, originMs: Long = nowMs): String = when (message) {
        is PliMessage -> pli(message, nowMs)
        is ChatMessage -> chat(message, nowMs, originMs = originMs)
        is MarkerMessage -> marker(message, nowMs, originMs)
        is EmergencyMessage -> emergency(message, nowMs, originMs)
    }

    /** Stale time is three reporting intervals after now, or five minutes when the interval is unknown. */
    fun pli(m: PliMessage, nowMs: Long): String {
        val staleMs = nowMs + (m.intervalS?.let { it * 3000L } ?: DEFAULT_STALE_MS)
        val sb = StringBuilder(512)
        openEvent(sb, m.uid, m.cotType, "m-g", nowMs, staleMs)
        point(sb, m.lat, m.lon, m.haeM?.toDouble(), m.ceM?.toDouble())
        sb.append("<detail>")
        sb.append("<contact callsign=\"").append(esc(m.callsign)).append("\" endpoint=\"*:-1:stcp\"/>")
        sb.append("<uid Droid=\"").append(esc(m.callsign)).append("\"/>")
        val team = if (m.team == Team.UNKNOWN) Team.CYAN else m.team
        val role = if (m.role == Role.UNKNOWN) Role.TEAM_MEMBER else m.role
        sb.append("<__group name=\"").append(team.cotName).append("\" role=\"").append(role.cotName).append("\"/>")
        if (m.battery != null) sb.append("<status battery=\"").append(m.battery).append("\"/>")
        if (m.speedDms != null || m.courseDeg != null) {
            sb.append("<track speed=\"").append(fmt1((m.speedDms ?: 0) / 10.0))
                .append("\" course=\"").append(fmt1((m.courseDeg ?: 0).toDouble())).append("\"/>")
        }
        sb.append(RX_MARK).append("</detail></event>")
        return sb.toString()
    }

    /**
     * GeoChat. The uid convention `GeoChat.<senderUid>.<room>.<messageId>` and the `__chat`,
     * `chatgrp`, `link` and `remarks` details are what ATAK needs to file the message under the
     * right conversation. [lat]/[lon] are the sender's last known position, if any. [originMs]
     * (when the message was written) is the event and remarks time.
     */
    fun chat(m: ChatMessage, nowMs: Long, lat: Double? = null, lon: Double? = null, originMs: Long = nowMs): String {
        val sb = StringBuilder(768)
        val uid = "GeoChat.${m.senderUid}.${m.roomId}.${m.messageId}"
        openEvent(sb, uid, CotEvent.TYPE_CHAT, "h-g-i-g-o", originMs, nowMs + DEFAULT_STALE_MS)
        point(sb, lat ?: 0.0, lon ?: 0.0, null, null)
        sb.append("<detail>")
        sb.append("<__chat parent=\"RootContactGroup\" groupOwner=\"false\" messageId=\"").append(esc(m.messageId))
            .append("\" chatroom=\"").append(esc(m.roomName))
            .append("\" id=\"").append(esc(m.roomId))
            .append("\" senderCallsign=\"").append(esc(m.senderCallsign)).append("\">")
        sb.append("<chatgrp uid0=\"").append(esc(m.senderUid))
            .append("\" uid1=\"").append(esc(m.roomId))
            .append("\" id=\"").append(esc(m.roomId)).append("\"/></__chat>")
        sb.append("<link uid=\"").append(esc(m.senderUid)).append("\" type=\"a-f-G-U-C\" relation=\"p-p\"/>")
        sb.append("<remarks source=\"BAO.F.ATAK.").append(esc(m.senderUid))
            .append("\" to=\"").append(esc(m.roomId))
            .append("\" time=\"").append(time(originMs)).append("\">")
            .append(esc(m.text)).append("</remarks>")
        sb.append(RX_MARK).append("</detail></event>")
        return sb.toString()
    }

    /** Point marker. `<archive/>` keeps it on the map after the stale time passes. */
    fun marker(m: MarkerMessage, nowMs: Long, originMs: Long = nowMs): String {
        val sb = StringBuilder(512)
        openEvent(sb, m.uid, m.cotType, "h-g-i-g-o", originMs, nowMs + DEFAULT_STALE_MS)
        point(sb, m.lat, m.lon, m.haeM?.toDouble(), null)
        sb.append("<detail>")
        sb.append("<contact callsign=\"").append(esc(m.name)).append("\"/>")
        if (m.remarks.isNullOrEmpty()) sb.append("<remarks/>") else sb.append("<remarks>").append(esc(m.remarks)).append("</remarks>")
        if (m.colorArgb != null) sb.append("<color argb=\"").append(m.colorArgb).append("\"/>")
        when {
            m.cotType == "b-m-p-s-m" && m.colorArgb != null ->
                sb.append("<usericon iconsetpath=\"COT_MAPPING_SPOTMAP/b-m-p-s-m/").append(m.colorArgb).append("\"/>")
            m.cotType.startsWith("a-") && m.cotType.length >= 3 ->
                sb.append("<usericon iconsetpath=\"COT_MAPPING_2525B/").append(esc(m.cotType.substring(0, 3)))
                    .append('/').append(esc(m.cotType)).append("\"/>")
        }
        sb.append("<archive/>").append(RX_MARK).append("</detail></event>")
        return sb.toString()
    }

    /**
     * Emergency alert or cancellation, laid out as ATAK sends them: a `b-a-*` event linked to the
     * person in distress, with an `<emergency>` detail naming the alert type.
     */
    fun emergency(m: EmergencyMessage, nowMs: Long, originMs: Long = nowMs): String {
        val sb = StringBuilder(512)
        openEvent(sb, m.uid, m.kind.cotType, "h-e", originMs, nowMs + EMERGENCY_STALE_MS)
        point(sb, m.lat, m.lon, m.haeM?.toDouble(), null)
        sb.append("<detail>")
        sb.append("<link uid=\"").append(esc(m.senderUid)).append("\" type=\"a-f-G-U-C\" relation=\"p-p\"/>")
        sb.append("<contact callsign=\"").append(esc(m.callsign)).append("\"/>")
        if (m.active) {
            sb.append("<emergency type=\"").append(esc(m.kind.label)).append("\">")
        } else {
            sb.append("<emergency cancel=\"true\">")
        }
        sb.append(esc(m.text)).append("</emergency>")
        sb.append(RX_MARK).append("</detail></event>")
        return sb.toString()
    }

    /** Reply to a client's `t-x-c-t` ping. */
    fun pong(nowMs: Long): String {
        val sb = StringBuilder(256)
        openEvent(sb, "takPong", CotEvent.TYPE_PONG, "h-g-i-g-o", nowMs, nowMs + 20_000)
        point(sb, 0.0, 0.0, 0.0, null)
        sb.append("</event>")
        return sb.toString()
    }

    private fun openEvent(sb: StringBuilder, uid: String, type: String, how: String, timeMs: Long, staleMs: Long) {
        val now = time(timeMs)
        sb.append(HEADER)
        sb.append("<event version=\"2.0\" uid=\"").append(esc(uid))
            .append("\" type=\"").append(esc(type))
            .append("\" time=\"").append(now)
            .append("\" start=\"").append(now)
            .append("\" stale=\"").append(time(staleMs))
            .append("\" how=\"").append(how).append("\">")
    }

    private fun point(sb: StringBuilder, lat: Double, lon: Double, hae: Double?, ce: Double?) {
        sb.append("<point lat=\"").append(String.format(Locale.ROOT, "%.7f", lat))
            .append("\" lon=\"").append(String.format(Locale.ROOT, "%.7f", lon))
            .append("\" hae=\"").append(fmt1(hae ?: CotEvent.UNKNOWN))
            .append("\" ce=\"").append(fmt1(ce ?: CotEvent.UNKNOWN))
            .append("\" le=\"").append(fmt1(CotEvent.UNKNOWN)).append("\"/>")
    }

    private fun fmt1(v: Double): String = String.format(Locale.ROOT, "%.1f", v)

    fun time(ms: Long): String = TIME.format(Instant.ofEpochMilli(ms))

    /** Escapes text for use in XML content or a double-quoted attribute; drops illegal control characters. */
    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '&'.code -> sb.append("&amp;")
                cp == '<'.code -> sb.append("&lt;")
                cp == '>'.code -> sb.append("&gt;")
                cp == '"'.code -> sb.append("&quot;")
                cp == '\''.code -> sb.append("&apos;")
                cp == 0x9 || cp == 0xA || cp == 0xD -> sb.append("&#").append(cp).append(';')
                cp < 0x20 || cp == 0xFFFE || cp == 0xFFFF || (cp in 0xD800..0xDFFF) -> { /* not legal in XML 1.0 */ }
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }
}
