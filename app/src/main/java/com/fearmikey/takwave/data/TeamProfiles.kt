// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.data

import com.fearmikey.takwave.core.profile.TeamProfile

/** The team-wide part of these settings as a profile. Keys, passphrases and radio-specific settings are never included. */
fun Settings.toTeamProfile(name: String, assignments: Map<String, Int>): TeamProfile = TeamProfile(
    name = TeamProfile.cleanName(name),
    pliBaseS = pliBaseS,
    pliMinS = pliMinS,
    pliDistanceM = pliDistanceM,
    pliHeadingDeg = pliHeadingDeg,
    csmaSlotMs = slotTimeMs,
    persistence = persistence,
    slotted = slotted,
    slotLengthMs = slotLengthMs,
    slotCount = slotCount,
    slotAssignments = assignments,
    resendOwn = resendOwn,
    repeatHoldMin = repeatHoldMin,
    repeatMax = repeatMax,
    repeatGapS = repeatGapS,
    repeatBudget = repeatBudget,
    encryption = encryption.name,
    cipher = cipher.name,
    hideCallsign = hideCallsign,
    fx25CheckBytes = fx25.checkBytes,
)

/**
 * These settings with [profile] applied. Fields the profile leaves out are kept. When the
 * profile assigns slots, this station takes its own slot if listed. Throws
 * [IllegalArgumentException] for an encryption mode or cipher this version does not know.
 */
fun Settings.withTeamProfile(profile: TeamProfile): Settings {
    val enc = profile.encryption?.let { name ->
        EncryptionMode.entries.firstOrNull { it.name == name } ?: throw IllegalArgumentException("unknown encryption mode '$name'")
    }
    val ciph = profile.cipher?.let { name ->
        CipherChoice.entries.firstOrNull { it.name == name } ?: throw IllegalArgumentException("unknown cipher '$name'")
    }
    val fx = profile.fx25CheckBytes?.let { Fx25Mode.of(it) ?: throw IllegalArgumentException("unknown FX.25 setting $it") }
    val count = profile.slotCount ?: slotCount
    val ownSlot = address?.let { profile.slotFor(it.toString()) }
    return copy(
        pliBaseS = profile.pliBaseS ?: pliBaseS,
        pliMinS = profile.pliMinS ?: pliMinS,
        pliDistanceM = profile.pliDistanceM ?: pliDistanceM,
        pliHeadingDeg = profile.pliHeadingDeg ?: pliHeadingDeg,
        slotTimeMs = profile.csmaSlotMs ?: slotTimeMs,
        persistence = profile.persistence ?: persistence,
        slotted = profile.slotted ?: slotted,
        slotLengthMs = profile.slotLengthMs ?: slotLengthMs,
        slotCount = count,
        // A slot number that no longer fits a smaller cycle falls back to "choose from callsign".
        slotIndex = when {
            ownSlot != null -> ownSlot - 1
            slotIndex >= count -> -1
            else -> slotIndex
        },
        resendOwn = profile.resendOwn ?: resendOwn,
        repeatHoldMin = profile.repeatHoldMin ?: repeatHoldMin,
        repeatMax = profile.repeatMax ?: repeatMax,
        repeatGapS = profile.repeatGapS ?: repeatGapS,
        repeatBudget = profile.repeatBudget ?: repeatBudget,
        encryption = enc ?: encryption,
        cipher = ciph ?: cipher,
        hideCallsign = profile.hideCallsign ?: hideCallsign,
        fx25 = fx ?: fx25,
    )
}

/** One line per setting that differs between [old] and [new], for the confirmation dialog. */
fun profileChanges(old: Settings, new: Settings): List<String> = buildList {
    fun <T> diff(label: String, a: T, b: T, show: (T) -> String = { it.toString() }) {
        if (a != b) add("$label: ${show(a)} → ${show(b)}")
    }
    val onOff: (Boolean) -> String = { if (it) "on" else "off" }
    diff("PLI base interval (s)", old.pliBaseS, new.pliBaseS)
    diff("PLI minimum interval (s)", old.pliMinS, new.pliMinS)
    diff("PLI early after moving (m)", old.pliDistanceM, new.pliDistanceM)
    diff("PLI early after turning (degrees)", old.pliHeadingDeg, new.pliHeadingDeg)
    diff("CSMA slot time (ms)", old.slotTimeMs, new.slotTimeMs)
    diff("CSMA persistence", old.persistence, new.persistence)
    diff("Time slots", old.slotted, new.slotted, onOff)
    diff("Slot length (ms)", old.slotLengthMs, new.slotLengthMs)
    diff("Slots per cycle", old.slotCount, new.slotCount)
    diff("My slot number", old.slotIndex, new.slotIndex) { if (it < 0) "from callsign" else (it + 1).toString() }
    diff("Send my messages again", old.resendOwn, new.resendOwn, onOff)
    diff("Keep messages for (minutes)", old.repeatHoldMin, new.repeatHoldMin)
    diff("Repeats per message", old.repeatMax, new.repeatMax)
    diff("Gap between repeats (s)", old.repeatGapS, new.repeatGapS)
    diff("Repeats per 10 minutes", old.repeatBudget, new.repeatBudget)
    diff("FX.25", old.fx25, new.fx25) { it.label }
    diff("Encryption", old.encryption, new.encryption) { it.label }
    if (new.encryption != EncryptionMode.OFF && new.encryption != EncryptionMode.ECIES_TEAM) {
        diff("Cipher", old.cipher, new.cipher) { it.label }
    }
    if (new.encryption != EncryptionMode.OFF) diff("Encrypt callsign too", old.hideCallsign, new.hideCallsign, onOff)
}
