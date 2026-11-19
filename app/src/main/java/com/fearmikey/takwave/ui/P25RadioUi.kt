// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fearmikey.takwave.AppContainer
import com.fearmikey.takwave.DetectedRadio
import com.fearmikey.takwave.core.ip.DmrVendor
import com.fearmikey.takwave.core.ip.IpFraming
import com.fearmikey.takwave.core.ip.Ipv4
import com.fearmikey.takwave.data.ModemBackend
import com.fearmikey.takwave.data.SerialLinkKind
import com.fearmikey.takwave.data.Settings
import com.fearmikey.takwave.ptt.SerialLinks
import com.fearmikey.takwave.service.TakwaveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown when a Motorola radio is plugged in. Nothing changes until the operator agrees; then the
 * modem backend becomes P25 packet data (auto-detect on) or MOTOTRBO data on that radio, and a
 * running gateway is restarted so it takes effect. APX portables and MOTOTRBO radios offer a USB
 * network link (RNDIS) and share Motorola's vendor id, so when the product name does not tell
 * them apart the operator picks; older ASTRO radios may offer a USB serial port. A radio that
 * offers neither only gets an explanation.
 */
@Composable
fun RadioDetectedDialog(container: AppContainer, settings: Settings, radio: DetectedRadio, running: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun apply(change: (Settings) -> Settings) {
        scope.launch {
            container.settingsStore.update(change)
            if (running) {
                TakwaveService.stop(context)
                // Give the old instance time to release the port.
                delay(800)
                TakwaveService.start(context)
            }
            onDone()
        }
    }
    val useP25: (Settings) -> Settings = {
        it.copy(backend = ModemBackend.P25_DATA, p25Link = SerialLinkKind.USB, p25Device = radio.deviceKey, p25AutoDetect = true)
    }
    val useDmr: (Settings) -> Settings = {
        val motorola = if (it.dmrVendor == DmrVendor.MOTOTRBO) it else it.withDmrVendor(DmrVendor.MOTOTRBO)
        motorola.copy(backend = ModemBackend.DMR_DATA, dmrDevice = radio.deviceKey)
    }
    val replaces = { target: ModemBackend ->
        (if (settings.backend != target) " This replaces the current modem backend (${settings.backend.label})." else "") +
            (if (running) " The gateway restarts to apply it." else "")
    }
    if (radio.link == DetectedRadio.Link.USB_NETWORK && radio.system != DetectedRadio.System.P25) {
        val known = radio.system == DetectedRadio.System.DMR
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text("Motorola radio connected") },
            text = {
                Text(
                    if (known) {
                        "Use ${radio.label} as TAKwave's modem? TAKwave will send its packets as DMR data calls " +
                            "to talkgroup ${settings.dmrTalkgroup} through the radio's USB network link." + replaces(ModemBackend.DMR_DATA) +
                            "\n\nThe radio needs \"Forward to PC\" on in its programming; tap the \"i\" in Settings > " +
                            "Radio & audio > DMR radio data for the details."
                    } else {
                        "${radio.label} offers a USB network link. Is it a MOTOTRBO (DMR) radio, such as an XPR or " +
                            "SL, or an APX (P25) radio? TAKwave then uses it as the modem." +
                            (if (running) " The gateway restarts to apply it." else "")
                    },
                )
            },
            confirmButton = {
                Row {
                    if (!known) TextButton(onClick = { apply(useP25) }) { Text("APX (P25)") }
                    TextButton(onClick = { apply(useDmr) }) { Text(if (known) "Use this radio" else "MOTOTRBO (DMR)") }
                }
            },
            dismissButton = { TextButton(onClick = onDone) { Text("Not now") } },
        )
        return
    }
    if (radio.link == DetectedRadio.Link.NONE) {
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text("Motorola radio connected") },
            text = {
                Text(
                    "${radio.label} offers neither a USB network link nor a USB serial port on this cable, so TAKwave " +
                        "cannot use it for packet data. If the radio has an RS-232 data cable, connect that through a " +
                        "USB serial adapter and choose \"P25 radio packet data\" in Settings > Radio & audio.",
                )
            },
            confirmButton = { TextButton(onClick = onDone) { Text("OK") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Motorola radio connected") },
        text = {
            Text(
                "Use ${radio.label} as TAKwave's modem? TAKwave will send its packets as P25 packet data through the " +
                    "radio" + when (radio.link) {
                        DetectedRadio.Link.USB_NETWORK -> " over its USB network link, and ask the radio for the addresses each time it connects."
                        else -> " and find the radio's data port settings by itself each time it connects."
                    } +
                    replaces(ModemBackend.P25_DATA) +
                    "\n\nThe radio needs the packet data option; tap the \"i\" in Settings > Radio & audio > P25 radio packet data for the details.",
            )
        },
        confirmButton = {
            TextButton(onClick = { apply(useP25) }) { Text("Use this radio") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Not now") } },
    )
}

/**
 * Probes the chosen serial port for the radio's baud rate, framing and address, and stores what it
 * finds. The port cannot be shared, so it is refused while the gateway runs (which probes by itself
 * when auto-detect is on).
 */
@Composable
internal fun P25DetectButton(settings: Settings, running: Boolean, update: ((Settings) -> Settings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    OutlinedButton(
        enabled = !busy,
        onClick = {
            if (running) {
                message = "Stop the gateway first: it is using the radio's link. With auto-detect on, it detects the radio by itself."
                ok = false
                return@OutlinedButton
            }
            busy = true
            message = "Looking for the radio…"
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    try {
                        val found = SerialLinks.probe(context, settings) { progress -> message = "$progress…" }
                        Result.success(found)
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
                outcome.onSuccess { found ->
                    update { it.withP25Detected(found) }
                    ok = true
                    message = "Found: ${found.framing.name}" +
                        (if (settings.p25Link == SerialLinkKind.BLUETOOTH) " over Bluetooth" else " at ${found.baud} baud") +
                        (found.radioAddress?.let { ", radio at ${Ipv4.format(it)}" } ?: "") +
                        ". Saved." + if (found.framing == IpFraming.PPP) " Addresses are agreed with the radio when the gateway connects." else ""
                }.onFailure { e ->
                    ok = false
                    message = "Not found: ${e.message ?: e.javaClass.simpleName}"
                }
                busy = false
            }
        },
    ) { Text(if (busy) "Detecting…" else "Detect radio settings now") }
    message?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (busy) MaterialTheme.colorScheme.onSurface else if (ok) RxGreen else WarnAmber,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Spacer(Modifier.height(4.dp))
}
