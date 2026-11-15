// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ui

import android.Manifest
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.fearmikey.takwave.AppContainer
import com.fearmikey.takwave.core.ax25.Ax25Address
import com.fearmikey.takwave.data.ModemBackend
import com.fearmikey.takwave.data.PttMethod
import com.fearmikey.takwave.data.Settings
import kotlinx.coroutines.launch

/** How the radio is attached. Picking one decides which quick-start steps follow. */
private enum class RadioSetup(val label: String, val detail: String) {
    USB_INTERFACE(
        "USB interface with PTT",
        "A Digirig or similar: USB sound card plus a serial line that keys the radio. Recommended.",
    ),
    VOX_CABLE(
        "Audio cable, radio on VOX",
        "A plain audio cable or USB sound card. The radio keys itself when it hears audio.",
    ),
    BLUETOOTH(
        "Bluetooth serial PTT",
        "Audio through a cable or USB sound card; the radio is keyed by a command over a Bluetooth serial link.",
    ),
    KISS_TNC(
        "External KISS TNC",
        "A hardware TNC or Dire Wolf does the modem work. TAKwave connects to it over TCP.",
    ),
    ACOUSTIC(
        "No radio yet (acoustic test)",
        "Two phones next to each other, using speaker and microphone. For trying TAKwave out.",
    ),
}

private enum class Step { WELCOME, PERMISSIONS, OFFER, CALLSIGN, RADIO, AUDIO, PTT, KISS, TRANSMIT, DONE }

private fun stepsFor(setup: RadioSetup?): List<Step> = buildList {
    add(Step.WELCOME)
    add(Step.PERMISSIONS)
    add(Step.OFFER)
    add(Step.CALLSIGN)
    add(Step.RADIO)
    when (setup) {
        RadioSetup.USB_INTERFACE, RadioSetup.BLUETOOTH -> {
            add(Step.AUDIO)
            add(Step.PTT)
            add(Step.TRANSMIT)
        }
        RadioSetup.VOX_CABLE -> {
            add(Step.AUDIO)
            add(Step.TRANSMIT)
        }
        RadioSetup.KISS_TNC -> add(Step.KISS)
        RadioSetup.ACOUSTIC, null -> {}
    }
    add(Step.DONE)
}

/** The settings that follow directly from the way the radio is attached. */
private fun applySetup(s: Settings, setup: RadioSetup): Settings = when (setup) {
    RadioSetup.USB_INTERFACE -> s.copy(backend = ModemBackend.SOFT, pttMethod = PttMethod.SERIAL)
    // VOX needs time to trip before data starts.
    RadioSetup.VOX_CABLE -> s.copy(backend = ModemBackend.SOFT, pttMethod = PttMethod.VOX, txDelayMs = maxOf(s.txDelayMs, 500))
    RadioSetup.BLUETOOTH -> s.copy(backend = ModemBackend.SOFT, pttMethod = PttMethod.BLUETOOTH)
    RadioSetup.KISS_TNC -> s.copy(backend = ModemBackend.KISS_TCP)
    RadioSetup.ACOUSTIC -> s.copy(backend = ModemBackend.SOFT, pttMethod = PttMethod.VOX, inputDevice = "", outputDevice = "")
}

/**
 * First-run guide: permissions, then an optional quick start that asks only for the settings the
 * chosen radio setup needs. Every value is stored as it is entered, using the same controls as
 * the Settings tab. Finishing or skipping sets [Settings.onboardingDone].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(container: AppContainer, settings: Settings) {
    val scope = rememberCoroutineScope()
    fun update(transform: (Settings) -> Settings) {
        scope.launch { container.settingsStore.update(transform) }
    }
    fun finish() = update { it.copy(onboardingDone = true) }

    var setup by rememberSaveable { mutableStateOf<RadioSetup?>(null) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val steps = stepsFor(setup)
    val step = steps[index.coerceIn(0, steps.lastIndex)]
    val deviceTick = rememberDeviceTick()

    BackHandler(enabled = index > 0) { index-- }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("TAKwave setup") },
                    actions = { TextButton(onClick = { finish() }) { Text("Skip") } },
                )
                LinearProgressIndicator(
                    progress = { (index + 1f) / steps.size },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (index > 0) OutlinedButton(onClick = { index-- }) { Text("Back") }
                    Spacer(Modifier.weight(1f))
                    when (step) {
                        // The question is answered with the two buttons on the page.
                        Step.OFFER -> {}
                        Step.DONE -> Button(onClick = { finish() }) { Text("Finish") }
                        else -> Button(
                            enabled = when (step) {
                                Step.CALLSIGN -> settings.address != null
                                Step.RADIO -> setup != null
                                else -> true
                            },
                            onClick = { index++ },
                        ) {
                            Text(
                                when (step) {
                                    Step.WELCOME -> "Get started"
                                    Step.PERMISSIONS -> "Continue"
                                    else -> "Next"
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        // Keyed by step so each page starts scrolled to the top with fresh text fields.
        key(step) {
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .verticalScrollbar(scroll)
                    .verticalScroll(scroll)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                when (step) {
                    Step.WELCOME -> WelcomePage()
                    Step.PERMISSIONS -> PermissionsPage()
                    Step.OFFER -> OfferPage(onYes = { index++ }, onNo = { finish() })
                    Step.CALLSIGN -> CallsignPage(settings) { update(it) }
                    Step.RADIO -> RadioPage(setup) { chosen ->
                        setup = chosen
                        update { applySetup(it, chosen) }
                    }
                    Step.AUDIO -> {
                        StepHeader("Audio connection", "Tell TAKwave which audio device is wired to the radio. Plug the interface in first.")
                        Section("Audio devices", info = HelpText.AUDIO) {
                            AudioDeviceSettings(settings, deviceTick) { update(it) }
                        }
                    }
                    Step.PTT -> if (setup == RadioSetup.BLUETOOTH) {
                        StepHeader("Bluetooth PTT", "Choose the paired Bluetooth device that keys the radio, and the commands it understands.")
                        Section("Bluetooth serial PTT", info = HelpText.BLUETOOTH_PTT) {
                            BluetoothPttSettings(settings) { update(it) }
                        }
                    } else {
                        StepHeader("PTT adapter", "Choose the USB serial adapter that keys the radio. Turn the radio's own VOX off.")
                        Section("Serial PTT", info = HelpText.SERIAL_PTT) {
                            SerialPttSettings(settings, deviceTick.intValue) { update(it) }
                        }
                    }
                    Step.KISS -> {
                        StepHeader("KISS TNC", "Where does the TNC accept KISS connections?")
                        Section("KISS over TCP", info = HelpText.KISS) {
                            TextSetting(
                                label = "KISS TNC host",
                                value = settings.kissHost,
                                error = if (settings.kissHost.isBlank()) "Required" else null,
                                onChange = { v -> update { it.copy(kissHost = v) } },
                                filter = { it.trim().take(253) },
                            )
                            IntSetting("KISS TNC port", settings.kissPort, 1..65535) { v -> update { it.copy(kissPort = v) } }
                        }
                    }
                    Step.TRANSMIT -> {
                        StepHeader(
                            "Transmit timing and level",
                            "These starting values work for most radios. You can fine-tune them later on the Calibrate tab.",
                        )
                        Section("Transmit", info = HelpText.TRANSMIT) {
                            IntSetting("TXDELAY (ms)", settings.txDelayMs, 50..2000) { v -> update { it.copy(txDelayMs = v) } }
                            IntSetting("TX audio level (%)", settings.txLevel, 1..100) { v -> update { it.copy(txLevel = v) } }
                            if (settings.pttMethod == PttMethod.VOX) {
                                Text(
                                    "With VOX, turn VOX on in the radio's menu at its most sensitive setting. If the other " +
                                        "station misses the start of your packets, raise TXDELAY.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    Step.DONE -> DonePage(settings, setup)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun StepHeader(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
}

@Composable
private fun WelcomePage() {
    StepHeader(
        "Welcome to TAKwave",
        "TAKwave carries ATAK positions, chat and markers over an ordinary FM radio. This short guide gets the " +
            "app ready: first the permissions Android needs, then, if you like, the settings for your radio.",
    )
    WarningCard(
        "You need a licence to transmit, and you are responsible for following the rules of the radio service " +
            "you operate in.",
    )
    Text(
        "Tap the round \"i\" next to a heading whenever you are not sure what something means. You can leave " +
            "the guide at any time with Skip; everything here is also on the Settings tab.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun PermissionsPage() {
    val context = LocalContext.current
    // Bumped after every prompt and when returning from a system screen, to re-read what is granted.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val pm = remember { context.getSystemService(PowerManager::class.java) }

    StepHeader(
        "Permissions",
        "Each one is optional, and you can grant it later. TAKwave has no trackers and sends nothing to the internet.",
    )

    PermissionCard(
        title = "Microphone",
        info = HelpText.MICROPHONE,
        summary = "Needed to hear the radio. Audio is decoded on the phone and never recorded.",
        granted = remember(tick) { granted(context, Manifest.permission.RECORD_AUDIO) },
        onGrant = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PermissionCard(
            title = "Notifications",
            info = HelpText.NOTIFICATIONS,
            summary = "Shows the gateway's status and a Stop button while it runs.",
            granted = remember(tick) { granted(context, Manifest.permission.POST_NOTIFICATIONS) },
            onGrant = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        )
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PermissionCard(
            title = "Nearby devices (Bluetooth)",
            info = HelpText.NEARBY_DEVICES,
            summary = "Only for Bluetooth PTT. Skip it if your radio is keyed another way.",
            granted = remember(tick) { granted(context, Manifest.permission.BLUETOOTH_CONNECT) },
            onGrant = { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
        )
    }
    PermissionCard(
        title = "Run in the background",
        info = HelpText.BATTERY,
        summary = "Stops Android from pausing the gateway when the screen is off.",
        granted = remember(tick) { pm.isIgnoringBatteryOptimizations(context.packageName) },
        onGrant = { requestBatteryExemption(context) },
    )
    Text(
        "If a button does nothing, Android has stopped asking; allow the permission under Android Settings > Apps > TAKwave.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun PermissionCard(title: String, info: String, summary: String, granted: Boolean, onGrant: () -> Unit) {
    Section(title, info = info) {
        Text(summary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        if (granted) {
            Text("Allowed", style = MaterialTheme.typography.labelLarge, color = RxGreen)
        } else {
            OutlinedButton(onClick = onGrant) { Text("Allow") }
        }
    }
}

@Composable
private fun OfferPage(onYes: () -> Unit, onNo: () -> Unit) {
    StepHeader(
        "Quick-start guide",
        "Would you like to be walked through the settings for your radio? It takes about two minutes and only " +
            "asks for what your setup needs: your callsign, how the radio is connected, and how it is keyed.",
    )
    Button(onClick = onYes, modifier = Modifier.fillMaxWidth()) { Text("Yes, guide me") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onNo, modifier = Modifier.fillMaxWidth()) { Text("No thanks, I'll set it up myself") }
    Text(
        "You can run the guide again later from the bottom of the Settings tab.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun CallsignPage(settings: Settings, update: ((Settings) -> Settings) -> Unit) {
    StepHeader("Your station", "Every packet carries your callsign. TAKwave will not transmit without it.")
    Section("Callsign", info = HelpText.CALLSIGN) {
        TextSetting(
            label = "Callsign (required)",
            value = settings.callsign,
            capitalize = true,
            error = when {
                settings.callsign.isEmpty() -> "Required to continue"
                !Ax25Address.isValidCall(settings.callsign) -> "1 to 6 letters and digits"
                else -> null
            },
            onChange = { v -> update { it.copy(callsign = v) } },
            filter = { it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(6) },
        )
        IntSetting("SSID", settings.ssid, 0..15) { v -> update { it.copy(ssid = v) } }
        Text("If you run only one station, leave the SSID at 0.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RadioPage(selected: RadioSetup?, onSelect: (RadioSetup) -> Unit) {
    StepHeader("How is your radio connected?", "The next steps depend on this.")
    Section("Radio setup", info = HelpText.RADIO_SETUP) {
        for (option in RadioSetup.entries) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = null, modifier = Modifier.padding(8.dp))
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    Text(option.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DonePage(settings: Settings, setup: RadioSetup?) {
    StepHeader("All set", "TAKwave is configured. Here is what you chose and what to do next.")
    Section("Your setup") {
        Text("Station: ${settings.address ?: "no callsign"}", style = MaterialTheme.typography.bodyMedium)
        Text("Radio: ${setup?.label ?: "not chosen"}", style = MaterialTheme.typography.bodyMedium)
        if (settings.backend == ModemBackend.SOFT) {
            Text("PTT: ${settings.pttMethod.label}", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("TNC: ${settings.kissHost}:${settings.kissPort}", style = MaterialTheme.typography.bodyMedium)
        }
    }
    Section("Next steps") {
        val steps = buildList {
            add("On the Status tab, press Start.")
            add("Press \"Export ATAK data package\" and share it to ATAK so it connects to TAKwave.")
            if (settings.backend == ModemBackend.SOFT) {
                add("On the Calibrate tab, set the receive volume and the transmit level before going on the air.")
            }
            add("If the gateway was already running, restart it from the Settings tab so the new settings apply.")
        }
        steps.forEachIndexed { i, text ->
            Text("${i + 1}. $text", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}
