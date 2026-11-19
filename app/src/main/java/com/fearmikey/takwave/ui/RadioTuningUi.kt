// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fearmikey.takwave.data.Settings

/**
 * Pacing by hand and the raw-data log, for the digital radio backends (P25, DMR, D-STAR, NXDN).
 * Their speeds and behaviour were estimated without the radios at hand, so these are the knobs to
 * correct them on the air.
 */
@Composable
internal fun RadioTuningSettings(settings: Settings, update: ((Settings) -> Settings) -> Unit) {
    val (defaultBps, defaultKeyUp) = settings.radioPacingDefaults ?: return
    val custom = settings.radioDataBps > 0 || settings.radioKeyUpMs >= 0
    SwitchSetting(
        "Set the pacing by hand",
        "TAKwave waits after each packet for as long as the radio should need to send it, so its own priorities " +
            "decide what goes next. Default for this radio: $defaultBps bit/s plus $defaultKeyUp ms per transmission. " +
            "Lower the rate if packets go missing when several are sent in a row; raise it if the radio sits idle " +
            "between them.",
        custom,
    ) { on ->
        update {
            if (on) it.copy(radioDataBps = defaultBps, radioKeyUpMs = defaultKeyUp) else it.copy(radioDataBps = 0, radioKeyUpMs = -1)
        }
    }
    if (custom) {
        val (bps, keyUp) = settings.radioPacing
        IntSetting("Data rate (bit/s)", bps, 100..20_000) { v -> update { it.copy(radioDataBps = v) } }
        IntSetting("Key-up time per transmission (ms)", keyUp, 0..5_000) { v -> update { it.copy(radioKeyUpMs = v) } }
    }
    SwitchSetting(
        "Log raw radio data",
        "Write to the log what the radio sends that TAKwave does not use (other ports, damaged packets, raw bytes " +
            "on a serial link). Use it while setting up, or to send to the developers when something does not work; " +
            "leave it off otherwise.",
        settings.radioDataLog,
    ) { v -> update { it.copy(radioDataLog = v) } }
    Text(
        "The log can contain your own and other stations' traffic as received; share it only with people you trust.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}
