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
import com.fearmikey.takwave.data.BtPttPreset
import com.fearmikey.takwave.ptt.BluetoothDevices
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

    val deviceTick = rememberDeviceTick()

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
            IntSetting("TCP port", settings.takPort, 1024..65535) { v -> update { it.copy(takPort = v) } }
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
                    "The TNC (for example Dire Wolf) does the modulation, channel access and PTT. " +
                        "The audio, PTT, TX and CSMA settings below are not used.",
                    style = MaterialTheme.typography.bodySmall,
                )
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
                        SerialPttSettings(settings, deviceTick.intValue) { update(it) }
                    }
                    PttMethod.BLUETOOTH -> {
                        Spacer(Modifier.height(8.dp))
                        BluetoothPttSettings(settings) { update(it) }
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
        }

        Section("Encryption", info = HelpText.ENCRYPTION) {
            WarningCard(
                "Encryption is NOT permitted on amateur radio frequencies. Leave this Off unless you are operating " +
                    "under a Part 90 (business band) licence that allows it.",
            )
            ChoiceSetting(EncryptionMode.entries.map { it to it.label }, settings.encryption) { v -> update { it.copy(encryption = v) } }
            EncryptionDetails(container, settings) { transform -> update(transform) }
        }

        TeamProfileSection(container, settings)

        Section("Setup guide") {
            Text(
                "Walks through permissions and the settings your radio needs, one step at a time.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { update { it.copy(onboardingDone = false) } }) { Text("Run the setup guide again") }
        }
        Spacer(Modifier.height(24.dp))
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

/** USB serial adapter, control line and polarity. Shared by Settings and the setup guide. */
@Composable
internal fun SerialPttSettings(settings: Settings, deviceTick: Int, update: ((Settings) -> Settings) -> Unit) {
    val context = LocalContext.current
    val devices = remember(deviceTick) { SerialDevices.list(context) }
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

/** Paired device and key/unkey commands for Bluetooth serial PTT. Shared by Settings and the setup guide. */
@Composable
internal fun BluetoothPttSettings(settings: Settings, update: ((Settings) -> Settings) -> Unit) {
    val context = LocalContext.current
    // Re-read the paired devices after a permission prompt or a trip to the system settings.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val allowed = remember(tick) { BluetoothDevices.hasPermission(context) }
    val devices = remember(tick) { BluetoothDevices.paired(context) }

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
            if (settings.btPttDevice.isNotEmpty() && devices.none { it.address == settings.btPttDevice }) {
                add(settings.btPttDevice to "${settings.btPttDevice} (not paired)")
            }
        }
        ChoiceSetting(choices, settings.btPttDevice) { v -> update { it.copy(btPttDevice = v) } }
        if (devices.isEmpty()) {
            Text(
                "No paired devices, or Bluetooth is off. Pair the radio or adapter in Android's Bluetooth settings first.",
                style = MaterialTheme.typography.bodySmall,
                color = WarnAmber,
            )
        } else if (settings.btPttDevice.isEmpty()) {
            Text("Choose the device that keys your radio.", style = MaterialTheme.typography.bodySmall, color = WarnAmber)
        }
    }
    OutlinedButton(onClick = {
        try {
            context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (e: Exception) {
            // No Bluetooth settings screen on this device.
        }
    }) { Text("Open Bluetooth settings") }

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
    Text(
        when (mode) {
            EncryptionMode.ECIES_TEAM -> "Each packet is encrypted to the team key with a one-time key (ECIES, AES-256-GCM). Adds 48 to 49 bytes per packet."
            else -> "Adds 28 bytes per packet."
        } + " Your callsign is still sent in the clear; only the message content is encrypted. " +
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
internal fun IntSetting(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
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
