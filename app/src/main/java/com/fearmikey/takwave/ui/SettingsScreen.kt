// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fearmikey.takwave.AppContainer
import com.fearmikey.takwave.audio.AudioDeviceOption
import com.fearmikey.takwave.audio.AudioDevices
import com.fearmikey.takwave.core.ax25.Ax25Address
import com.fearmikey.takwave.data.EncryptionMode
import com.fearmikey.takwave.data.ModemBackend
import com.fearmikey.takwave.data.PttLine
import com.fearmikey.takwave.data.PttMethod
import com.fearmikey.takwave.data.Settings
import com.fearmikey.takwave.engine.RadioStatus
import com.fearmikey.takwave.ptt.SerialDevices
import com.fearmikey.takwave.service.TakwaveService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, settings: Settings, status: RadioStatus) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun update(transform: (Settings) -> Settings) {
        scope.launch { container.settingsStore.update(transform) }
    }

    // Bumped whenever USB devices come, go, or get permission, to re-read the device lists.
    var deviceTick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                deviceTick++
            }
        }
        val filter = IntentFilter().apply {
            addAction(SerialDevices.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (status.running) {
            Section("Service is running") {
                Text("Changes take effect the next time the gateway starts.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    scope.launch {
                        TakwaveService.stop(context)
                        // Give the old instance time to release the port, audio and USB.
                        delay(800)
                        TakwaveService.start(context)
                    }
                }) { Text("Restart gateway now") }
            }
        }

        Section("Station") {
            TextSetting(
                label = "Callsign (required)",
                value = settings.callsign,
                capitalize = true,
                error = when {
                    settings.callsign.isEmpty() -> "Required. TAKwave will not transmit without it."
                    !Ax25Address.isValidCall(settings.callsign) -> "1 to 6 letters and digits"
                    else -> null
                },
                onChange = { v -> update { it.copy(callsign = v) } },
                filter = { it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(6) },
            )
            IntSetting("SSID", settings.ssid, 0..15) { v -> update { it.copy(ssid = v) } }
            Text(
                "Your callsign is sent in the clear in every packet as the station identification.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("TAK server") {
            IntSetting("TCP port", settings.takPort, 1024..65535) { v -> update { it.copy(takPort = v) } }
            SwitchSetting(
                "Allow LAN clients",
                "Listen on all interfaces so another device (for example iTAK on this phone's hotspot) can connect. " +
                    "Anyone on the network can then connect; there is no authentication.",
                settings.allowLan,
            ) { v -> update { it.copy(allowLan = v) } }
        }

        Section("Position reports (PLI)") {
            IntSetting("Base interval (s)", settings.pliBaseS, 10..3600) { v -> update { it.copy(pliBaseS = v) } }
            IntSetting("Minimum interval (s)", settings.pliMinS, 5..600) { v -> update { it.copy(pliMinS = v) } }
            IntSetting("Send early after moving (m)", settings.pliDistanceM, 5..10000) { v -> update { it.copy(pliDistanceM = v) } }
            IntSetting("Send early after turning (degrees)", settings.pliHeadingDeg, 5..180) { v -> update { it.copy(pliHeadingDeg = v) } }
        }

        Section("Modem backend") {
            ChoiceSetting(ModemBackend.entries.map { it to it.label }, settings.backend) { v -> update { it.copy(backend = v) } }
            if (settings.backend == ModemBackend.KISS_TCP) {
                TextSetting(
                    label = "KISS TNC host",
                    value = settings.kissHost,
                    error = if (settings.kissHost.isBlank()) "Required" else null,
                    onChange = { v -> update { it.copy(kissHost = v) } },
                    filter = { it.trim().take(253) },
                )
                IntSetting("KISS TNC port", settings.kissPort, 1..65535) { v -> update { it.copy(kissPort = v) } }
                Text(
                    "The TNC (for example Dire Wolf) does the modulation, channel access and PTT. " +
                        "The audio, PTT, TX and CSMA settings below are not used.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (settings.backend == ModemBackend.SOFT) {
            Section("Audio devices") {
                val inputs = remember(deviceTick) { AudioDevices.inputs(context) }
                val outputs = remember(deviceTick) { AudioDevices.outputs(context) }
                Text("Input (from radio)", style = MaterialTheme.typography.labelLarge)
                ChoiceSetting(audioChoices(inputs, settings.inputDevice), settings.inputDevice) { v -> update { it.copy(inputDevice = v) } }
                Spacer(Modifier.height(8.dp))
                Text("Output (to radio)", style = MaterialTheme.typography.labelLarge)
                ChoiceSetting(audioChoices(outputs, settings.outputDevice), settings.outputDevice) { v -> update { it.copy(outputDevice = v) } }
                OutlinedButton(onClick = { deviceTick++ }) { Text("Refresh device list") }
                Text(
                    "A Digirig shows up as USB audio. With the system default and no interface attached, TAKwave uses the " +
                        "built-in microphone and speaker: acoustic test mode.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Section("PTT") {
                ChoiceSetting(PttMethod.entries.map { it to it.label }, settings.pttMethod) { v -> update { it.copy(pttMethod = v) } }
                when (settings.pttMethod) {
                    PttMethod.SERIAL -> {
                        val devices = remember(deviceTick) { SerialDevices.list(context) }
                        Spacer(Modifier.height(8.dp))
                        Text("USB serial adapter", style = MaterialTheme.typography.labelLarge)
                        val choices = buildList {
                            add("" to "First adapter found")
                            devices.forEach { add(it.key to it.label + if (it.hasPermission) "" else " (permission needed)") }
                            if (settings.pttDevice.isNotEmpty() && devices.none { it.key == settings.pttDevice }) {
                                add(settings.pttDevice to "${settings.pttDevice} (not connected)")
                            }
                        }
                        ChoiceSetting(choices, settings.pttDevice) { v -> update { it.copy(pttDevice = v) } }
                        if (devices.isEmpty()) {
                            Text("No USB serial adapter detected.", style = MaterialTheme.typography.bodySmall, color = WarnAmber)
                        }
                        devices.filter { !it.hasPermission }.forEach { d ->
                            OutlinedButton(onClick = { SerialDevices.requestPermission(context, d.device) }) {
                                Text("Grant access to ${d.label}")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Control line", style = MaterialTheme.typography.labelLarge)
                        ChoiceSetting(PttLine.entries.map { it to it.name }, settings.pttLine) { v -> update { it.copy(pttLine = v) } }
                        SwitchSetting(
                            "Invert polarity",
                            "Off: the line goes high to transmit (Digirig). On: the line goes low to transmit.",
                            settings.pttInverted,
                        ) { v -> update { it.copy(pttInverted = v) } }
                    }
                    PttMethod.VOX -> Text(
                        "The radio keys itself from the audio (or you are testing acoustically). Raise TXDELAY if the " +
                            "start of packets is cut off.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    PttMethod.NONE -> Text(
                        "Audio is played without keying anything.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Section("Transmit") {
                IntSetting("TXDELAY (ms)", settings.txDelayMs, 50..2000) { v -> update { it.copy(txDelayMs = v) } }
                IntSetting("TXTAIL (ms)", settings.txTailMs, 10..500) { v -> update { it.copy(txTailMs = v) } }
                IntSetting("TX audio level (%)", settings.txLevel, 1..100) { v -> update { it.copy(txLevel = v) } }
                IntSetting("TX timeout (s)", settings.txTimeoutS, 3..60) { v -> update { it.copy(txTimeoutS = v) } }
                Text(
                    "The transmitter is force-unkeyed if a single transmission lasts longer than the timeout.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("Channel access (CSMA)") {
                IntSetting("Slot time (ms)", settings.slotTimeMs, 10..1000) { v -> update { it.copy(slotTimeMs = v) } }
                IntSetting("Persistence (0-255)", settings.persistence, 0..255) { v -> update { it.copy(persistence = v) } }
                IntSetting("Busy above input level (%, 0 = off)", settings.energyThreshold, 0..100) { v -> update { it.copy(energyThreshold = v) } }
                Text(
                    "TAKwave always waits while it detects a packet on the channel. The level threshold additionally " +
                        "defers to voice or noise; leave it off for acoustic tests.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Section("Encryption") {
            WarningCard(
                "Encryption is NOT permitted on amateur radio frequencies. Leave this Off unless you are operating " +
                    "under a Part 90 (business band) licence that allows it.",
            )
            ChoiceSetting(EncryptionMode.entries.map { it to it.label }, settings.encryption) { v -> update { it.copy(encryption = v) } }
            if (settings.encryption == EncryptionMode.AES256_GCM) {
                TextSetting(
                    label = "Passphrase (shared by all stations)",
                    value = settings.passphrase,
                    password = true,
                    error = when {
                        settings.passphrase.isEmpty() -> "Required. Transmitting is disabled until a passphrase is set."
                        settings.passphrase.length < 12 -> "Short passphrases are easy to guess; use 12 or more characters."
                        else -> null
                    },
                    onChange = { v -> update { it.copy(passphrase = v) } },
                )
                Text(
                    "Your callsign is still sent in the clear. Only the message content is encrypted.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun audioChoices(options: List<AudioDeviceOption>, selected: String): List<Pair<String, String>> = buildList {
    add("" to "System default")
    options.forEach { add(it.key to it.label) }
    if (selected.isNotEmpty() && options.none { it.key == selected }) add(selected to "$selected (not connected)")
}

@Composable
private fun TextSetting(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    error: String? = null,
    password: Boolean = false,
    capitalize: Boolean = false,
    filter: (String) -> String = { it },
) {
    // Local text state keeps typing smooth; the stored value catches up asynchronously.
    var text by remember { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = filter(it)
            onChange(text)
        },
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = if (capitalize) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Ascii,
            autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun IntSetting(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in range
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new.filter { it.isDigit() }.take(6)
            val v = text.toIntOrNull()
            // Only valid values are stored; an out-of-range entry leaves the old setting in place.
            if (v != null && v in range) onChange(v)
        },
        label = { Text(label) },
        singleLine = true,
        isError = !valid,
        supportingText = if (valid) null else ({ Text("Enter ${range.first} to ${range.last}") }),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun SwitchSetting(label: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceSetting(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column {
        for ((value, label) in options) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == selected, onClick = null, modifier = Modifier.padding(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
