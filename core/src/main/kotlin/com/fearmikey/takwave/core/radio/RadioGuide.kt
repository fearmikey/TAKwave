// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.radio

import java.util.Locale

/*
 * Recommended radio settings for running TAKwave on VHF and UHF FM: 2 m and 70 cm amateur, or
 * Part 90 business band. Advice only. TAKwave cannot tune the radio; the operator sets the
 * channel on it. Sources: US rules (47 CFR 90.35 itinerant channels, the Part 90 narrowbanding
 * rule) and the ARRL band plans. Elsewhere the national band plan applies.
 */

/** The radio service, which decides the bands, channel width rules and frequencies. */
enum class RadioService { AMATEUR, BUSINESS }

/** VHF is 2 m (amateur) or 150-174 MHz (business); UHF is 70 cm or 450-470 MHz. */
enum class Band { VHF, UHF }

/**
 * FM channel width. [dataDeviationHz] is the deviation to aim for with Bell 202 AFSK: about 60% of
 * [maxDeviationHz], which leaves room for the tone level differences of real radios.
 */
enum class ChannelWidth(val spacingKhz: String, val maxDeviationHz: Int, val dataDeviationHz: Int) {
    NARROW("12.5", 2500, 1500),
    WIDE("25", 5000, 3000),
}

/** Where the team operates. Picks the band and the FX.25 suggestion. */
enum class Terrain { OPEN, HILLS, MOUNTAINS, FOREST, URBAN, INDOOR }

/** One frequency, or a segment from [lowMhz] to [highMhz]. */
data class FrequencyRange(val lowMhz: Double, val highMhz: Double = lowMhz) {
    init {
        require(highMhz >= lowMhz) { "range ends below its start" }
    }

    val label: String get() = if (highMhz == lowMhz) mhz(lowMhz) else "${mhz(lowMhz)}-${mhz(highMhz)}"

    private fun mhz(v: Double): String {
        // Four decimals for offset channels (151.5125), otherwise the usual three (145.010).
        val s = String.format(Locale.ROOT, "%.4f", v)
        return if (s.endsWith("0")) s.dropLast(1) else s
    }
}

/** A suggested channel (or channels to stay off), with the reason. */
data class Channel(val frequencies: List<FrequencyRange>, val note: String = "") {
    val label: String get() = frequencies.joinToString(", ") { it.label } + " MHz"
}

/** Everything the radio guide shows for one combination of choices. */
data class RadioAdvice(
    val service: RadioService,
    val band: Band,
    val terrain: Terrain,
    val recommendedBand: Band,
    val bandReason: String,
    /** The width in effect: always [ChannelWidth.NARROW] for business band. */
    val width: ChannelWidth,
    /** True when the rules leave no choice of width. */
    val widthFixed: Boolean,
    val widthReason: String,
    val channels: List<Channel>,
    val frequencyNote: String,
    val avoid: List<Channel>,
    /** Suggested FX.25 check bytes for this terrain (0 = off). */
    val fx25CheckBytes: Int,
    /** Antenna and placement advice for this terrain. */
    val terrainTips: List<String>,
)

object RadioGuide {

    fun bandLabel(service: RadioService, band: Band): String = when (service) {
        RadioService.AMATEUR -> if (band == Band.VHF) "2 m (144-148 MHz)" else "70 cm (420-450 MHz)"
        RadioService.BUSINESS -> if (band == Band.VHF) "VHF (150-174 MHz)" else "UHF (450-470 MHz)"
    }

    /** The widths the service allows. Part 90 has been narrowband-only since 2013. */
    fun allowedWidths(service: RadioService): List<ChannelWidth> = when (service) {
        RadioService.AMATEUR -> ChannelWidth.entries
        RadioService.BUSINESS -> listOf(ChannelWidth.NARROW)
    }

    fun defaultWidth(service: RadioService): ChannelWidth = when (service) {
        RadioService.AMATEUR -> ChannelWidth.WIDE
        RadioService.BUSINESS -> ChannelWidth.NARROW
    }

    /** [width] if the service allows it, otherwise the service's default. */
    fun effectiveWidth(service: RadioService, width: ChannelWidth): ChannelWidth =
        if (width in allowedWidths(service)) width else defaultWidth(service)

    fun recommendedBand(terrain: Terrain): Band = when (terrain) {
        Terrain.OPEN, Terrain.HILLS, Terrain.MOUNTAINS, Terrain.FOREST -> Band.VHF
        Terrain.URBAN, Terrain.INDOOR -> Band.UHF
    }

    fun bandReason(terrain: Terrain): String = when (terrain) {
        Terrain.OPEN ->
            "In open country, range depends on line of sight to the horizon. With the same size of antenna, VHF " +
                "loses a little less signal over distance, so it reaches slightly further. UHF also works well here."
        Terrain.HILLS ->
            "VHF bends over hill crests and ridges better than UHF, so it reaches stations that are just out of sight."
        Terrain.MOUNTAINS ->
            "VHF bends over ridges better than UHF. Even so, height matters more than the band here: get one " +
                "station up high."
        Terrain.FOREST ->
            "Leaves and wet wood absorb UHF noticeably more than VHF, so VHF goes further through dense foliage."
        Terrain.URBAN ->
            "UHF's shorter wavelength gets through gaps between buildings and reflects along streets better, and " +
                "its shorter antennas suit handhelds."
        Terrain.INDOOR ->
            "UHF gets into and out of buildings, through windows and doorways and around corners, better than VHF."
    }

    /** Suggested FX.25 check bytes: more where multipath and obstructions damage packets. */
    fun fx25For(terrain: Terrain): Int = when (terrain) {
        Terrain.OPEN, Terrain.HILLS -> 16
        Terrain.MOUNTAINS, Terrain.FOREST, Terrain.URBAN, Terrain.INDOOR -> 32
    }

    fun terrainTips(terrain: Terrain): List<String> = when (terrain) {
        Terrain.OPEN -> listOf(
            "The horizon limits range. Raising an antenna from 1.5 m to 6 m roughly doubles the distance to the " +
                "horizon. Height helps more than extra power.",
        )
        Terrain.HILLS -> listOf(
            "Stop on crests and forward slopes, not in valleys.",
            "Park one station on high ground and turn on Relay for other stations there (Settings > Channel > " +
                "Store and forward).",
        )
        Terrain.MOUNTAINS -> listOf(
            "Line of sight decides almost everything. Put one relay station (Settings > Channel > Store and " +
                "forward) on a high point that sees the whole team.",
            "Rock faces cause reflections that damage packets. FX.25 repairs some of that damage.",
        )
        Terrain.FOREST -> listOf(
            "Get antennas above the canopy where you can, and keep them away from tree trunks.",
            "Wet foliage costs more range than dry. Expect shorter range after rain.",
        )
        Terrain.URBAN -> listOf(
            "Reflections cause deep fades that change every few meters. If packets fail, move a few steps.",
            "A relay station on a rooftop or a high floor covers a lot of ground.",
        )
        Terrain.INDOOR -> listOf(
            "Work near windows, away from metal and concrete walls.",
            "Radios inside vehicles need an external antenna.",
        )
    }

    /** Radio settings that matter for data, whatever the band. */
    val radioChecklist: List<String> = listOf(
        "Every radio on the team: same frequency, same channel width, same CTCSS/DCS tone (or none).",
        "Squelch very low or open (see Calibrate levels). TAKwave finds packets itself.",
        "Battery saver off. It puts the receiver to sleep and misses the start of packets.",
        "Dual watch / dual standby off. The radio leaves the data channel to check the other one.",
        "Busy channel lockout off. TAKwave does its own channel access, and the lockout can silently block " +
            "a transmission that TAKwave thinks went out.",
        "Roger beep, key beeps, VOX (unless VOX is your PTT method), scrambler and noise reduction off.",
        "The radio's transmit time-out timer longer than TAKwave's TX timeout.",
        "Use the lowest power that reliably reaches the team. A better antenna or more height beats more power.",
    )

    fun advise(service: RadioService, band: Band, width: ChannelWidth, terrain: Terrain): RadioAdvice {
        val effective = effectiveWidth(service, width)
        val (channels, note, avoid) = frequencies(service, band)
        return RadioAdvice(
            service = service,
            band = band,
            terrain = terrain,
            recommendedBand = recommendedBand(terrain),
            bandReason = bandReason(terrain),
            width = effective,
            widthFixed = allowedWidths(service).size == 1,
            widthReason = widthReason(service, effective),
            channels = channels,
            frequencyNote = note,
            avoid = avoid,
            fx25CheckBytes = fx25For(terrain),
            terrainTips = terrainTips(terrain),
        )
    }

    private const val MISMATCH =
        " A wide transmitter heard on a narrow receiver sounds distorted and clipped. A narrow transmitter heard " +
            "on a wide receiver sounds quiet. Either way packets decode badly, so every radio must use the same width."

    private fun widthReason(service: RadioService, width: ChannelWidth): String = when {
        service == RadioService.BUSINESS ->
            "Since 1 January 2013, Part 90 business stations in 150-174 MHz and 421-512 MHz must use 12.5 kHz " +
                "(narrowband) channels. Set every radio's channel to narrow." + MISMATCH
        width == ChannelWidth.WIDE ->
            "Wide (25 kHz, 5 kHz deviation) is the usual setting for 2 m and 70 cm FM and gives the modem the " +
                "strongest audio. Narrow also works if the whole team uses it." + MISMATCH
        else ->
            "Narrow works when every radio on the team is set to narrow. It gives a few dB less signal-to-noise " +
                "than wide, so range is slightly shorter." + MISMATCH
    }

    private fun f(vararg mhz: Double) = mhz.map { FrequencyRange(it) }
    private fun r(low: Double, high: Double) = FrequencyRange(low, high)

    private data class Frequencies(val channels: List<Channel>, val note: String, val avoid: List<Channel>)

    private fun frequencies(service: RadioService, band: Band): Frequencies = when (service) {
        RadioService.AMATEUR -> if (band == Band.VHF) AMATEUR_VHF else AMATEUR_UHF
        RadioService.BUSINESS -> if (band == Band.VHF) BUSINESS_VHF else BUSINESS_UHF
    }

    private val AMATEUR_VHF = Frequencies(
        channels = listOf(
            Channel(f(145.010, 145.030, 145.050, 145.070, 145.090), "Traditional packet channels, 20 kHz apart"),
        ),
        note = "In the ARRL band plan, 144.90-145.10 MHz is for weak-signal and FM simplex use, and these " +
            "channels are the traditional packet frequencies. Listen first, because some areas still run packet " +
            "nodes or Winlink gateways on them. Your local frequency coordinator's band plan comes first.",
        avoid = listOf(
            Channel(f(144.390), "APRS. TAKwave packets are not APRS and would clutter the APRS network"),
            Channel(f(146.520), "National simplex calling frequency (voice)"),
            Channel(listOf(r(144.000, 144.300)), "CW, SSB and weak-signal work"),
            Channel(listOf(r(145.800, 146.000)), "Satellites, including the ISS on 145.825"),
            Channel(
                listOf(r(144.600, 144.900), r(145.200, 145.500), r(146.010, 146.370), r(146.610, 147.390), r(147.600, 147.990)),
                "Repeater inputs and outputs",
            ),
        ),
    )

    private val AMATEUR_UHF = Frequencies(
        channels = listOf(
            Channel(listOf(r(445.000, 447.000)), "Simplex, links and repeaters, as your area's band plan assigns them"),
        ),
        note = "There are no nationwide packet channels on 70 cm. In the ARRL band plan, 445-447 MHz is shared by " +
            "simplex, links and repeaters, as each area chooses. Check your local coordinator's band plan for a " +
            "simplex or digital channel, and listen before you use it.",
        avoid = listOf(
            Channel(f(446.000), "National simplex calling frequency (voice)"),
            Channel(listOf(r(435.000, 438.000)), "Satellites only"),
            Channel(listOf(r(432.000, 433.000)), "EME, beacons and weak-signal work"),
            Channel(listOf(r(442.000, 445.000), r(447.000, 450.000)), "Repeater inputs and outputs in most areas"),
        ),
    )

    private const val PART90_NOTE =
        "Transmit only on frequencies and emissions listed on your FCC license. Check that the license lists " +
            "a data emission (for example 11K2F2D) as well as voice. The channels below are the nationwide " +
            "itinerant channels (47 CFR 90.35), for stations licensed for itinerant (mobile, no fixed site) " +
            "operation. Operating from a fixed site needs a coordinated frequency. Other users share itinerant " +
            "channels, so listen first."

    private val BUSINESS_VHF = Frequencies(
        channels = listOf(
            Channel(f(151.505, 158.400)),
            Channel(f(151.5125, 158.4075), "Offset channels: 11.25 kHz bandwidth at most"),
            Channel(f(151.625), "\"Red Dot\". Busy in many areas, with rental and retail radios"),
            Channel(f(151.700, 151.760, 154.5275), "35 W at most; 11.25 kHz bandwidth at most"),
        ),
        note = PART90_NOTE,
        avoid = listOf(
            Channel(
                f(151.820, 151.880, 151.940, 154.570, 154.600),
                "MURS: a license-free Part 95 service with its own rules (2 W, certified MURS radios). A business " +
                    "license does not cover these channels",
            ),
            Channel(f(151.955), "\"Purple Dot\": not an itinerant channel. It needs a coordinated license"),
            Channel(f(151.640), "Itinerant, but limited to 6 kHz bandwidth, which is too narrow for FM data"),
        ),
    )

    private val BUSINESS_UHF = Frequencies(
        channels = listOf(
            Channel(f(464.500), "\"Brown Dot\". 35 W at most"),
            Channel(f(464.550), "\"Yellow Dot\". 35 W at most"),
            Channel(f(469.500, 469.550), "35 W at most"),
        ),
        note = PART90_NOTE,
        avoid = listOf(
            Channel(
                listOf(r(462.550, 462.725), r(467.550, 467.725)),
                "GMRS and FRS: Part 95 services, not usable under a business license",
            ),
        ),
    )
}
