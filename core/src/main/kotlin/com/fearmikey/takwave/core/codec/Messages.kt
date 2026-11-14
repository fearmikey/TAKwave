// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.codec

/** The 14 ATAK team colours. The ordinal is the on-air code; never reorder, only append. */
enum class Team(val cotName: String) {
    UNKNOWN(""),
    WHITE("White"),
    YELLOW("Yellow"),
    ORANGE("Orange"),
    MAGENTA("Magenta"),
    RED("Red"),
    MAROON("Maroon"),
    PURPLE("Purple"),
    DARK_BLUE("Dark Blue"),
    BLUE("Blue"),
    CYAN("Cyan"),
    TEAL("Teal"),
    GREEN("Green"),
    DARK_GREEN("Dark Green"),
    BROWN("Brown");

    companion object {
        fun fromCotName(name: String?): Team = entries.firstOrNull { it != UNKNOWN && it.cotName.equals(name, true) } ?: UNKNOWN
        fun fromCode(code: Int): Team = entries.getOrElse(code) { UNKNOWN }
    }
}

/** The standard ATAK team roles. The ordinal is the on-air code; never reorder, only append. */
enum class Role(val cotName: String) {
    UNKNOWN(""),
    TEAM_MEMBER("Team Member"),
    TEAM_LEAD("Team Lead"),
    HQ("HQ"),
    SNIPER("Sniper"),
    MEDIC("Medic"),
    FORWARD_OBSERVER("Forward Observer"),
    RTO("RTO"),
    K9("K9");

    companion object {
        fun fromCotName(name: String?): Role = entries.firstOrNull { it != UNKNOWN && it.cotName.equals(name, true) } ?: UNKNOWN
        fun fromCode(code: Int): Role = entries.getOrElse(code) { UNKNOWN }
    }
}

/** A message that can travel over the air. Numeric fields hold the exact on-air quantisation. */
sealed interface TakMessage {
    /** Identity used for loop prevention and queue replacement. */
    val key: String
}

/**
 * Position report.
 *
 * @param latE7 latitude in degrees x 1e7
 * @param lonE7 longitude in degrees x 1e7
 * @param haeM height above ellipsoid in metres, null when unknown
 * @param ceM circular error in metres, null when unknown
 * @param speedDms ground speed in units of 0.1 m/s, null when unknown
 * @param courseDeg course in whole degrees 0-359, null when unknown
 * @param battery percent 0-100, null when unknown
 * @param intervalS the sender's nominal reporting interval in seconds (multiple of 5), null when unknown
 */
data class PliMessage(
    val uid: String,
    val callsign: String,
    val cotType: String,
    val latE7: Int,
    val lonE7: Int,
    val haeM: Int? = null,
    val ceM: Int? = null,
    val speedDms: Int? = null,
    val courseDeg: Int? = null,
    val team: Team = Team.UNKNOWN,
    val role: Role = Role.UNKNOWN,
    val battery: Int? = null,
    val intervalS: Int? = null,
) : TakMessage {
    override val key: String get() = uid
    val lat: Double get() = latE7 / 1e7
    val lon: Double get() = lonE7 / 1e7
}

data class ChatMessage(
    val messageId: String,
    val senderUid: String,
    val senderCallsign: String,
    val roomId: String,
    val roomName: String,
    val text: String,
) : TakMessage {
    override val key: String get() = messageId

    companion object {
        const val ALL_CHAT_ROOMS = "All Chat Rooms"
    }
}

data class MarkerMessage(
    val uid: String,
    val cotType: String,
    val name: String,
    val latE7: Int,
    val lonE7: Int,
    val haeM: Int? = null,
    val remarks: String? = null,
    val colorArgb: Int? = null,
) : TakMessage {
    override val key: String get() = uid
    val lat: Double get() = latE7 / 1e7
    val lon: Double get() = lonE7 / 1e7
}

/** Outcome of decoding an over-air payload. Decoding never throws. */
sealed interface DecodeResult {
    data class Ok(val message: TakMessage, val encrypted: Boolean) : DecodeResult
    data class Error(val reason: String) : DecodeResult
}
