// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fearmikey.takwave.core.io.SerialEncoding
import com.fearmikey.takwave.data.SerialLinkKind
import com.fearmikey.takwave.data.SerialRadioType
import com.fearmikey.takwave.data.Settings
import com.fearmikey.takwave.ptt.SerialDevices
import com.fearmikey.takwave.ptt.SerialLinks

/**
 * Radio type and link for a digital radio whose data port passes bytes over the air (Icom D-STAR
 * DV data, Kenwood NX / Icom IDAS NXDN transparent data). Shared by Settings and the setup guide.
 */
@Composable
internal fun SerialDataSettings(settings: Settings, deviceTick: Int, update: ((Settings) -> Settings) -> Unit) {
    val context = LocalContext.current
    Text("Radio", style = MaterialTheme.typography.labelLarge)
    ChoiceSetting(SerialRadioType.entries.map { it to it.label }, settings.serialRadioType) { v -> update { it.copy(serialRadioType = v) } }
    Text(
        if (settings.serialRadioType.isDstar) {
            "In the radio's menu: DV mode on the team's frequency, the data port (USB or Bluetooth) set to DV Data, " +
                "and DV Data TX set to Auto so the radio transmits when data arrives." +
                if (settings.serialRadioType == SerialRadioType.DSTAR_FAST) " Turn DV Fast Data on in every radio." else ""
        } else {
            "In the radio's programming: transparent data on the data port, at the baud rate set here, on an NXDN " +
                "channel shared by the team."
        },
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )

    Spacer(Modifier.height(8.dp))
    Text("Connection", style = MaterialTheme.typography.labelLarge)
    ChoiceSetting(SerialLinkKind.entries.map { it to it.label }, settings.serialRadioLink) { v -> update { it.copy(serialRadioLink = v) } }
    when (settings.serialRadioLink) {
        SerialLinkKind.USB -> {
            val ports = remember(deviceTick) { SerialDevices.list(context) }
            val choices = buildList {
                add("" to "Automatic (the first USB serial port)")
                ports.forEach { add(it.key to it.label + if (it.hasPermission) "" else " (permission needed)") }
                if (settings.serialRadioUsbDevice.isNotEmpty() && ports.none { it.key == settings.serialRadioUsbDevice }) {
                    add(settings.serialRadioUsbDevice to "${settings.serialRadioUsbDevice} (not connected)")
                }
            }
            ChoiceSetting(choices, settings.serialRadioUsbDevice) { v -> update { it.copy(serialRadioUsbDevice = v) } }
            if (ports.isEmpty()) {
                Text(
                    "Nothing detected. Plug in the radio's USB cable, or the USB-to-RS-232 adapter on its data cable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarnAmber,
                )
            }
            ports.filter { !it.hasPermission }.forEach { d ->
                OutlinedButton(onClick = { SerialDevices.requestPermission(context, d.device) }) { Text("Grant access to ${d.label}") }
            }
            Spacer(Modifier.height(8.dp))
            Text("Port on the device", style = MaterialTheme.typography.labelLarge)
            ChoiceSetting(
                listOf(0 to "First port", 1 to "Second port (for example the IC-705's port B)"),
                settings.serialRadioUsbPort,
            ) { v -> update { it.copy(serialRadioUsbPort = v) } }
            Spacer(Modifier.height(8.dp))
            Text("Baud rate (must match the radio's data port)", style = MaterialTheme.typography.labelLarge)
            ChoiceSetting((listOf(4800) + SerialLinks.BAUD_RATES).map { it to "$it" }, settings.serialRadioBaud) { v ->
                update { it.copy(serialRadioBaud = v) }
            }
        }
        SerialLinkKind.BLUETOOTH -> {
            BluetoothDevicePicker(settings.serialRadioBtDevice, "Choose the radio or Bluetooth serial adapter.", settings, update) { v ->
                update { it.copy(serialRadioBtDevice = v) }
            }
            Text(
                "Pair it in Android's Bluetooth settings first. On an ID-52 or IC-705, set the Bluetooth data function " +
                    "to DV Data, and close Icom's own apps while TAKwave runs: the radio accepts one serial connection.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("Encoding", style = MaterialTheme.typography.labelLarge)
    ChoiceSetting(
        listOf(
            SerialEncoding.BINARY to "Binary (compact; default)",
            SerialEncoding.TEXT to "Text (a third longer; for radios that alter bytes or send data line by line)",
        ),
        settings.serialEncoding,
    ) { v -> update { it.copy(serialEncoding = v) } }
    Text(
        "Every station reads both, so this only changes what this station sends. Switch to Text if packets from " +
            "this station arrive damaged or not at all while the link itself works.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Every TAKwave station on the channel must use the same kind of radio and data mode. Each packet carries " +
            "its own check, and damaged ones are dropped. The radio keys itself, so the audio, PTT, transmit and " +
            "channel access settings are not shown. Not yet tested with a real radio.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
    )
}
