// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave

import android.content.Intent
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.IntentCompat
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fearmikey.takwave.core.profile.TeamProfile
import com.fearmikey.takwave.data.ModemBackend
import com.fearmikey.takwave.data.Settings
import com.fearmikey.takwave.ptt.SerialDevices
import com.fearmikey.takwave.ptt.SerialLinks
import com.fearmikey.takwave.usb.UsbNetDevices
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.fearmikey.takwave.ui.AboutScreen
import com.fearmikey.takwave.ui.CalibrateScreen
import com.fearmikey.takwave.ui.LogScreen
import com.fearmikey.takwave.ui.OnboardingScreen
import com.fearmikey.takwave.ui.ProfileImportDialog
import com.fearmikey.takwave.ui.RadioDetectedDialog
import com.fearmikey.takwave.ui.RadioGuideScreen
import com.fearmikey.takwave.ui.SettingsPage
import com.fearmikey.takwave.ui.SettingsPageScreen
import com.fearmikey.takwave.ui.SettingsScreen
import com.fearmikey.takwave.ui.StatusScreen
import com.fearmikey.takwave.ui.TakwaveTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = container
        // On recreation (rotation) the launching intent was already handled.
        if (savedInstanceState == null) {
            handleProfileLink(intent)
            handleUsbAttached(intent)
        }
        setContent {
            TakwaveTheme {
                TakwaveRoot(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleProfileLink(intent)
        handleUsbAttached(intent)
    }

    /**
     * A Motorola radio plugged in (the USB device filter starts TAKwave for it). It is only noted
     * here; the dialog asks before anything changes. Other USB devices need nothing: opening the
     * app from the filter has already granted access to them.
     */
    private fun handleUsbAttached(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
        if (!SerialLinks.isMotorolaRadio(device)) return
        val link = when {
            UsbNetDevices.framingOf(device) != null -> DetectedRadio.Link.USB_NETWORK
            UsbSerialProber.getDefaultProber().probeDevice(device) != null -> DetectedRadio.Link.SERIAL
            else -> DetectedRadio.Link.NONE
        }
        val system = DetectedRadio.systemOf(device.productId, device.productName, link)
        val label = when {
            device.productId == DetectedRadio.MOTOTRBO_PORTABLE_PRODUCT_ID -> "MOTOTRBO portable"
            else -> device.productName?.takeIf { it.isNotBlank() } ?: "Motorola radio (%04x)".format(device.productId)
        }
        container.detectedRadio.value = DetectedRadio(
            deviceKey = SerialDevices.keyOf(device),
            label = label,
            link = link,
            system = system,
        )
    }

    /**
     * A takwave://profile link, typically from a QR code scanned with the camera app. It comes from
     * outside the app, so it is only queued for review: the dialog parses and validates it and
     * applies nothing until the operator confirms. Nothing else in the intent is used.
     */
    private fun handleProfileLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        if (!data.scheme.equals(TeamProfile.SCHEME, ignoreCase = true) || !data.host.equals(TeamProfile.HOST, ignoreCase = true)) return
        container.pendingProfile.value = data.toString().take(TeamProfile.MAX_LENGTH + 1)
    }
}

/**
 * Every screen except Status, which is home. They open from the top bar icons, the Settings list
 * or the Status screen and stack up; Back (arrow or gesture) closes them one at a time.
 */
private enum class Screen(val title: String, val page: SettingsPage? = null) {
    SETTINGS("Settings"),
    STATION(SettingsPage.STATION.title, SettingsPage.STATION),
    RADIO(SettingsPage.RADIO.title, SettingsPage.RADIO),
    CHANNEL(SettingsPage.CHANNEL.title, SettingsPage.CHANNEL),
    SECURITY(SettingsPage.SECURITY.title, SettingsPage.SECURITY),
    CALIBRATE("Calibrate levels"),
    RADIO_GUIDE("Radio guide"),
    LOG("Log"),
    ABOUT("About"),
}

/** The back stack survives rotation and process death as a list of screen names. */
private val BackStackSaver = listSaver<SnapshotStateList<Screen>, String>(
    save = { stack -> stack.map { it.name } },
    restore = { names -> names.map { Screen.valueOf(it) }.toMutableStateList() },
)

private const val HOME_KEY = "STATUS"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TakwaveRoot(container: AppContainer) {
    val stack = rememberSaveable(saver = BackStackSaver) { mutableStateListOf() }
    // Keeps each open screen's saveable state (scroll position) while another is on top of it.
    val screenState = rememberSaveableStateHolder()
    fun open(screen: Screen) {
        stack.add(screen)
    }
    fun back() {
        val closed = stack.removeAt(stack.lastIndex)
        screenState.removeState(closed.name)
    }
    val settings: Settings? by container.settingsStore.settings.collectAsStateWithLifecycle(initialValue = null)
    val status by container.status.collectAsStateWithLifecycle()
    val pendingProfile by container.pendingProfile.collectAsStateWithLifecycle()
    val detectedRadio by container.detectedRadio.collectAsStateWithLifecycle()

    // A profile from a link, file or the clipboard is reviewed here, over whatever screen is showing.
    val forReview = pendingProfile
    settings?.let { s ->
        if (forReview != null) ProfileImportDialog(container, s, forReview) { container.pendingProfile.value = null }
        // A Motorola radio was plugged in. Not asked again when it is already the configured modem.
        val radio = detectedRadio
        if (radio != null && forReview == null) {
            val alreadyUsed = (s.backend == ModemBackend.P25_DATA && s.p25Device == radio.deviceKey && s.p25AutoDetect) ||
                (s.backend == ModemBackend.DMR_DATA && s.dmrDevice == radio.deviceKey)
            if (alreadyUsed) {
                LaunchedEffect(radio) { container.detectedRadio.value = null }
            } else {
                RadioDetectedDialog(container, s, radio, status.running) { container.detectedRadio.value = null }
            }
        }
    }

    // First run (or "Run the setup guide again" in Settings): the guide replaces everything, and
    // the app comes back to the Status screen afterward.
    val current = settings
    if (current != null && !current.onboardingDone) {
        LaunchedEffect(Unit) { while (stack.isNotEmpty()) back() }
        OnboardingScreen(container, current)
        return
    }

    BackHandler(enabled = stack.isNotEmpty()) { back() }

    val top = stack.lastOrNull()
    Scaffold(
        topBar = {
            if (top != null) {
                TopAppBar(
                    title = { Text(top.title) },
                    navigationIcon = {
                        IconButton(onClick = { back() }) {
                            Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("TAKwave") },
                    actions = {
                        IconButton(onClick = { open(Screen.LOG) }) {
                            Icon(painterResource(R.drawable.ic_log), contentDescription = "Packet log")
                        }
                        IconButton(onClick = { open(Screen.SETTINGS) }) {
                            Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                        }
                        IconButton(onClick = { open(Screen.ABOUT) }) {
                            Icon(painterResource(R.drawable.ic_about), contentDescription = "About TAKwave")
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            val s = settings ?: return@Box // DataStore has not delivered the first value yet.
            val page = top?.page
            screenState.SaveableStateProvider(top?.name ?: HOME_KEY) {
                when {
                    page != null -> SettingsPageScreen(
                        page, container, s, status,
                        onCalibrate = { open(Screen.CALIBRATE) },
                        onRadioGuide = { open(Screen.RADIO_GUIDE) },
                    )
                    top == null -> StatusScreen(container, s, status, onCalibrate = { open(Screen.CALIBRATE) })
                    top == Screen.SETTINGS -> SettingsScreen(container, s, status) { p ->
                        open(Screen.entries.first { it.page == p })
                    }
                    top == Screen.CALIBRATE -> CalibrateScreen(container, s, status)
                    top == Screen.RADIO_GUIDE -> RadioGuideScreen(container, s, onCalibrate = { open(Screen.CALIBRATE) })
                    top == Screen.LOG -> LogScreen(container)
                    else -> AboutScreen()
                }
            }
        }
    }
}
