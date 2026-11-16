// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fearmikey.takwave.AppContainer
import com.fearmikey.takwave.core.radio.Band
import com.fearmikey.takwave.core.radio.Channel
import com.fearmikey.takwave.core.radio.ChannelWidth
import com.fearmikey.takwave.core.radio.RadioGuide
import com.fearmikey.takwave.core.radio.RadioService
import com.fearmikey.takwave.core.radio.Terrain
import com.fearmikey.takwave.data.EncryptionMode
import com.fearmikey.takwave.data.Fx25Mode
import com.fearmikey.takwave.data.ModemBackend
import com.fearmikey.takwave.data.Settings
import kotlinx.coroutines.launch

internal val RadioService.label: String
    get() = when (this) {
        RadioService.AMATEUR -> "Amateur (ham): 2 m and 70 cm"
        RadioService.BUSINESS -> "Business band (Part 90 license)"
    }

internal val Terrain.label: String
    get() = when (this) {
        Terrain.OPEN -> "Open, flat country"
        Terrain.HILLS -> "Rolling hills"
        Terrain.MOUNTAINS -> "Mountains, deep valleys"
        Terrain.FOREST -> "Forest, dense foliage"
        Terrain.URBAN -> "Town or city, between buildings"
        Terrain.INDOOR -> "Inside buildings or vehicles"
    }

internal val ChannelWidth.label: String
    get() = when (this) {
        ChannelWidth.WIDE -> "Wide: 25 kHz channel, 5 kHz deviation"
        ChannelWidth.NARROW -> "Narrow: 12.5 kHz channel, 2.5 kHz deviation"
    }

/** "1.5 kHz" for the deviation target of a channel width. */
internal fun ChannelWidth.deviationText(): String = "%.1f kHz".format(dataDeviationHz / 1000.0)

/** One line for the Radio & audio page, e.g. "Amateur 2 m, wide, open, flat country". */
internal fun Settings.radioGuideSummary(): String {
    val service = if (radioService == RadioService.AMATEUR) "Amateur" else "Business"
    val band = RadioGuide.bandLabel(radioService, radioBand).substringBefore(" (")
    val width = if (effectiveChannelWidth == ChannelWidth.WIDE) "wide" else "narrow"
    return "$service $band, $width, ${terrain.label.replaceFirstChar { it.lowercase() }}"
}

/**
 * Recommended radio settings for the chosen service, band, channel width and terrain. Advice for
 * setting up the radio itself; the only TAKwave setting it can change is FX.25, on request.
 */
@Composable
fun RadioGuideScreen(container: AppContainer, settings: Settings, onCalibrate: () -> Unit) {
    val scope = rememberCoroutineScope()
    val update: ((Settings) -> Settings) -> Unit = { transform ->
        scope.launch { container.settingsStore.update(transform) }
    }
    val advice = RadioGuide.advise(settings.radioService, settings.radioBand, settings.channelWidth, settings.terrain)
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScrollbar(scroll)
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            "TAKwave cannot tune your radio. Set these on the radio or in its programming software. The " +
                "frequencies follow US rules and the ARRL band plan. In other countries, follow your national band plan.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        if (settings.radioService == RadioService.AMATEUR && settings.encryption != EncryptionMode.OFF) {
            WarningCard(
                "Encryption is on (Settings > Security), and it is not permitted on amateur frequencies. Turn it " +
                    "off before transmitting on 2 m or 70 cm.",
            )
        }

        Section("License", info = HelpText.RADIO_GUIDE) {
            ChoiceSetting(RadioService.entries.map { it to it.label }, settings.radioService) { v ->
                update { it.copy(radioService = v, channelWidth = RadioGuide.effectiveWidth(v, it.channelWidth)) }
            }
        }

        Section("Terrain") {
            ChoiceSetting(Terrain.entries.map { it to it.label }, settings.terrain) { v ->
                // A new terrain switches to the band that suits it; the operator can still override below.
                update { it.copy(terrain = v, radioBand = RadioGuide.recommendedBand(v)) }
            }
        }

        Section("Band") {
            Text(
                "Recommended: ${RadioGuide.bandLabel(settings.radioService, advice.recommendedBand)}",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(advice.bandReason, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
            ChoiceSetting(
                Band.entries.map { b ->
                    val tag = if (b == advice.recommendedBand) " (recommended)" else ""
                    b to RadioGuide.bandLabel(settings.radioService, b) + tag
                },
                settings.radioBand,
            ) { v -> update { it.copy(radioBand = v) } }
            Text(
                "The whole team must be on the same band.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("Channel width") {
            if (advice.widthFixed) {
                Text(advice.width.label, style = MaterialTheme.typography.labelLarge)
            } else {
                ChoiceSetting(RadioGuide.allowedWidths(settings.radioService).map { it to it.label }, advice.width) { v ->
                    update { it.copy(channelWidth = v) }
                }
            }
            Text(advice.widthReason, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                "Set TX audio level for about ${advice.width.deviationText()} deviation " +
                    "(the channel allows %.1f kHz).".format(advice.width.maxDeviationHz / 1000.0),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onCalibrate) { Text("Open calibration") }
        }

        Section("Frequencies: ${RadioGuide.bandLabel(settings.radioService, settings.radioBand)}") {
            for (c in advice.channels) ChannelRow(c)
            Text(advice.frequencyNote, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
            Spacer(Modifier.height(8.dp))
            Text("Stay off", style = MaterialTheme.typography.labelLarge, color = WarnAmber)
            for (c in advice.avoid) ChannelRow(c)
        }

        Section("Radio settings for data") {
            for (item in RadioGuide.radioChecklist) Bullet(item)
        }

        Section("For this terrain") {
            for (tip in advice.terrainTips) Bullet(tip)
            if (settings.backend == ModemBackend.SOFT) {
                val suggested = Fx25Mode.of(advice.fx25CheckBytes) ?: Fx25Mode.OFF
                Spacer(Modifier.height(8.dp))
                Text("Suggested FX.25: ${suggested.label}", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Error correction repairs packets damaged by weak signals and reflections. Stations without FX.25 " +
                        "still read your packets.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (settings.fx25 != suggested) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { update { it.copy(fx25 = suggested) } }) { Text("Use this FX.25 setting") }
                } else {
                    Text("FX.25 is already set to this.", style = MaterialTheme.typography.bodySmall, color = RxGreen)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ChannelRow(channel: Channel) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(channel.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        if (channel.note.isNotEmpty()) Text(channel.note, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
