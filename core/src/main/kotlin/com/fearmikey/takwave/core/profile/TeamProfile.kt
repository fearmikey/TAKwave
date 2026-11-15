// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.profile

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * The settings a team has to agree on, in a form that fits a QR code or a short text file:
 * position report pacing, channel access, time slots (with optional per-station slot numbers),
 * store-and-forward limits, FX.25, and which encryption mode and cipher to use.
 *
 * Never part of a profile: keys, passphrases, callsigns of the importing station, radio-specific
 * settings (audio devices, PTT, TXDELAY, levels), the TAK port, and the relay switch, which is
 * meant for one chosen station rather than the whole team.
 *
 * A null field is not part of the profile and leaves the importer's setting unchanged. Values
 * are validated against the same ranges as the settings screen; a profile with any value out of
 * range is refused as a whole rather than applied in part.
 */
data class TeamProfile(
    val name: String = "",
    val pliBaseS: Int? = null,
    val pliMinS: Int? = null,
    val pliDistanceM: Int? = null,
    val pliHeadingDeg: Int? = null,
    val csmaSlotMs: Int? = null,
    val persistence: Int? = null,
    val slotted: Boolean? = null,
    val slotLengthMs: Int? = null,
    val slotCount: Int? = null,
    /** Slot numbers, counted from 1, by station: `CALL` or `CALL-SSID`, upper case. */
    val slotAssignments: Map<String, Int> = emptyMap(),
    val resendOwn: Boolean? = null,
    val repeatHoldMin: Int? = null,
    val repeatMax: Int? = null,
    val repeatGapS: Int? = null,
    val repeatBudget: Int? = null,
    /** Encryption mode name as the app stores it (for example `OFF`, `PASSPHRASE`). Never key material. */
    val encryption: String? = null,
    val cipher: String? = null,
    /** FX.25 check bytes when transmitting: 0 (off), 16, 32 or 64. */
    val fx25CheckBytes: Int? = null,
) {
    /** The slot (counted from 1) assigned to [station] (`CALL-SSID`), trying the bare callsign too. */
    fun slotFor(station: String): Int? {
        val s = station.uppercase(Locale.ROOT)
        return slotAssignments[s] ?: slotAssignments[s.substringBefore('-')]
    }

    /** The profile as a `takwave://profile?...` link; also the content of a profile file. */
    fun toUri(): String {
        val p = ArrayList<Pair<String, String>>()
        p += "v" to VERSION.toString()
        if (name.isNotEmpty()) p += "name" to name
        pliBaseS?.let { p += "pli_base" to it.toString() }
        pliMinS?.let { p += "pli_min" to it.toString() }
        pliDistanceM?.let { p += "pli_dist" to it.toString() }
        pliHeadingDeg?.let { p += "pli_turn" to it.toString() }
        csmaSlotMs?.let { p += "csma_slot" to it.toString() }
        persistence?.let { p += "csma_p" to it.toString() }
        slotted?.let { p += "slots" to bool(it) }
        slotLengthMs?.let { p += "slot_len" to it.toString() }
        slotCount?.let { p += "slot_count" to it.toString() }
        if (slotAssignments.isNotEmpty()) {
            p += "assign" to slotAssignments.entries.sortedBy { it.value }.joinToString(";") { "${it.key}:${it.value}" }
        }
        resendOwn?.let { p += "resend" to bool(it) }
        repeatHoldMin?.let { p += "hold" to it.toString() }
        repeatMax?.let { p += "repeats" to it.toString() }
        repeatGapS?.let { p += "gap" to it.toString() }
        repeatBudget?.let { p += "budget" to it.toString() }
        encryption?.let { p += "enc" to it }
        cipher?.let { p += "cipher" to it }
        fx25CheckBytes?.let { p += "fx25" to it.toString() }
        return PREFIX + p.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }

    companion object {
        const val SCHEME = "takwave"
        const val HOST = "profile"
        const val PREFIX = "$SCHEME://$HOST?"
        const val VERSION = 1
        /** Longer input is refused before parsing. */
        const val MAX_LENGTH = 2048
        const val MAX_NAME = 40
        const val MAX_ASSIGNMENTS = 64

        val PLI_BASE = 10..3600
        val PLI_MIN = 5..600
        val PLI_DISTANCE = 5..10000
        val PLI_HEADING = 5..180
        val CSMA_SLOT = 10..1000
        val PERSISTENCE = 0..255
        val SLOT_LENGTH = 1000..30000
        val SLOT_COUNT = 1..64
        val REPEAT_HOLD = 5..240
        val REPEAT_MAX = 1..10
        val REPEAT_GAP = 30..1800
        val REPEAT_BUDGET = 1..30
        val FX25_CHOICES = setOf(0, 16, 32, 64)

        private val STATION = Regex("[A-Z0-9]{1,6}(-([0-9]|1[0-5]))?")
        private val IDENTIFIER = Regex("[A-Z0-9_]{1,32}")

        private fun bool(b: Boolean) = if (b) "1" else "0"

        /**
         * Reads a profile from a link or file content. Surrounding text and whitespace are
         * ignored, so a link pasted from a message works. Throws [IllegalArgumentException] with
         * a reason a person can act on.
         */
        fun parse(text: String): TeamProfile {
            require(text.length <= MAX_LENGTH) { "too long to be a TAKwave profile" }
            val start = text.indexOf(PREFIX, ignoreCase = true)
            require(start >= 0) { "not a TAKwave profile (it should start with $PREFIX)" }
            val query = text.substring(start + PREFIX.length).trim().takeWhile { !it.isWhitespace() }
            val params = LinkedHashMap<String, String>()
            for (part in query.split('&')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                val key = if (eq < 0) part else part.substring(0, eq)
                val value = if (eq < 0) "" else try {
                    // The String overload: the Charset one needs Android 13.
                    URLDecoder.decode(part.substring(eq + 1), "UTF-8")
                } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException("the value of '$key' is garbled")
                }
                // The first occurrence wins; a later duplicate cannot override it.
                params.putIfAbsent(key, value)
            }
            val version = params["v"]?.toIntOrNull() ?: throw IllegalArgumentException("the profile has no version")
            require(version <= VERSION) { "made by a newer TAKwave version; update TAKwave first" }
            require(version >= 1) { "unknown profile version $version" }

            fun int(key: String, range: IntRange): Int? {
                val raw = params[key] ?: return null
                val v = raw.toIntOrNull() ?: throw IllegalArgumentException("'$key' is not a number")
                require(v in range) { "'$key' is $v; it must be ${range.first} to ${range.last}" }
                return v
            }

            fun flag(key: String): Boolean? = when (params[key]) {
                null -> null
                "1" -> true
                "0" -> false
                else -> throw IllegalArgumentException("'$key' must be 0 or 1")
            }

            fun identifier(key: String): String? {
                val raw = params[key] ?: return null
                require(IDENTIFIER.matches(raw)) { "'$key' is not a valid setting name" }
                return raw
            }

            val slotCount = int("slot_count", SLOT_COUNT)
            val fx25 = int("fx25", 0..64)
            require(fx25 == null || fx25 in FX25_CHOICES) { "'fx25' must be 0, 16, 32 or 64" }
            return TeamProfile(
                name = cleanName(params["name"] ?: ""),
                pliBaseS = int("pli_base", PLI_BASE),
                pliMinS = int("pli_min", PLI_MIN),
                pliDistanceM = int("pli_dist", PLI_DISTANCE),
                pliHeadingDeg = int("pli_turn", PLI_HEADING),
                csmaSlotMs = int("csma_slot", CSMA_SLOT),
                persistence = int("csma_p", PERSISTENCE),
                slotted = flag("slots"),
                slotLengthMs = int("slot_len", SLOT_LENGTH),
                slotCount = slotCount,
                slotAssignments = parseAssignments(params["assign"] ?: "", slotCount ?: SLOT_COUNT.last),
                resendOwn = flag("resend"),
                repeatHoldMin = int("hold", REPEAT_HOLD),
                repeatMax = int("repeats", REPEAT_MAX),
                repeatGapS = int("gap", REPEAT_GAP),
                repeatBudget = int("budget", REPEAT_BUDGET),
                encryption = identifier("enc"),
                cipher = identifier("cipher"),
                fx25CheckBytes = fx25,
            )
        }

        /**
         * Reads slot assignments typed by a person or stored in a link: entries separated by
         * new lines, commas or semicolons, each `CALL=slot`, `CALL-SSID=slot` or with `:`.
         * Slots count from 1 and must not exceed [slotCount].
         */
        fun parseAssignments(text: String, slotCount: Int): Map<String, Int> {
            val out = LinkedHashMap<String, Int>()
            for (raw in text.split('\n', ',', ';')) {
                val entry = raw.trim()
                if (entry.isEmpty()) continue
                val sep = entry.indexOfAny(charArrayOf('=', ':'))
                require(sep > 0) { "slot assignment '$entry' should look like N0CALL=1" }
                val station = entry.substring(0, sep).trim().uppercase(Locale.ROOT)
                require(STATION.matches(station)) { "'$station' is not a callsign (with an optional -SSID)" }
                val slot = entry.substring(sep + 1).trim().toIntOrNull()
                    ?: throw IllegalArgumentException("the slot for $station is not a number")
                require(slot in 1..slotCount) { "the slot for $station must be 1 to $slotCount" }
                require(station !in out) { "$station is assigned twice" }
                out[station] = slot
                require(out.size <= MAX_ASSIGNMENTS) { "at most $MAX_ASSIGNMENTS slot assignments" }
            }
            return out
        }

        /** Profile names are shown in a dialog: keep them short and free of control characters. */
        fun cleanName(name: String): String =
            name.filter { !it.isISOControl() }.trim().take(MAX_NAME)
    }
}
