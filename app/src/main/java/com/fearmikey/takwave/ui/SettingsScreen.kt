// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.compose.runtime.MutableIntState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.fearmikey.takwave.core.io.PttCommand
import com.fearmikey.takwave.data.BluetoothKissLink
import com.fearmikey.takwave.data.BtPttPreset
import com.fearmikey.takwave.data.SerialLinkKind
import com.fearmikey.takwave.ptt.BluetoothDevices
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fearmikey.takwave.AppContainer
import com.fearmikey.takwave.R
import com.fearmikey.takwave.audio.AudioDeviceOption
import com.fearmikey.takwave.audio.AudioDevices
import com.fearmikey.takwave.core.ax25.Ax25Address
import com.fearmikey.takwave.core.gateway.Gateway
import com.fearmikey.takwave.data.CipherChoice
import com.fearmikey.takwave.data.EncryptionMode
import com.fearmikey.takwave.data.Fx25Mode
import com.fearmikey.takwave.data.KeySlot
import com.fearmikey.takwave.data.TimeSource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.fearmikey.takwave.data.ModemBackend
import com.fearmikey.takwave.core.ip.IpFraming
import com.fearmikey.takwave.core.ip.Ipv4
import com.fearmikey.takwave.data.PttLine
import com.fearmikey.takwave.data.PttMethod
import com.fearmikey.takwave.data.Settings
import com.fearmikey.takwave.data.label
import com.fearmikey.takwave.engine.RadioStatus
import com.fearmikey.takwave.ptt.SerialDevices
import com.fearmikey.takwave.ptt.SerialLinks
import com.fearmikey.takwave.usb.UsbNetDevices
import com.fearmikey.takwave.service.TakwaveService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The groups of settings. Each opens on its own page from the list on the Settings screen. */
enum class SettingsPage(val title: String) {
    STATION("Station & TAK"),
    RADIO("Radio & audio"),
    CHANNEL("Channel"),
    SECURITY("Security"),
}

/** The Settings screen: one row per [SettingsPage] with a summary of its current values. */
@Composable
fun SettingsScreen(container: AppContainer, settings: Settings, status: RadioStatus, onOpen: (SettingsPage) -> Unit) {
    val scope = rememberCoroutineScope()
    SettingsColumn(status) {
        for (page in SettingsPage.entries) {
            val (summary, attention) = page.summary(settings)
            CategoryRow(page.title, summary, attention) { onOpen(page) }
        }

        Section("Setup guide") {
            Text(
                "Walks through permissions and the settings your radio needs, one step at a time.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                scope.launch { container.settingsStore.update { it.copy(onboardingDone = false) } }
            }) { Text("Run the setup guide again") }
        }
    }
}

/**
 * One group of settings. [onCalibrate] and [onRadioGuide] open the calibration screen and the radio
 * guide from the Radio & audio page.
 */
@Composable
fun SettingsPageScreen(
    page: SettingsPage,
    container: AppContainer,
    settings: Settings,
    status: RadioStatus,
    onCalibrate: () -> Unit,
    onRadioGuide: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val update: ((Settings) -> Settings) -> Unit = { transform ->
        scope.launch { container.settingsStore.update(transform) }
    }
    when (page) {
        SettingsPage.STATION -> StationPage(container, settings, status, update)
        SettingsPage.RADIO -> RadioPage(settings, status, onCalibrate, onRadioGuide, update)
        SettingsPage.CHANNEL -> ChannelPage(settings, status, update)
        SettingsPage.SECURITY -> SecurityPage(container, settings, status, update)
    }
}

/** A one-line summary of the page's current values, and whether it needs the operator's attention. */
private fun SettingsPage.summary(s: Settings): Pair<String, Boolean> = when (this) {
    SettingsPage.STATION ->
        (s.address?.let { "$it, TAK port ${s.takPort}" } ?: "No callsign set") to (s.address == null)
    SettingsPage.RADIO -> when (s.backend) {
        ModemBackend.SOFT -> "Soft modem, PTT: ${s.pttMethod.label}" to false
        ModemBackend.KISS_TCP ->
            "KISS TNC at ${s.kissHost.ifBlank { "(no host)" }}:${s.kissPort}" to s.kissHost.isBlank()
        ModemBackend.KISS_BLUETOOTH ->
            (if (s.kissBtDevice.isEmpty()) "KISS TNC over Bluetooth, no device chosen"
            else "KISS TNC over Bluetooth" + if (s.kissBtLink == BluetoothKissLink.BLE) " LE" else "") to s.kissBtDevice.isEmpty()
        ModemBackend.P25_DATA ->
            (s.p25Problem ?: ("P25 radio packet data, ${s.p25Framing.name}" +
                if (s.p25Link == SerialLinkKind.BLUETOOTH) " over Bluetooth" else " at ${s.p25Baud} baud")) to (s.p25Problem != null)
        ModemBackend.DMR_DATA ->
            (s.dmrProblem ?: "${s.dmrVendor.label} data to ${s.dmrTargetsText}") to (s.dmrProblem != null)
        ModemBackend.SERIAL_DATA ->
            (s.serialRadioProblem ?: s.serialRadioSummary) to (s.serialRadioProblem != null)
    }
    SettingsPage.CHANNEL -> {
        val parts = buildList {
            if (s.backend == ModemBackend.SOFT) {
                add(if (s.fx25 == Fx25Mode.OFF) "FX.25 off" else "FX.25 on")
                add(if (s.slotted) "time slots" else "CSMA")
            }
            val repeats = listOfNotNull("re-sending".takeIf { s.resendOwn }, "relaying".takeIf { s.relay })
            add(if (repeats.isEmpty()) "store and forward off" else repeats.joinToString(" and "))
        }
        parts.joinToString(", ").replaceFirstChar { it.uppercase() } to false
    }
    SettingsPage.SECURITY ->
        when {
            s.encryption == EncryptionMode.OFF -> "Encryption off"
            s.callsignHidden -> "Encryption: ${s.encryption.label}, callsign hidden"
            else -> "Encryption: ${s.encryption.label}"
        } to (s.encryption != EncryptionMode.OFF)
}

/** Scrolling column for the Settings screens, with the restart prompt on top while the gateway runs. */
@Composable
private fun SettingsColumn(status: RadioStatus, content: @Composable ColumnScope.() -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScrollbar(scroll)
            .verticalScroll(scroll)
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
        content()
        Spacer(Modifier.height(24.dp))
    }
}

/** A tappable card on the Settings list that opens a page. */
@Composable
private fun CategoryRow(title: String, summary: String, attention: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (attention) WarnAmber else Color.Unspecified,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(painterResource(R.drawable.ic_chevron), contentDescription = null)
        }
    }
}

@Composable
private fun StationPage(container: AppContainer, settings: Settings, status: RadioStatus, update: ((Settings) -> Settings) -> Unit) {
    SettingsColumn(status) {
        Section("Station", info = HelpText.CALLSIGN) {
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

        Section("TAK server", info = HelpText.TAK_SERVER) {
            IntSetting(
                "TCP port",
                settings.takPort,
                1024..65535,
                reject = { if (it == Settings.ATAK_INPUT_PORT) "ATAK itself listens on ${Settings.ATAK_INPUT_PORT}; choose another port" else null },
            ) { v -> update { it.copy(takPort = v) } }
            SwitchSetting(
                "Allow LAN clients",
                "Listen on all interfaces so another device (for example iTAK on this phone's hotspot) can connect. " +
                    "Anyone on the network can then connect; there is no authentication.",
                settings.allowLan,
            ) { v -> update { it.copy(allowLan = v) } }
        }

        Section("Position reports (PLI)", info = HelpText.PLI) {
            IntSetting("Base interval (s)", settings.pliBaseS, 10..3600) { v -> update { it.copy(pliBaseS = v) } }
            IntSetting("Minimum interval (s)", settings.pliMinS, 5..600) { v -> update { it.copy(pliMinS = v) } }
            IntSetting("Send early after moving (m)", settings.pliDistanceM, 5..10000) { v -> update { it.copy(pliDistanceM = v) } }
            IntSetting("Send early after turning (degrees)", settings.pliHeadingDeg, 5..180) { v -> update { it.copy(pliHeadingDeg = v) } }
        }

        TeamProfileSection(container, settings)
    }
}

@Composable
private fun RadioPage(
    settings: Settings,
    status: RadioStatus,
    onCalibrate: () -> Unit,
    onRadioGuide: () -> Unit,
    update: ((Settings) -> Settings) -> Unit,
) {
    val deviceTick = rememberDeviceTick()
    SettingsColumn(status) {
        Section("Radio guide", info = HelpText.RADIO_GUIDE) {
            Text(
                "Recommended band, channel width and frequencies for 2 m / 70 cm amateur or business band, based on " +
                    "your terrain, and the radio settings that matter for data.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                settings.radioGuideSummary(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRadioGuide) { Text("Open radio guide") }
        }

        Section("Modem backend", info = HelpText.BACKEND) {
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
                    "The TNC (for example Dire Wolf) does the modulation, channel access and PTT, so the audio, PTT, " +
                        "transmit and channel access settings are not shown.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (settings.backend == ModemBackend.KISS_BLUETOOTH) {
                Spacer(Modifier.height(8.dp))
                BluetoothKissSettings(settings) { update(it) }
            }
        }

        if (settings.backend == ModemBackend.P25_DATA) {
            Section("P25 radio packet data", info = HelpText.P25_DATA) {
                P25DataSettings(settings, deviceTick.intValue, status.running) { update(it) }
            }
        }

        if (settings.backend == ModemBackend.DMR_DATA) {
            Section("DMR radio data", info = HelpText.DMR_DATA) {
                DmrDataSettings(settings, deviceTick.intValue) { update(it) }
            }
        }

        if (settings.backend == ModemBackend.SERIAL_DATA) {
            Section("D-STAR or NXDN radio data", info = HelpText.SERIAL_DATA) {
                SerialDataSettings(settings, deviceTick.intValue) { update(it) }
            }
        }

        if (settings.radioPacingDefaults != null) {
            Section("Tuning and troubleshooting", info = HelpText.RADIO_TUNING) {
                RadioTuningSettings(settings) { update(it) }
            }
        }

        if (settings.backend == ModemBackend.SOFT) {
            Section("Audio devices", info = HelpText.AUDIO) {
                AudioDeviceSettings(settings, deviceTick) { update(it) }
            }

            Section("PTT", info = HelpText.PTT) {
                ChoiceSetting(PttMethod.entries.map { it to it.label }, settings.pttMethod) { v -> update { it.copy(pttMethod = v) } }
                when (settings.pttMethod) {
                    PttMethod.SERIAL -> {
                        Spacer(Modifier.height(8.dp))
                        SerialPttSettings(
                            settings,
                            deviceTick.intValue,
                            onUseVox = { update { it.copy(pttMethod = PttMethod.VOX, txDelayMs = maxOf(it.txDelayMs, VOX_MIN_TX_DELAY_MS)) } },
                        ) { update(it) }
                    }
                    PttMethod.BLUETOOTH -> {
                        Spacer(Modifier.height(8.dp))
                        BluetoothPttSettings(settings) { update(it) }
                    }
                    PttMethod.VOX -> Text(
                        "The radio, or an auto-PTT cable such as the BTECH APRS-K1 PRO, keys itself from the audio (or " +
                            "you are testing acoustically). With an auto-PTT cable, leave the radio's own VOX off. Raise " +
                            "TXDELAY if the start of packets is cut off.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    PttMethod.NONE -> Text(
                        "Audio is played without keying anything.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Section("Transmit", info = HelpText.TRANSMIT) {
                IntSetting("TXDELAY (ms)", settings.txDelayMs, 50..2000) { v -> update { it.copy(txDelayMs = v) } }
                IntSetting("TXTAIL (ms)", settings.txTailMs, 10..500) { v -> update { it.copy(txTailMs = v) } }
                IntSetting("TX audio level (%)", settings.txLevel, 1..100) { v -> update { it.copy(txLevel = v) } }
                IntSetting("TX timeout (s)", settings.txTimeoutS, 3..60) { v -> update { it.copy(txTimeoutS = v) } }
                Text(
                    "The transmitter is force-unkeyed if a single transmission lasts longer than the timeout.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("Calibrate levels") {
                Text(
                    "Set the receive volume with the level meter and the transmit level with test tones, before going " +
                        "on the air.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onCalibrate) { Text("Open calibration") }
            }
        }
    }
}

@Composable
private fun ChannelPage(settings: Settings, status: RadioStatus, update: ((Settings) -> Settings) -> Unit) {
    SettingsColumn(status) {
        if (settings.backend == ModemBackend.SOFT) {
            Section("Error correction (FX.25)", info = HelpText.FX25) {
                ChoiceSetting(Fx25Mode.entries.map { it to it.label }, settings.fx25) { v -> update { it.copy(fx25 = v) } }
                Text(
                    "FX.25 packets are always received and repaired. This only decides whether your own packets carry " +
                        "the extra check bytes, at the cost of longer transmissions. Stations without FX.25 still read them.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("Channel access (CSMA)", info = HelpText.CSMA) {
                IntSetting("Slot time (ms)", settings.slotTimeMs, 10..1000) { v -> update { it.copy(slotTimeMs = v) } }
                IntSetting("Persistence (0-255)", settings.persistence, 0..255) { v -> update { it.copy(persistence = v) } }
                IntSetting("Busy above input level (%, 0 = off)", settings.energyThreshold, 0..100) { v -> update { it.copy(energyThreshold = v) } }
                Text(
                    "TAKwave always waits while it detects a packet on the channel. The level threshold additionally " +
                        "defers to voice or noise; leave it off for acoustic tests.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("Time slots", info = HelpText.SLOTS) {
                SwitchSetting(
                    "Transmit only in my time slot",
                    "Stations take turns: each one transmits only during its own slot of a repeating cycle, so two " +
                        "stations that cannot hear each other do not transmit over one another. Every station in the " +
                        "group must use the same slot length and slot count, and a different slot number.",
                    settings.slotted,
                ) { v -> update { it.copy(slotted = v) } }
                if (settings.slotted) {
                    IntSetting("Slot length (ms)", settings.slotLengthMs, 1000..30000) { v -> update { it.copy(slotLengthMs = v) } }
                    IntSetting("Slots per cycle", settings.slotCount, 1..64) { v -> update { it.copy(slotCount = v) } }
                    IntSetting("My slot number (0 = choose from callsign)", settings.slotIndex + 1, 0..settings.slotCount) { v ->
                        update { it.copy(slotIndex = v - 1) }
                    }
                    Text(
                        "Cycle: %.0f s. Each station can send at most once per cycle, so keep the PLI interval longer than this."
                            .format(settings.slotLengthMs * settings.slotCount / 1000.0),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Time source", style = MaterialTheme.typography.labelLarge)
                    ChoiceSetting(TimeSource.entries.map { it to it.label }, settings.timeSource) { v -> update { it.copy(timeSource = v) } }
                    Text(
                        "Slots are aligned to UTC, so all stations need the right time to within about a quarter of a second. " +
                            "The phone clock is accurate when it has had a network connection recently. Off-grid, choose GNSS: " +
                            "TAKwave then uses satellite time whenever the GPS is running (ATAK keeps it running). " +
                            "Automatic slot numbers can collide; assign numbers by hand for a team.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            Text(
                "With a KISS TNC, error correction, channel access and time slots are set on the TNC.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        Section("Store and forward", info = HelpText.STORE_FORWARD) {
            SwitchSetting(
                "Send my messages again",
                "When a station comes into range that missed one of your chat messages or markers, send it once more.",
                settings.resendOwn,
            ) { v -> update { it.copy(resendOwn = v) } }
            SwitchSetting(
                "Relay for other stations",
                "Pass on chat, markers and emergency alerts heard from other stations, under your callsign. Use on " +
                    "one well-placed station only.",
                settings.relay,
            ) { v -> update { it.copy(relay = v) } }
            if (settings.resendOwn || settings.relay) {
                Spacer(Modifier.height(8.dp))
                Text("Limits", style = MaterialTheme.typography.labelLarge)
                IntSetting("Keep messages for (minutes)", settings.repeatHoldMin, 5..240) { v -> update { it.copy(repeatHoldMin = v) } }
                IntSetting("Repeats per message", settings.repeatMax, 1..10) { v -> update { it.copy(repeatMax = v) } }
                IntSetting("Minimum gap between repeats (s)", settings.repeatGapS, 30..1800) { v -> update { it.copy(repeatGapS = v) } }
                IntSetting("Most repeats per 10 minutes", settings.repeatBudget, 1..30) { v -> update { it.copy(repeatBudget = v) } }
                Text(
                    "New traffic always goes first; repeats only use air time that is otherwise idle. At most " +
                        "%d repeats in 10 minutes is at most about %d s of air time.".format(settings.repeatBudget, settings.repeatBudget * 2),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SecurityPage(container: AppContainer, settings: Settings, status: RadioStatus, update: ((Settings) -> Settings) -> Unit) {
    SettingsColumn(status) {
        Section("Encryption", info = HelpText.ENCRYPTION) {
            WarningCard(
                "Encryption is NOT permitted on amateur radio frequencies. Leave this Off unless you are operating " +
                    "under a Part 90 (business band) license that allows it.",
            )
            ChoiceSetting(EncryptionMode.entries.map { it to it.label }, settings.encryption) { v -> update { it.copy(encryption = v) } }
            EncryptionDetails(container, settings) { transform -> update(transform) }
        }
    }
}

/**
 * A counter that is bumped whenever USB devices come, go, or get permission, so device lists
 * are re-read. Callers may also bump it themselves ("Refresh").
 */
@Composable
internal fun rememberDeviceTick(): MutableIntState {
    val context = LocalContext.current
    val tick = remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                tick.intValue++
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
    return tick
}

/** Input and output device pickers. Shared by Settings and the setup guide. */
@Composable
internal fun AudioDeviceSettings(settings: Settings, deviceTick: MutableIntState, update: ((Settings) -> Settings) -> Unit) {
    val context = LocalContext.current
    val inputs = remember(deviceTick.intValue) { AudioDevices.inputs(context) }
    val outputs = remember(deviceTick.intValue) { AudioDevices.outputs(context) }
    Text("Input (from radio)", style = MaterialTheme.typography.labelLarge)
    ChoiceSetting(audioChoices(inputs, settings.inputDevice), settings.inputDevice) { v -> update { it.copy(inputDevice = v) } }
    Spacer(Modifier.height(8.dp))
    Text("Output (to radio)", style = MaterialTheme.typography.labelLarge)
    ChoiceSetting(audioChoices(outputs, settings.outputDevice), settings.outputDevice) { v -> update { it.copy(outputDevice = v) } }
    OutlinedButton(onClick = { deviceTick.intValue++ }) { Text("Refresh device list") }
    Text(
        "A Digirig shows up as USB audio. With the system default and no interface attached, TAKwave uses the " +
            "built-in microphone and speaker: acoustic test mode.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** Shortest TXDELAY set when switching to VOX: the radio or cable needs time to key before data starts. */
internal const val VOX_MIN_TX_DELAY_MS = 500

/**
 * USB serial adapter, control line and polarity. Shared by Settings and the setup guide.
 * [onUseVox] is offered when only a USB sound card is attached, which usually means an auto-PTT cable.
 */
@Composable
internal fun SerialPttSettings(
    settings: Settings,
    deviceTick: Int,
    onUseVox: () -> Unit,
    update: ((Settings) -> Settings) -> Unit,
) {
    val context = LocalContext.current
    val devices = remember(deviceTick) { SerialDevices.list(context) }
    val usbAudio = remember(deviceTick) { AudioDevices.hasUsbAudio(context) }
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
        if (usbAudio) {
            // A USB sound card with no serial port is most likely a cable that keys the radio from the audio.
            Text(
                "A USB sound card is connected, but it has no serial port to key the radio with. If it is a cable " +
                    "that keys the radio from the audio itself, such as the BTECH APRS-K1 PRO, use VOX instead " +
                    "(TXDELAY is raised to at least $VOX_MIN_TX_DELAY_MS ms) and leave the radio's own VOX off.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onUseVox) { Text("Use VOX instead") }
        }
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

/**
 * Picks one of the already-paired Bluetooth devices (or a Bluetooth LE TNC found with the BLE
 * chooser) by MAC address, asking for the Nearby devices permission when needed. [chooseHint] is
 * shown while nothing is selected. Used for Bluetooth PTT, a Bluetooth KISS TNC, and digital radio
 * data links. With [settings] and [update], a recognised radio gets a hint with a button that sets
 * TAKwave up for it ([BluetoothRadioHint]).
 */
@Composable
internal fun BluetoothDevicePicker(
    selected: String,
    chooseHint: String,
    settings: Settings? = null,
    update: (((Settings) -> Settings) -> Unit)? = null,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    // Re-read the paired devices after a permission prompt or a trip to the system settings.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val allowed = remember(tick) { BluetoothDevices.hasPermission(context) }
    val devices = remember(tick) { BluetoothDevices.known(context) }

    Text("Paired Bluetooth device", style = MaterialTheme.typography.labelLarge)
    if (!allowed) {
        Text(
            "TAKwave needs the Nearby devices permission to use a paired Bluetooth device.",
            style = MaterialTheme.typography.bodySmall,
            color = WarnAmber,
        )
        OutlinedButton(onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }) { Text("Allow Bluetooth access") }
    } else {
        val choices = buildList {
            devices.forEach { add(it.address to it.label) }
            if (selected.isNotEmpty() && devices.none { it.address.equals(selected, ignoreCase = true) }) {
                add(selected to "$selected (not paired)")
            }
        }
        ChoiceSetting(choices, devices.firstOrNull { it.address.equals(selected, ignoreCase = true) }?.address ?: selected) { v -> onSelect(v) }
        devices.firstOrNull { it.address.equals(selected, ignoreCase = true) }?.let { BluetoothRadioHint(it, settings, update) }
        if (devices.isEmpty()) {
            Text(
                "No paired devices, or Bluetooth is off. Pair the radio or adapter in Android's Bluetooth settings first.",
                style = MaterialTheme.typography.bodySmall,
                color = WarnAmber,
            )
        } else if (selected.isEmpty()) {
            Text(chooseHint, style = MaterialTheme.typography.bodySmall, color = WarnAmber)
        }
    }
    OutlinedButton(onClick = {
        try {
            context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (e: Exception) {
            // No Bluetooth settings screen on this device.
        }
    }) { Text("Open Bluetooth settings") }
}

/**
 * Radio or TNC that speaks KISS over Bluetooth: classic serial (SPP) or the Bluetooth LE KISS
 * service. Shared by Settings and the setup guide.
 */
@Composable
internal fun BluetoothKissSettings(settings: Settings, update: ((Settings) -> Settings) -> Unit) {
    BluetoothDevicePicker(settings.kissBtDevice, "Choose the radio or TNC.", settings, update) { v -> update { it.copy(kissBtDevice = v) } }
    BleTncFinder { address -> update { it.copy(kissBtDevice = address, kissBtLink = BluetoothKissLink.BLE) } }

    Spacer(Modifier.height(8.dp))
    Text("Bluetooth link", style = MaterialTheme.typography.labelLarge)
    ChoiceSetting(BluetoothKissLink.entries.map { it to it.label }, settings.kissBtLink) { v -> update { it.copy(kissBtLink = v) } }
    Text(
        "For a radio with a built-in KISS TNC, such as the BTECH UV-PRO or Kenwood TH-D75, or a Bluetooth TNC such " +
            "as a Mobilinkd. Pair it in Android's Bluetooth settings (a Bluetooth LE TNC can be found with the button " +
            "above instead), switch its KISS TNC on, and close any other app that is connected to it. The radio does " +
            "the modulation, channel access and PTT, so no audio cable is needed and the audio, PTT, transmit and " +
            "channel access settings are not shown.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * Radio connection, line settings and IP addresses for a P25 radio's packet data. The radio is
 * either a USB network device (APX portables: RNDIS), reached through a USB serial port (a data
 * cable with PPP or SLIP), or through a Bluetooth serial adapter on that data cable. Shared by
 * Settings and the setup guide.
 */
@Composable
internal fun P25DataSettings(settings: Settings, deviceTick: Int, running: Boolean, update: ((Settings) -> Settings) -> Unit) {
    val context = LocalContext.current
    val bluetooth = settings.p25Link == SerialLinkKind.BLUETOOTH
    Text("Connection", style = MaterialTheme.typography.labelLarge)
    ChoiceSetting(
        listOf(
            SerialLinkKind.USB to "USB (the radio's USB cable, or a USB-to-RS-232 adapter on its data cable)",
            SerialLinkKind.BLUETOOTH to "Bluetooth serial adapter on the radio's data cable",
        ),
        settings.p25Link,
    ) { v -> update { it.copy(p25Link = v) } }
    Spacer(Modifier.height(8.dp))

    // Which kind of link the current choice leads to: a USB network radio, or a serial line.
    var network: com.fearmikey.takwave.usb.UsbNetOption? = null
    if (bluetooth) {
        BluetoothDevicePicker(settings.p25BtDevice, "Choose the Bluetooth serial adapter on the radio's data cable.") { v ->
            update { it.copy(p25BtDevice = v) }
        }
        Text(
            "Set the adapter (with its own setup tool) to the radio's data port baud rate, 8N1, and pair it in " +
                "Android's Bluetooth settings. Over Bluetooth only the framing can be detected; the adapter fixes the rate.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    } else {
        val networkRadios = remember(deviceTick) { UsbNetDevices.list(context) }
        // An RNDIS radio also looks like a CDC serial port to the serial library; list it once, as a network radio.
        val serialPorts = remember(deviceTick) { SerialDevices.list(context).filter { s -> networkRadios.none { it.key == s.key } } }
        Text("Radio on USB", style = MaterialTheme.typography.labelLarge)
        val choices = buildList {
            add("" to "Automatic (a radio on USB first, else the first serial port)")
            networkRadios.forEach { add(it.key to it.label + if (it.hasPermission) "" else " (permission needed)") }
            serialPorts.forEach { add(it.key to it.label + " (serial)" + if (it.hasPermission) "" else " (permission needed)") }
            if (settings.p25Device.isNotEmpty() && networkRadios.none { it.key == settings.p25Device } && serialPorts.none { it.key == settings.p25Device }) {
                add(settings.p25Device to "${settings.p25Device} (not connected)")
            }
        }
        ChoiceSetting(choices, settings.p25Device) { v -> update { it.copy(p25Device = v) } }
        if (networkRadios.isEmpty() && serialPorts.isEmpty()) {
            Text(
                "Nothing detected. Plug in the radio's USB cable (APX portables), or the USB-to-RS-232 adapter on its data cable.",
                style = MaterialTheme.typography.bodySmall,
                color = WarnAmber,
            )
        }
        networkRadios.filter { !it.hasPermission }.forEach { d ->
            OutlinedButton(onClick = { SerialDevices.requestPermission(context, d.device) }) { Text("Grant access to ${d.label}") }
        }
        serialPorts.filter { !it.hasPermission }.forEach { d ->
            OutlinedButton(onClick = { SerialDevices.requestPermission(context, d.device) }) { Text("Grant access to ${d.label}") }
        }
        network = networkRadios.firstOrNull { settings.p25Device.isEmpty() || it.key == settings.p25Device }
    }

    Spacer(Modifier.height(8.dp))
    SwitchSetting(
        "Detect the radio's settings automatically",
        if (network != null) {
            "Each time the gateway connects, ask the radio for this phone's address and its own (DHCP), falling back " +
                "to the addresses below. Nothing is sent over the air while doing so."
        } else if (bluetooth) {
            "Each time the gateway connects, find the framing (PPP or SLIP) by asking the radio, and keep the " +
                "addresses it agrees to. Nothing is sent over the air while detecting."
        } else {
            "Each time the gateway connects, find the baud rate and framing (PPP or SLIP) by asking the radio, and keep " +
                "the addresses it agrees to. Nothing is sent over the air while detecting."
        },
        settings.p25AutoDetect,
    ) { v -> update { it.copy(p25AutoDetect = v) } }

    if (network != null) {
        Text(
            "${network.label}: a USB network link, so there is no baud rate or framing to set. The radio's " +
                "addresses are checked when the gateway starts.",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        P25DetectButton(settings, running, update)

        if (!bluetooth) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (settings.p25AutoDetect) "Baud rate (last detected; tried first)" else "Baud rate (must match the radio's data port)",
                style = MaterialTheme.typography.labelLarge,
            )
            ChoiceSetting(SerialLinks.BAUD_RATES.map { it to "$it" }, settings.p25Baud) { v -> update { it.copy(p25Baud = v) } }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            if (settings.p25AutoDetect) "Framing (last detected)" else "Framing (must match the radio's data port)",
            style = MaterialTheme.typography.labelLarge,
        )
        ChoiceSetting(
            listOf(IpFraming.PPP to "PPP (addresses are negotiated)", IpFraming.SLIP to "SLIP (fixed addresses)"),
            settings.p25Framing,
        ) { v -> update { it.copy(p25Framing = v) } }
    }

    Spacer(Modifier.height(8.dp))
    TextSetting(
        label = "This phone's IP address (radio's \"mobile computer\" IP)",
        value = settings.p25LocalIp,
        error = if (Ipv4.parse(settings.p25LocalIp).let { it == null || it == 0 }) "Enter an address such as 192.168.128.2" else null,
        onChange = { v -> update { it.copy(p25LocalIp = v) } },
        filter = { it.filter { ch -> ch.isDigit() || ch == '.' }.take(15) },
    )
    TextSetting(
        label = "Radio's IP address on the cable (\"subscriber\" IP)",
        value = settings.p25RadioIp,
        error = if (Ipv4.parse(settings.p25RadioIp) == null) "Enter an address such as 192.168.128.1" else null,
        onChange = { v -> update { it.copy(p25RadioIp = v) } },
        filter = { it.filter { ch -> ch.isDigit() || ch == '.' }.take(15) },
    )
    TextSetting(
        label = "Send to",
        value = settings.p25Destinations,
        error = if (Ipv4.parseList(settings.p25Destinations) == null) "One or more addresses, separated by commas" else null,
        onChange = { v -> update { it.copy(p25Destinations = v) } },
        filter = { it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' || ch == ' ' }.take(200) },
    )
    IntSetting("UDP port (same on every station)", settings.p25Port, 1024..65535) { v -> update { it.copy(p25Port = v) } }
    Text(
        "255.255.255.255 sends every packet once to all radios on the channel; the radio needs Limited Broadcast " +
            "enabled. Or list the other radios' air-interface IP addresses: each packet then goes out once per " +
            "address, which costs air time. The radio does the modulation, channel access and keying, so the audio, " +
            "PTT, transmit and channel access settings are not shown. Not yet tested with a real radio.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** Paired device and key/unkey commands for Bluetooth serial PTT. Shared by Settings and the setup guide. */
@Composable
internal fun BluetoothPttSettings(settings: Settings, update: ((Settings) -> Settings) -> Unit) {
    BluetoothDevicePicker(settings.btPttDevice, "Choose the device that keys your radio.", settings, update) { v ->
        update { it.copy(btPttDevice = v) }
    }

    Spacer(Modifier.height(8.dp))
    Text("Commands", style = MaterialTheme.typography.labelLarge)
    ChoiceSetting(BtPttPreset.entries.map { it to it.label }, settings.btPttPreset) { v -> update { it.copy(btPttPreset = v) } }
    if (settings.btPttPreset == BtPttPreset.CUSTOM) {
        TextSetting(
            label = "Command to transmit",
            value = settings.btKeyCommand,
            error = PttCommand.errorOf(settings.btKeyCommand),
            onChange = { v -> update { it.copy(btKeyCommand = v) } },
            filter = { it.take(4 * PttCommand.MAX_BYTES) },
        )
        TextSetting(
            label = "Command to stop transmitting",
            value = settings.btUnkeyCommand,
            error = PttCommand.errorOf(settings.btUnkeyCommand),
            onChange = { v -> update { it.copy(btUnkeyCommand = v) } },
            filter = { it.take(4 * PttCommand.MAX_BYTES) },
        )
        Text("Write special bytes as \\r, \\n or \\xHH.", style = MaterialTheme.typography.bodySmall)
    }
    Text(
        "Only PTT goes over Bluetooth; audio still uses the devices chosen above. If the Bluetooth link drops while " +
            "transmitting, the stop command cannot reach the radio, so set a short time-out timer (TOT) on the radio.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** Mode-specific controls: passphrase entry, cipher choice and the key slots that the mode uses. */
@Composable
private fun EncryptionDetails(container: AppContainer, settings: Settings, update: ((Settings) -> Settings) -> Unit) {
    val mode = settings.encryption
    if (mode == EncryptionMode.OFF) return
    val scope = rememberCoroutineScope()
    var keyTick by remember { mutableIntStateOf(0) }
    var pendingSlot by rememberSaveable { mutableStateOf(KeySlot.SYMMETRIC) }
    var importMessage by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val slot = pendingSlot
            scope.launch {
                importMessage = withContext(Dispatchers.IO) {
                    try {
                        "Imported: " + container.keyFiles.import(slot, uri)
                    } catch (e: Exception) {
                        "Not imported: ${e.message}"
                    }
                }
                keyTick++
            }
        }
    }

    @Composable
    fun slotRow(slot: KeySlot, label: String, hint: String) {
        val info by produceState<String?>(initialValue = null, slot, keyTick) {
            value = withContext(Dispatchers.IO) { container.keyFiles.info(slot) }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(info ?: "Not loaded", style = MaterialTheme.typography.bodyMedium, color = if (info == null) WarnAmber else RxGreen)
            Text(hint, style = MaterialTheme.typography.bodySmall)
            Row {
                OutlinedButton(onClick = {
                    pendingSlot = slot
                    importMessage = null
                    picker.launch(arrayOf("*/*"))
                }) { Text(if (info == null) "Import file" else "Replace") }
                if (info != null) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { container.keyFiles.clear(slot) }
                            keyTick++
                        }
                    }) { Text("Remove") }
                }
            }
        }
    }

    when (mode) {
        EncryptionMode.OFF -> {}
        EncryptionMode.PASSPHRASE -> {
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
        }
        EncryptionMode.KEY_FILE -> slotRow(
            KeySlot.SYMMETRIC, "Shared key",
            "A 128, 192 or 256-bit key: raw bytes, hex, or Base64. Every station loads the same file. " +
                "Example: openssl rand -out takwave.key 32",
        )
        EncryptionMode.ECDH_PAIR -> {
            slotRow(
                KeySlot.PRIVATE, "My private key",
                "PKCS#8 PEM, EC P-256 or X25519, not password-protected. Never share this file.",
            )
            slotRow(
                KeySlot.PUBLIC, "Peer's public key",
                "PEM public key of the one other station. Both stations must use the same key type. " +
                    "Compare the key id shown here with the other operator.",
            )
        }
        EncryptionMode.ECIES_TEAM -> {
            slotRow(
                KeySlot.PUBLIC, "Team public key",
                "PEM public key, EC P-256 or X25519. Enough to send. All stations show the same key id.",
            )
            slotRow(
                KeySlot.PRIVATE, "Team private key (optional)",
                "Needed to read traffic. Leave it off devices that only report, so a lost device cannot decrypt the team's messages.",
            )
        }
    }
    importMessage?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("Imported")) RxGreen else WarnAmber)
    }
    if (mode != EncryptionMode.ECIES_TEAM) {
        Spacer(Modifier.height(8.dp))
        Text("Cipher", style = MaterialTheme.typography.labelLarge)
        ChoiceSetting(CipherChoice.entries.map { it to it.label }, settings.cipher) { v -> update { it.copy(cipher = v) } }
    }
    Spacer(Modifier.height(8.dp))
    SwitchSetting(
        "Encrypt my callsign too",
        "Send every packet with the shared placeholder ${Gateway.HIDDEN_SOURCE} in the radio header and your " +
            "callsign inside the encrypted part, so listeners without the key cannot tell who is transmitting. " +
            "Older TAKwave versions cannot read these packets, so update every station first.",
        settings.hideCallsign,
    ) { v -> update { it.copy(hideCallsign = v) } }
    if (settings.hideCallsign) {
        WarningCard(
            "Your callsign no longer goes out in the clear, so these packets do not identify your station. " +
                "Identify it some other way as your license requires (for example a periodic clear voice or " +
                "Morse ID), and check that your license allows encrypted station identification.",
        )
    }
    Text(
        when (mode) {
            EncryptionMode.ECIES_TEAM -> "Each packet is encrypted to the team key with a one-time key (ECIES, AES-256-GCM). Adds 48 to 49 bytes per packet"
            else -> "Adds 28 bytes per packet"
        } + (if (settings.hideCallsign) ", plus up to 8 for the callsign. " else ". Your callsign is still sent in the clear; only the message content is encrypted. ") +
            "RSA keys are not supported: an RSA ciphertext does not fit in a radio packet.",
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun audioChoices(options: List<AudioDeviceOption>, selected: String): List<Pair<String, String>> = buildList {
    add("" to "System default")
    options.forEach { add(it.key to it.label) }
    if (selected.isNotEmpty() && options.none { it.key == selected }) add(selected to "$selected (not connected)")
}

@Composable
internal fun TextSetting(
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
internal fun IntSetting(
    label: String,
    value: Int,
    range: IntRange,
    /** Returns a reason when an in-range value is still not allowed. */
    reject: (Int) -> String? = { null },
    onChange: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val rejection = parsed?.takeIf { it in range }?.let(reject)
    val valid = parsed != null && parsed in range && rejection == null
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new.filter { it.isDigit() }.take(6)
            val v = text.toIntOrNull()
            // Only valid values are stored; an out-of-range entry leaves the old setting in place.
            if (v != null && v in range && reject(v) == null) onChange(v)
        },
        label = { Text(label) },
        singleLine = true,
        isError = !valid,
        supportingText = if (valid) null else ({ Text(rejection ?: "Enter ${range.first} to ${range.last}") }),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
internal fun SwitchSetting(label: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun <T> ChoiceSetting(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
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
