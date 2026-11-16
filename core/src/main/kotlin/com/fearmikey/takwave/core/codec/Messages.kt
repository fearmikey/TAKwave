// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.codec

/** The 14 ATAK team colors. The ordinal is the on-air code; never reorder, only append. */
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

/** A message that can travel over the air. Numeric fields hold the exact on-air quantization. */
sealed interface TakMessage {
    /** Identity used for loop prevention and queue replacement. */
    val key: String
}

/**
 * Position report.
 *
 * @param latE7 latitude in degrees x 1e7
 * @param lonE7 longitude in degrees x 1e7
 * @param haeM height above ellipsoid in meters, null when unknown
 * @param ceM circular error in meters, null when unknown
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

/** ATAK emergency alert kinds. The ordinal is the on-air code; never reorder, only append. */
enum class EmergencyKind(val cotType: String, val label: String) {
    CANCEL("b-a-o-can", "Cancel"),
    ALERT_911("b-a-o-tbl", "911 Alert"),
    RING_THE_BELL("b-a-o-pan", "Ring The Bell"),
    IN_CONTACT("b-a-o-opn", "In Contact"),
    GEOFENCE_BREACHED("b-a-g", "Geo-fence Breached"),
    CUSTOM("b-a-o-c", "Custom");

    companion object {
        fun fromCotType(type: String): EmergencyKind? = entries.firstOrNull { it.cotType == type }

        /** A code from a newer sender is shown as a custom alert rather than dropped: an alert must never be lost. */
        fun fromCode(code: Int): EmergencyKind = entries.getOrElse(code) { CUSTOM }
    }
}

/**
 * An ATAK emergency alert (911, Ring The Bell, In Contact, ...) or its cancellation.
 *
 * @param uid the alert's event uid; ATAK uses `<senderUid>-9-1-1`, and the cancel reuses it
 * @param senderUid the uid of the person raising the alert
 * @param callsign the alert's contact callsign (ATAK writes `<callsign>-Alert`)
 * @param text the text of the `<emergency>` element (ATAK writes the sender's callsign)
 */
data class EmergencyMessage(
    val uid: String,
    val senderUid: String,
    val kind: EmergencyKind,
    val callsign: String,
    val text: String,
    val latE7: Int,
    val lonE7: Int,
    val haeM: Int? = null,
) : TakMessage {
    override val key: String get() = uid
    /** False for a cancellation. */
    val active: Boolean get() = kind != EmergencyKind.CANCEL
    val lat: Double get() = latE7 / 1e7
    val lon: Double get() = lonE7 / 1e7

    companion object {
        /** ATAK's alert uid is the sender's uid with this suffix; such uids are not sent twice. */
        const val UID_SUFFIX = "-9-1-1"
    }
}

/** Outcome of decoding an over-air payload. Decoding never throws. */
sealed interface DecodeResult {
    /**
     * @param hops how many times the packet was relayed by other stations; 0 when heard from its author
     * @param ageS seconds since the author first sent the message; 0 for a first transmission
     * @param seq the sender's position report counter (PROTOCOL.md 4.4), or null when absent
     * @param hiddenSource the sending station's address from inside the ciphertext, for a
     *   hidden-source packet (PROTOCOL.md 6); null when the AX.25 source is the real one
     */
    data class Ok(
        val message: TakMessage,
        val encrypted: Boolean,
        val hops: Int = 0,
        val ageS: Int = 0,
        val seq: Int? = null,
        val hiddenSource: com.fearmikey.takwave.core.ax25.Ax25Address? = null,
    ) : DecodeResult
    data class Error(val reason: String) : DecodeResult
}
