// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.cot

import com.fearmikey.takwave.core.codec.ChatMessage
import com.fearmikey.takwave.core.codec.MarkerMessage
import com.fearmikey.takwave.core.codec.PliMessage
import com.fearmikey.takwave.core.codec.Role
import com.fearmikey.takwave.core.codec.TakMessage
import com.fearmikey.takwave.core.codec.Team
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** What a CoT event from a TAK client turned out to be. */
sealed interface Classified {
    /** Something that may go over the air. */
    data class Send(val message: TakMessage) : Classified
    /** A `t-x-c-t` keep-alive; answered locally, never transmitted. */
    data object Ping : Classified
    /** Not eligible for RF. */
    data class Drop(val reason: String) : Classified
}

/** Decides which client CoT events go over the air and converts them to wire messages. */
object CotMapper {

    /** @param pliIntervalS the local base PLI interval, advertised so receivers can set stale times */
    fun classify(e: CotEvent, pliIntervalS: Int? = null): Classified {
        if (e.fromRf) return Classified.Drop("originated from RF")
        if (e.type == CotEvent.TYPE_PING) return Classified.Ping
        return when {
            e.type == CotEvent.TYPE_CHAT -> chat(e)
            e.type.startsWith("b-m-p") -> Classified.Send(marker(e))
            e.type.startsWith("a-") -> when {
                // A self-report carries the client's software and team details.
                e.hasTakv || e.groupName != null -> Classified.Send(pli(e, pliIntervalS))
                // A marker the user dropped by hand.
                e.how.startsWith("h-g") || e.hasUserIcon || e.hasArchive -> Classified.Send(marker(e))
                else -> Classified.Drop("atom '${e.type}' is neither a self-report nor a user marker")
            }
            else -> Classified.Drop("type '${e.type}' is not carried over RF")
        }
    }

    private fun pli(e: CotEvent, intervalS: Int?): PliMessage = PliMessage(
        uid = e.uid,
        callsign = e.callsign ?: "",
        cotType = e.type,
        latE7 = e7(e.lat),
        lonE7 = e7(e.lon),
        haeM = metres(e.hae, 32767.0),
        ceM = metres(e.ce, 65534.0)?.let { abs(it) },
        speedDms = e.speed?.takeIf { it >= 0 && it < 6553.4 }?.let { (it * 10).roundToInt() },
        courseDeg = e.course?.takeIf { it.isFinite() }?.let { ((it.roundToInt() % 360) + 360) % 360 },
        team = Team.fromCotName(e.groupName),
        role = Role.fromCotName(e.groupRole),
        battery = e.battery?.takeIf { it in 0..100 },
        intervalS = intervalS,
    )

    private fun marker(e: CotEvent): MarkerMessage = MarkerMessage(
        uid = e.uid,
        cotType = e.type,
        name = e.callsign ?: "",
        latE7 = e7(e.lat),
        lonE7 = e7(e.lon),
        haeM = metres(e.hae, 32767.0),
        remarks = e.remarks?.trim()?.takeIf { it.isNotEmpty() },
        colorArgb = e.colorArgb,
    )

    private fun chat(e: CotEvent): Classified {
        val c = e.chat
        // Fall back to the uid convention GeoChat.<senderUid>.<room>.<messageId>.
        val parts = if (e.uid.startsWith("GeoChat.")) e.uid.split('.') else emptyList()
        val senderUid = c?.senderUid ?: e.linkUid ?: parts.getOrNull(1)
        val messageId = c?.messageId ?: parts.lastOrNull()?.takeIf { parts.size >= 4 }
        val roomId = c?.id ?: c?.chatroom
        val text = e.remarks
        if (senderUid.isNullOrEmpty() || messageId.isNullOrEmpty() || roomId.isNullOrEmpty() || text == null) {
            return Classified.Drop("incomplete GeoChat event")
        }
        return Classified.Send(
            ChatMessage(
                messageId = messageId,
                senderUid = senderUid,
                senderCallsign = c?.senderCallsign ?: "",
                roomId = roomId,
                roomName = c?.chatroom ?: roomId,
                text = text,
            ),
        )
    }

    private fun e7(deg: Double): Int = (deg * 1e7).roundToLong().toInt()

    /** CoT uses 9999999.0 for "unknown"; anything outside the wire range is treated the same way. */
    private fun metres(v: Double, max: Double): Int? =
        if (!v.isFinite() || abs(v) > max) null else v.roundToInt()
}
