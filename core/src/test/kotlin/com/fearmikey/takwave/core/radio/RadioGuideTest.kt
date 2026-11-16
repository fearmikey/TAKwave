// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioGuideTest {

    private fun all(service: RadioService, band: Band): RadioAdvice =
        RadioGuide.advise(service, band, ChannelWidth.WIDE, Terrain.OPEN)

    private fun RadioAdvice.suggested(): List<FrequencyRange> = channels.flatMap { it.frequencies }

    private fun List<FrequencyRange>.containsFrequency(mhz: Double) =
        any { mhz >= it.lowMhz - 1e-6 && mhz <= it.highMhz + 1e-6 }

    @Test
    fun businessBandIsAlwaysNarrow() {
        for (band in Band.entries) {
            val advice = RadioGuide.advise(RadioService.BUSINESS, band, ChannelWidth.WIDE, Terrain.FOREST)
            assertEquals(ChannelWidth.NARROW, advice.width)
            assertTrue(advice.widthFixed)
        }
    }

    @Test
    fun amateurKeepsTheChosenWidth() {
        for (width in ChannelWidth.entries) {
            val advice = RadioGuide.advise(RadioService.AMATEUR, Band.VHF, width, Terrain.OPEN)
            assertEquals(width, advice.width)
            assertFalse(advice.widthFixed)
        }
        assertEquals(ChannelWidth.WIDE, RadioGuide.defaultWidth(RadioService.AMATEUR))
    }

    @Test
    fun deviationTargetsStayInsideTheChannel() {
        for (w in ChannelWidth.entries) assertTrue(w.dataDeviationHz < w.maxDeviationHz)
    }

    @Test
    fun suggestedChannelsAreInsideTheBand() {
        val bands = mapOf(
            (RadioService.AMATEUR to Band.VHF) to 144.0..148.0,
            (RadioService.AMATEUR to Band.UHF) to 420.0..450.0,
            (RadioService.BUSINESS to Band.VHF) to 150.0..174.0,
            (RadioService.BUSINESS to Band.UHF) to 450.0..470.0,
        )
        for ((key, limits) in bands) {
            val suggested = all(key.first, key.second).suggested()
            assertTrue("$key has suggestions", suggested.isNotEmpty())
            for (f in suggested) {
                assertTrue("$key ${f.label}", f.lowMhz in limits && f.highMhz in limits)
            }
        }
    }

    @Test
    fun amateurSuggestionsStayOffAprsAndCallingFrequencies() {
        val vhf = all(RadioService.AMATEUR, Band.VHF)
        assertFalse(vhf.suggested().containsFrequency(144.390))
        assertFalse(vhf.suggested().containsFrequency(146.520))
        assertTrue(vhf.avoid.flatMap { it.frequencies }.containsFrequency(144.390))
        val uhf = all(RadioService.AMATEUR, Band.UHF)
        assertTrue(uhf.avoid.flatMap { it.frequencies }.containsFrequency(446.000))
    }

    @Test
    fun businessSuggestionsExcludeMursGmrsAndNonItinerantChannels() {
        val vhf = all(RadioService.BUSINESS, Band.VHF).suggested()
        for (mhz in listOf(151.820, 151.880, 151.940, 154.570, 154.600, 151.955, 151.640)) {
            assertFalse("$mhz", vhf.containsFrequency(mhz))
        }
        val uhf = all(RadioService.BUSINESS, Band.UHF).suggested()
        for (mhz in listOf(462.5625, 462.575, 462.600, 462.675, 467.5625, 467.7125)) {
            assertFalse("$mhz", uhf.containsFrequency(mhz))
        }
        assertTrue(vhf.containsFrequency(151.625))
        assertTrue(uhf.containsFrequency(464.500))
    }

    @Test
    fun terrainPicksTheBand() {
        assertEquals(Band.VHF, RadioGuide.recommendedBand(Terrain.FOREST))
        assertEquals(Band.VHF, RadioGuide.recommendedBand(Terrain.MOUNTAINS))
        assertEquals(Band.UHF, RadioGuide.recommendedBand(Terrain.URBAN))
        assertEquals(Band.UHF, RadioGuide.recommendedBand(Terrain.INDOOR))
        for (t in Terrain.entries) {
            assertTrue(RadioGuide.bandReason(t).isNotBlank())
            assertTrue(RadioGuide.terrainTips(t).isNotEmpty())
            assertTrue(RadioGuide.fx25For(t) in setOf(0, 16, 32, 64))
        }
    }

    @Test
    fun labelsUseThreeOrFourDecimals() {
        assertEquals("145.010", FrequencyRange(145.01).label)
        assertEquals("151.5125", FrequencyRange(151.5125).label)
        assertEquals("445.000-447.000", FrequencyRange(445.0, 447.0).label)
        assertEquals("464.500, 464.550 MHz", Channel(listOf(FrequencyRange(464.5), FrequencyRange(464.55))).label)
    }
}
