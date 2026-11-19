// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fearmikey.takwave.core.radio.BluetoothRadioMatch
import com.fearmikey.takwave.core.radio.BluetoothRadioUse
import com.fearmikey.takwave.core.radio.BluetoothRadios
import com.fearmikey.takwave.data.BluetoothKissLink
import com.fearmikey.takwave.data.BtPttPreset
import com.fearmikey.takwave.data.ModemBackend
import com.fearmikey.takwave.data.PttMethod
import com.fearmikey.takwave.data.SerialLinkKind
import com.fearmikey.takwave.data.SerialRadioType
import com.fearmikey.takwave.data.Settings
import com.fearmikey.takwave.ptt.BleCompanion
import com.fearmikey.takwave.ptt.BluetoothDeviceOption

/**
 * False inside the setup guide, whose steps follow the radio setup picked on its own page: there
 * a hint only explains, and switching the modem backend is left to that page.
 */
internal val LocalAllowBackendSwitch = compositionLocalOf { true }

/** These settings set up for the recognised Bluetooth device at [address]. */
internal fun Settings.withBluetoothRadio(use: BluetoothRadioUse, address: String): Settings = when (use) {
    BluetoothRadioUse.KISS_CLASSIC -> copy(
        backend = ModemBackend.KISS_BLUETOOTH,
        kissBtDevice = address,
        kissBtLink = if (kissBtLink == BluetoothKissLink.BLE) BluetoothKissLink.AUTO else kissBtLink,
    )
    BluetoothRadioUse.KISS_BLE -> copy(backend = ModemBackend.KISS_BLUETOOTH, kissBtDevice = address, kissBtLink = BluetoothKissLink.BLE)
    BluetoothRadioUse.DSTAR_DATA -> copy(
        backend = ModemBackend.SERIAL_DATA,
        serialRadioLink = SerialLinkKind.BLUETOOTH,
        serialRadioBtDevice = address,
        serialRadioType = if (serialRadioType.isDstar) serialRadioType else SerialRadioType.DSTAR_SLOW,
    )
}

/** The Bluetooth PTT command set a recognised radio understands, if TAKwave has one for it. */
private fun BluetoothRadioMatch.pttPreset(): BtPttPreset? = when {
    model.contains("TH-D7") -> BtPttPreset.KENWOOD_HT
    model.contains("IC-705") -> BtPttPreset.ICOM_IC705
    else -> null
}

private fun BluetoothRadioMatch.advice(): String = when (use) {
    BluetoothRadioUse.KISS_CLASSIC ->
        "$model: has a KISS TNC that TAKwave can reach over Bluetooth. Switch the TNC on (in the radio's menu or its " +
            "app) and use the \"KISS over Bluetooth\" backend; no audio cable is needed."
    BluetoothRadioUse.KISS_BLE ->
        "$model: a Bluetooth LE TNC. Use the \"KISS over Bluetooth\" backend with the link set to Bluetooth LE."
    BluetoothRadioUse.DSTAR_DATA ->
        "$model: its Bluetooth serial port can carry D-STAR DV data. Use the \"D-STAR or NXDN radio data\" backend " +
            "over Bluetooth, with the radio's Bluetooth serial port function set to DV Data."
} + when (pttPreset()) {
    BtPttPreset.KENWOOD_HT -> " For Bluetooth PTT with the soft modem instead, use the Kenwood TH-D74 / TH-D75 commands."
    BtPttPreset.ICOM_IC705 -> " For Bluetooth PTT with the soft modem instead, set that function to CI-V and use the IC-705 commands."
    else -> ""
}

/**
 * Shown under the Bluetooth device picker when the chosen device is a radio or TNC TAKwave knows:
 * what it is, how it is best used, and a button to set that up. Nothing changes until tapped.
 */
@Composable
internal fun BluetoothRadioHint(option: BluetoothDeviceOption, settings: Settings?, update: (((Settings) -> Settings) -> Unit)?) {
    val match = BluetoothRadios.match(option.name) ?: return
    val allowSwitch = LocalAllowBackendSwitch.current
    Card(Modifier.padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(match.advice(), style = MaterialTheme.typography.bodySmall)
            if (settings == null || update == null) return@Column
            val target = settings.withBluetoothRadio(match.use, option.address)
            if (allowSwitch && target != settings) {
                OutlinedButton(onClick = { update { it.withBluetoothRadio(match.use, option.address) } }, Modifier.padding(top = 4.dp)) {
                    Text("Use the ${match.model} as the modem")
                }
            }
            val preset = match.pttPreset()
            val choosingPtt = settings.backend == ModemBackend.SOFT && settings.pttMethod == PttMethod.BLUETOOTH &&
                settings.btPttDevice.equals(option.address, ignoreCase = true)
            if (preset != null && choosingPtt && settings.btPttPreset != preset) {
                OutlinedButton(onClick = { update { it.copy(btPttPreset = preset) } }, Modifier.padding(top = 4.dp)) {
                    Text("Use the ${match.model} PTT commands")
                }
            }
        }
    }
}

/**
 * Opens Android's companion device chooser for TNCs that advertise the Bluetooth LE KISS service.
 * The system scans, so TAKwave needs no scan or location permission; the picked device is passed
 * to [onFound] and then appears in the device list.
 */
@Composable
internal fun BleTncFinder(onFound: (String) -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val address = if (result.resultCode == Activity.RESULT_OK) BleCompanion.addressFrom(result.data) else null
        message = if (address != null) {
            onFound(address)
            null
        } else {
            "No TNC was chosen."
        }
    }
    OutlinedButton(onClick = {
        message = "Looking for Bluetooth LE TNCs…"
        BleCompanion.find(
            context,
            onChooser = { sender ->
                message = null
                launcher.launch(IntentSenderRequest.Builder(sender).build())
            },
            onError = { reason -> message = "Not found: $reason. Switch the TNC on and keep it close to the phone." },
        )
    }) { Text("Find a Bluetooth LE TNC nearby") }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = WarnAmber) }
}
