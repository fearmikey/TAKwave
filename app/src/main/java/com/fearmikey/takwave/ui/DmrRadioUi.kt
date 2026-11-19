// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fearmikey.takwave.core.ip.DmrIp
import com.fearmikey.takwave.core.ip.DmrVendor
import com.fearmikey.takwave.core.ip.Ipv4
import com.fearmikey.takwave.core.ip.MulticastDelivery
import com.fearmikey.takwave.data.Settings
import com.fearmikey.takwave.ptt.SerialDevices
import com.fearmikey.takwave.usb.UsbNetDevices

/**
 * Make, radio, addresses and destinations for a MOTOTRBO or Hytera (DMR) radio's data service on
 * its USB cable. Shared by Settings and the setup guide.
 */
@Composable
internal fun DmrDataSettings(settings: Settings, deviceTick: Int, update: ((Settings) -> Settings) -> Unit) {
    val context = LocalContext.current
    val radios = remember(deviceTick) { UsbNetDevices.list(context) }
    Text("Make", style = MaterialTheme.typography.labelLarge)
    ChoiceSetting(
        listOf(DmrVendor.MOTOTRBO to "Motorola MOTOTRBO (XPR, SL, R7, ION)", DmrVendor.HYTERA to "Hytera (PD, HP, X1 series)"),
        settings.dmrVendor,
    ) { v -> update { if (it.dmrVendor == v) it else it.withDmrVendor(v) } }
    if (settings.dmrVendor == DmrVendor.HYTERA) {
        Text(
            "Hytera support follows the same scheme as MOTOTRBO; its network numbers here are a best guess. Copy them " +
                "from the radio's programming if they differ.",
            style = MaterialTheme.typography.bodySmall,
            color = WarnAmber,
        )
    }

    Spacer(Modifier.height(8.dp))
    Text("Radio", style = MaterialTheme.typography.labelLarge)
    val choices = buildList {
        add("" to "Automatic (the first radio on USB)")
        radios.forEach { add(it.key to it.label + if (it.hasPermission) "" else " (permission needed)") }
        if (settings.dmrDevice.isNotEmpty() && radios.none { it.key == settings.dmrDevice }) {
            add(settings.dmrDevice to "${settings.dmrDevice} (not connected)")
        }
    }
    ChoiceSetting(choices, settings.dmrDevice) { v -> update { it.copy(dmrDevice = v) } }
    if (radios.isEmpty()) {
        Text(
            "Nothing detected. Plug the radio's USB programming cable into the phone with an OTG adapter and turn " +
                "the radio on. A radio that shows up nowhere (not even on a computer) has a cable or connector problem.",
            style = MaterialTheme.typography.bodySmall,
            color = WarnAmber,
        )
    }
    radios.filter { !it.hasPermission }.forEach { d ->
        OutlinedButton(onClick = { SerialDevices.requestPermission(context, d.device) }) { Text("Grant access to ${d.label}") }
    }

    Spacer(Modifier.height(8.dp))
    Text("Send to", style = MaterialTheme.typography.labelLarge)
    TextSetting(
        label = "Talkgroup ID (0 for none)",
        value = settings.dmrTalkgroup.toString(),
        error = if (settings.dmrTalkgroup != 0 && !DmrIp.validId(settings.dmrTalkgroup)) "1 to ${DmrIp.MAX_ID}, or 0" else null,
        onChange = { v -> v.toIntOrNull()?.let { n -> update { it.copy(dmrTalkgroup = n) } } },
        filter = { it.filter { ch -> ch.isDigit() }.take(8) },
    )
    TextSetting(
        label = "Also to these radio IDs (optional)",
        value = settings.dmrRadioIds,
        error = if (DmrIp.parseIds(settings.dmrRadioIds) == null) "Radio IDs from 1 to ${DmrIp.MAX_ID}, separated by commas" else null,
        onChange = { v -> update { it.copy(dmrRadioIds = v) } },
        filter = { it.filter { ch -> ch.isDigit() || ch == ',' || ch == ' ' }.take(200) },
    )
    val destinations = settings.dmrDestinations
    Text(
        if (destinations == null) {
            "Set a talkgroup or at least one radio ID."
        } else {
            "Each packet goes to " + destinations.joinToString { Ipv4.format(it) } +
                if (destinations.size > 1) ", once each (more air time)." else "."
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (destinations == null) WarnAmber else MaterialTheme.colorScheme.onSurface,
    )
    Text(
        "The talkgroup must be in the receive group list of the channel every radio is on. A talkgroup reaches " +
            "every radio on the channel with one transmission (a group data call); radio IDs get one individual " +
            "data call each.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )

    Spacer(Modifier.height(8.dp))
    SwitchSetting(
        "Get the addresses from the radio",
        "Each time the gateway connects, ask the radio for this phone's address and its own (DHCP), falling back to " +
            "the addresses below. Nothing is sent over the air while doing so.",
        settings.dmrAutoAddresses,
    ) { v -> update { it.copy(dmrAutoAddresses = v) } }
    TextSetting(
        label = "This phone's IP address on the cable",
        value = settings.dmrLocalIp,
        error = if (Ipv4.parse(settings.dmrLocalIp).let { it == null || it == 0 }) "Enter an address such as ${DmrIp.DEFAULT_LOCAL_ADDRESS}" else null,
        onChange = { v -> update { it.copy(dmrLocalIp = v) } },
        filter = { it.filter { ch -> ch.isDigit() || ch == '.' }.take(15) },
    )
    TextSetting(
        label = "Radio's IP address on the cable",
        value = settings.dmrRadioIp,
        error = if (Ipv4.parse(settings.dmrRadioIp) == null) "Enter an address such as ${DmrIp.DEFAULT_RADIO_ADDRESS}" else null,
        onChange = { v -> update { it.copy(dmrRadioIp = v) } },
        filter = { it.filter { ch -> ch.isDigit() || ch == '.' }.take(15) },
    )

    Spacer(Modifier.height(8.dp))
    Text("Network (must match the radio's programming)", style = MaterialTheme.typography.labelLarge)
    // Keyed by make: switching it resets these values, and the fields keep their own text otherwise.
    key(settings.dmrVendor) {
        IntSetting("CAI network (radio addresses)", settings.dmrCaiNetwork, 1..223, reject = { if (it == 127) "127 is not allowed" else null }) { v ->
            update { it.copy(dmrCaiNetwork = v) }
        }
        IntSetting("CAI group network (talkgroup addresses)", settings.dmrCaiGroupNetwork, 224..239) { v -> update { it.copy(dmrCaiGroupNetwork = v) } }
    }
    IntSetting("UDP port (same on every station)", settings.dmrPort, 1024..65535) { v -> update { it.copy(dmrPort = v) } }

    Spacer(Modifier.height(8.dp))
    Text("If talkgroup packets do not get through", style = MaterialTheme.typography.labelLarge)
    Text(
        "How talkgroup packets are handed to the radio on the cable. Not yet known for certain for any radio; try " +
            "the next choice if the other radios hear nothing while individual radio IDs work.",
        style = MaterialTheme.typography.bodySmall,
    )
    ChoiceSetting(
        listOf(
            MulticastDelivery.RADIO to "To the radio, as its router (default)",
            MulticastDelivery.MULTICAST_MAC to "As IP multicast, as a Windows PC sends it",
            MulticastDelivery.BROADCAST to "As a broadcast on the cable",
        ),
        settings.dmrGroupDelivery,
    ) { v -> update { it.copy(dmrGroupDelivery = v) } }
    TextSetting(
        label = "Custom addresses (replace the talkgroup and radio IDs)",
        value = settings.dmrCustomDestinations,
        error = if (settings.dmrCustomDestinations.isNotBlank() && Ipv4.parseList(settings.dmrCustomDestinations) == null) {
            "IP addresses separated by commas, or leave empty"
        } else {
            null
        },
        onChange = { v -> update { it.copy(dmrCustomDestinations = v) } },
        filter = { it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' || ch == ' ' }.take(200) },
    )
    Text(
        "Leave empty normally. If the radio's programming software shows a different address for your talkgroup " +
            "or radios than the ones listed above, enter those here.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "The radio needs data forwarding to the PC (\"Forward to PC\") on in its programming, or it keeps received data to itself. It does the " +
            "modulation, channel access and keying, so the audio, PTT, transmit and channel access settings are not " +
            "shown. Not yet tested with a real radio.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}
