// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fearmikey.takwave.AppContainer
import com.fearmikey.takwave.core.io.RfState
import com.fearmikey.takwave.engine.CalibrationTone
import com.fearmikey.takwave.engine.RadioStatus
import kotlin.math.roundToInt

@Composable
fun CalibrateScreen(container: AppContainer, status: RadioStatus) {
    val maxSeconds = (status.txTimeoutS - 1).coerceAtLeast(1)
    var seconds by rememberSaveable { mutableIntStateOf(5) }
    val duration = seconds.coerceIn(1, maxSeconds)
    val canTransmit = status.running && status.softModem && status.rf != RfState.TX

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (!status.running) {
            WarningCard("Start the gateway on the Status tab to use calibration.")
        } else if (!status.softModem) {
            WarningCard("Calibration tones and the level meter need the soft modem. With a KISS TNC, set levels on the TNC.")
        }

        Section("Receive level") {
            LevelMeter(status.level, status.peak)
            Spacer(Modifier.height(8.dp))
            Text(guidance(status), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Open the squelch or wait for a packet, then set the radio volume so the bar is green: peaks between " +
                    "about 20% and 80%. Red means the input is clipping; turn the volume down. Keep the radio's squelch " +
                    "open or very loose for data, and leave the volume alone once it is set.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("Transmit tones") {
            Text(
                "Keys the radio and sends a steady tone so you can set the audio level (FM deviation of roughly 3 kHz " +
                    "on a 5 kHz channel). Adjust \"TX audio level\" in Settings, or the interface's level control, until " +
                    "the signal sounds as loud as a normal voice transmission on a second receiver, then back off slightly. " +
                    "Both tones should sound equally loud.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Text("Duration: $duration s (TX timeout ${status.txTimeoutS} s)", style = MaterialTheme.typography.labelLarge)
            if (maxSeconds > 1) {
                Slider(
                    value = duration.toFloat(),
                    onValueChange = { seconds = it.roundToInt() },
                    valueRange = 1f..maxSeconds.toFloat(),
                    steps = (maxSeconds - 2).coerceAtLeast(0),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = canTransmit, onClick = { container.engine?.sendTone(CalibrationTone.MARK, duration) }) {
                    Text("1200 Hz")
                }
                Button(enabled = canTransmit, onClick = { container.engine?.sendTone(CalibrationTone.SPACE, duration) }) {
                    Text("2200 Hz")
                }
                Button(enabled = canTransmit, onClick = { container.engine?.sendTone(CalibrationTone.ALTERNATING, duration) }) {
                    Text("Alternating")
                }
            }
            Text(
                "Identify with your callsign by voice when testing on the air, and use a dummy load where you can.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Section("Test packet") {
            Text(
                "Queues one position report from this station, using the last position ATAK reported " +
                    "(0,0 if no TAK client has connected yet). The other station should list you under Heard.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(enabled = status.running, onClick = { container.engine?.sendTestPli() }) {
                Text("Send test PLI")
            }
            Text("Results appear on the Log tab.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private fun guidance(status: RadioStatus): String = when {
    !status.running || !status.softModem -> "No audio input is being measured."
    status.rf == RfState.TX -> "Transmitting; the receiver is muted."
    status.peak >= 0.9f -> "Too loud: the input is clipping. Turn the radio volume down."
    status.peak >= 0.2f -> "Good level."
    status.peak >= 0.05f -> "Usable, but on the quiet side. Turn the radio volume up a little."
    else -> "Very quiet or silent. Check the cable, open the squelch, or turn the volume up."
}
