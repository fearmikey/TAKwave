// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.radio

/** How TAKwave can use a radio or TNC recognised by its Bluetooth name. */
enum class BluetoothRadioUse {
    /** Built-in or external KISS TNC on a classic Bluetooth serial (SPP) link. */
    KISS_CLASSIC,

    /** KISS TNC on the Bluetooth LE KISS service. */
    KISS_BLE,

    /** Icom D-STAR radio whose Bluetooth serial port can carry DV data (and CI-V). */
    DSTAR_DATA,
}

/** A recognised device: a readable model name and the best way to use it. */
data class BluetoothRadioMatch(val model: String, val use: BluetoothRadioUse)

/**
 * Recognises radios and TNCs that TAKwave supports from the name they advertise over Bluetooth,
 * so the settings can suggest the right backend. Advice only: nothing is switched without the
 * operator, and an unknown name simply gets no suggestion.
 */
object BluetoothRadios {
    private class Entry(val patterns: List<String>, val match: BluetoothRadioMatch)

    private fun entry(model: String, use: BluetoothRadioUse, vararg patterns: String) =
        Entry(patterns.map { normalize(it) }, BluetoothRadioMatch(model, use))

    // First match wins, so more specific names come first (TNC4 before the generic Mobilinkd name).
    private val entries = listOf(
        entry("BTECH UV-PRO", BluetoothRadioUse.KISS_CLASSIC, "UV-PRO"),
        entry("Radioddity GA-5WB", BluetoothRadioUse.KISS_CLASSIC, "GA-5WB"),
        entry("Vero VR-N76 / VR-N7500", BluetoothRadioUse.KISS_CLASSIC, "VR-N76", "VR-N7500"),
        entry("Kenwood TH-D74", BluetoothRadioUse.KISS_CLASSIC, "TH-D74"),
        entry("Kenwood TH-D75", BluetoothRadioUse.KISS_CLASSIC, "TH-D75"),
        entry("Mobilinkd TNC4", BluetoothRadioUse.KISS_BLE, "TNC4"),
        entry("Mobilinkd TNC", BluetoothRadioUse.KISS_CLASSIC, "Mobilinkd", "TNC3"),
        entry("Icom IC-705", BluetoothRadioUse.DSTAR_DATA, "IC-705"),
        entry("Icom ID-52", BluetoothRadioUse.DSTAR_DATA, "ID-52"),
    )

    /** The supported device whose Bluetooth [name] this is, or null when it is not recognised. */
    fun match(name: String?): BluetoothRadioMatch? {
        if (name.isNullOrBlank()) return null
        val n = normalize(name)
        return entries.firstOrNull { e -> e.patterns.any { n.contains(it) } }?.match
    }

    /** Upper case without spaces or punctuation, so "UV-PRO", "UV PRO" and "uvpro" compare equal. */
    private fun normalize(s: String): String = s.uppercase().filter { it.isLetterOrDigit() }
}
