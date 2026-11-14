// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.cot

/** The parts of a Cursor-on-Target event that TAKwave cares about. Times are epoch milliseconds. */
data class CotEvent(
    val uid: String,
    val type: String,
    val how: String = "",
    val timeMs: Long = 0,
    val startMs: Long = 0,
    val staleMs: Long = 0,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val hae: Double = UNKNOWN,
    val ce: Double = UNKNOWN,
    val le: Double = UNKNOWN,
    val callsign: String? = null,
    val hasTakv: Boolean = false,
    val groupName: String? = null,
    val groupRole: String? = null,
    val battery: Int? = null,
    val speed: Double? = null,
    val course: Double? = null,
    val remarks: String? = null,
    val chat: ChatDetail? = null,
    val linkUid: String? = null,
    val colorArgb: Int? = null,
    val hasUserIcon: Boolean = false,
    val hasArchive: Boolean = false,
    /** True when the event carries the `<__takwave>` marker, meaning TAKwave built it from RF. */
    val fromRf: Boolean = false,
) {
    companion object {
        /** The CoT convention for "unknown" altitude and error values. */
        const val UNKNOWN = 9999999.0
        const val TYPE_PING = "t-x-c-t"
        const val TYPE_PONG = "t-x-c-t-r"
        const val TYPE_CHAT = "b-t-f"
    }
}

/** The `<__chat>` detail of a GeoChat event. */
data class ChatDetail(
    val id: String?,
    val chatroom: String?,
    val senderCallsign: String?,
    val messageId: String?,
    /** `chatgrp/@uid0`: the sender's uid. */
    val senderUid: String?,
)
