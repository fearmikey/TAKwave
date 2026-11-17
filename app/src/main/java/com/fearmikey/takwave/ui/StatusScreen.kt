// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.fearmikey.takwave.AppContainer
import com.fearmikey.takwave.core.codec.EmergencyMessage
import com.fearmikey.takwave.core.gateway.HeardAlert
import com.fearmikey.takwave.core.gateway.HeardStation
import com.fearmikey.takwave.core.stats.ChannelEvent
import com.fearmikey.takwave.core.stats.ChannelHealth
import com.fearmikey.takwave.core.io.RfState
import com.fearmikey.takwave.core.sched.Geo
import com.fearmikey.takwave.data.ModemBackend
import com.fearmikey.takwave.data.PttMethod
import com.fearmikey.takwave.data.Settings
import com.fearmikey.takwave.engine.RadioStatus
import com.fearmikey.takwave.net.Sharing
import com.fearmikey.takwave.service.TakwaveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun StatusScreen(container: AppContainer, settings: Settings, status: RadioStatus, onCalibrate: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Notifications are optional; the microphone is not when the soft modem is selected.
        if (settings.backend == ModemBackend.SOFT && !granted(context, Manifest.permission.RECORD_AUDIO)) {
            message = "The soft modem needs microphone permission to hear the radio."
        } else {
            message = null
            TakwaveService.start(context)
        }
    }

    fun startService() {
        val needed = buildList {
            if (settings.backend == ModemBackend.SOFT && !granted(context, Manifest.permission.RECORD_AUDIO)) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            // Optional here: without it the engine reports that the Bluetooth PTT or TNC is unavailable and does not transmit.
            val usesBluetooth = settings.backend == ModemBackend.KISS_BLUETOOTH ||
                (settings.backend == ModemBackend.SOFT && settings.pttMethod == PttMethod.BLUETOOTH)
            if (usesBluetooth &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(context, Manifest.permission.BLUETOOTH_CONNECT)
            ) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (needed.isEmpty()) {
            message = null
            TakwaveService.start(context)
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScrollbar(scroll)
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (status.running) "Gateway running" else "Gateway stopped", style = MaterialTheme.typography.titleLarge)
                Text(
                    settings.address?.let { "Station $it" } ?: "No callsign set",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (settings.address == null) WarnAmber else Color.Unspecified,
                )
            }
            if (status.running) {
                Button(
                    onClick = { TakwaveService.stop(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = TxRed, contentColor = Color.White),
                ) { Text("Stop") }
            } else {
                Button(onClick = { startService() }) { Text("Start") }
            }
        }

        for (a in status.ownAlerts) OwnAlertCard(a)
        if (status.heardAlerts.isNotEmpty()) {
            val now = System.currentTimeMillis()
            for (a in status.heardAlerts) HeardAlertCard(a, status.localPosition, now)
        }

        message?.let { WarningCard(it) }
        if (settings.address == null) {
            WarningCard("Set your callsign in Settings. TAKwave will not transmit without a valid callsign.")
        }
        for (w in status.warnings) WarningCard(w)

        Section("Radio") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RfBadge(if (status.running) status.rf else null)
                Spacer(Modifier.width(12.dp))
                Text(status.backendInfo.ifEmpty { settings.backend.label }, style = MaterialTheme.typography.bodyMedium)
            }
            if (status.cipherInfo.isNotEmpty()) {
                Text("Encrypted: ${status.cipherInfo}", style = MaterialTheme.typography.bodySmall, color = WarnAmber, modifier = Modifier.padding(top = 6.dp))
            }
            if (status.slotInfo.isNotEmpty()) {
                Text(status.slotInfo, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            if (status.repeatInfo.isNotEmpty()) {
                Text(
                    "${status.repeatInfo}; ${status.heldCount} held",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Input level", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                if (settings.backend == ModemBackend.SOFT) {
                    TextButton(onClick = onCalibrate) { Text("Adjust levels") }
                }
            }
            LevelMeter(status.level, status.peak)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stat("TAK clients", status.clients.toString())
                Stat("TX queue", status.queueDepth.toString())
                Stat("Stations heard", status.heard.size.toString())
            }
        }

        Section("ATAK connection") {
            Text(
                "Server ${if (settings.allowLan) "0.0.0.0" else "127.0.0.1"}:${settings.takPort} (TCP, plain CoT XML)",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    try {
                        Sharing.shareDataPackage(context.applicationContext, settings.takPort)
                    } catch (e: Exception) {
                        message = "Could not create the data package: ${e.message}"
                    }
                }
            }) { Text("Export ATAK data package") }
            Text(
                "Share the zip to ATAK (Import Manager) to add the TAKwave server connection.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        BatteryCard()

        val health = status.health
        if (status.running && health != null) ChannelHealthSection(health)

        Section("Heard") {
            if (status.heard.isEmpty()) {
                Text("No stations heard yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                val now = System.currentTimeMillis()
                status.heard.forEachIndexed { i, h ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    HeardRow(h, status.localPosition, now)
                }
            }
        }
    }
}

@Composable
private fun RfBadge(state: RfState?) {
    val (label, color) = when (state) {
        RfState.TX -> "TX" to TxRed
        RfState.RX -> "RX-DCD" to RxGreen
        RfState.IDLE -> "IDLE" to IdleGrey
        null -> "OFF" to IdleGrey
    }
    Text(
        label,
        color = Color.Black,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HeardRow(h: HeardStation, local: Pair<Double, Double>?, nowMs: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(h.source, style = MaterialTheme.typography.titleMedium)
            val where = if (local != null && h.lat != null && h.lon != null) {
                val d = Geo.distanceM(local.first, local.second, h.lat!!, h.lon!!)
                val b = Geo.bearingDeg(local.first, local.second, h.lat!!, h.lon!!)
                "${formatDistance(d)} at %03d°".format(b.toInt())
            } else if (h.lat == null) {
                "no position received"
            } else {
                "distance unknown (no local position)"
            }
            Text(listOfNotNull(h.takCallsign, where).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatAge(nowMs - h.lastHeardMs), style = MaterialTheme.typography.bodyMedium)
            val reception = h.pliReception?.let { " · PLI ${percent(it)}" } ?: ""
            Text("${h.packets} pkt$reception", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** A card in the alarm color; alerts must stand out from ordinary warnings. */
@Composable
private fun AlertCard(title: String, detail: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = TxRed, contentColor = Color.White),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                InfoButton("Emergency alerts", HelpText.EMERGENCY)
            }
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun OwnAlertCard(a: EmergencyMessage) {
    AlertCard(
        "Sending: ${a.kind.label}",
        "Your alert goes out ahead of all other traffic and is repeated every 30 s with your position until you cancel it in ATAK.",
    )
}

@Composable
private fun HeardAlertCard(a: HeardAlert, local: Pair<Double, Double>?, nowMs: Long) {
    val m = a.message
    val who = m.text.ifEmpty { m.callsign }.ifEmpty { a.source }
    val where = if (local != null && (m.latE7 != 0 || m.lonE7 != 0)) {
        val d = Geo.distanceM(local.first, local.second, m.lat, m.lon)
        val b = Geo.bearingDeg(local.first, local.second, m.lat, m.lon)
        "${formatDistance(d)} at %03d°".format(b.toInt())
    } else {
        null
    }
    val via = if (a.relayed) "relayed by ${a.source}" else "from ${a.source}"
    AlertCard(
        "EMERGENCY: ${m.kind.label} – $who",
        listOfNotNull(where, via, "raised ${formatAge(nowMs - a.originMs)}", "last heard ${formatAge(nowMs - a.lastHeardMs)}")
            .joinToString(" · "),
    )
}

@Composable
private fun ChannelHealthSection(h: ChannelHealth) {
    val minutes = maxOf(1L, h.windowMs / 60_000L)
    Section("Channel health (last $minutes min)", info = HelpText.CHANNEL_HEALTH) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Stat("Channel busy", h.busyFraction?.let { percent(it) } ?: "n/a")
            Stat("My air time", "${percent(h.ownAirtimeFraction)} (${h.ownAirtimeMs / 1000} s)")
            Stat("Repaired by FX.25", h[ChannelEvent.RX_REPAIRED].toString())
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Received: ${h[ChannelEvent.RX_DELIVERED]} delivered, ${h[ChannelEvent.RX_REPEAT]} repeats, " +
                "${h[ChannelEvent.RX_OTHER]} other traffic, ${h[ChannelEvent.RX_INVALID]} unreadable",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Sent: ${h[ChannelEvent.TX]} new, ${h[ChannelEvent.TX_REPEAT]} repeats, ${h[ChannelEvent.TX_FAILED]} failed, " +
                "${h[ChannelEvent.QUEUE_DROPPED]} dropped from the queue",
            style = MaterialTheme.typography.bodySmall,
        )
        val hints = buildList {
            if ((h.busyFraction ?: 0f) > ChannelHealth.BUSY_WARNING) {
                add("The channel is congested. Lengthen the PLI interval or turn on time slots.")
            }
            if (h.ownAirtimeFraction > ChannelHealth.AIRTIME_WARNING) {
                add("This station is using a large share of the channel. Lengthen the PLI interval or lower the repeat limits.")
            }
            if (h[ChannelEvent.TX_FAILED] > 0) add("Some packets could not get on the air in time; see the log (list icon, top bar).")
            if (h[ChannelEvent.RX_INVALID] > 0) {
                add("Some TAKwave packets could not be read. Check that every station uses the same encryption mode and key.")
            }
        }
        for (hint in hints) Text(hint, style = MaterialTheme.typography.bodySmall, color = WarnAmber, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun percent(f: Float): String = "%.0f%%".format(f * 100)

private fun formatDistance(m: Double): String = if (m < 1000) "%.0f m".format(m) else "%.1f km".format(m / 1000)

private fun formatAge(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ${s % 60}s ago"
        else -> "${s / 3600}h ${(s % 3600) / 60}m ago"
    }
}

/** Asks for a battery optimization exemption so Doze does not suspend the gateway. */
@SuppressLint("BatteryLife")
@Composable
private fun BatteryCard() {
    val context = LocalContext.current
    val pm = remember { context.getSystemService(PowerManager::class.java) }
    var exempt by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        exempt = pm.isIgnoringBatteryOptimizations(context.packageName)
    }
    if (exempt) return
    Section("Battery") {
        Text(
            "Android may pause TAKwave when the screen is off. Allow it to ignore battery optimization so packets keep flowing.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { requestBatteryExemption(context) }) { Text("Allow background running") }
    }
}

/** Shows the system prompt that exempts TAKwave from battery optimization. */
@SuppressLint("BatteryLife")
internal fun requestBatteryExemption(context: Context) {
    val direct = Intent(
        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    try {
        context.startActivity(direct)
    } catch (e: Exception) {
        // Some devices do not offer the direct prompt; fall back to the system list.
        try {
            context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (ignored: Exception) {
            // No settings screen available; nothing more the app can do.
        }
    }
}
