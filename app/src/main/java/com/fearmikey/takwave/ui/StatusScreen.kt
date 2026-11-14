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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.fearmikey.takwave.core.gateway.HeardStation
import com.fearmikey.takwave.core.io.RfState
import com.fearmikey.takwave.core.sched.Geo
import com.fearmikey.takwave.data.ModemBackend
import com.fearmikey.takwave.data.Settings
import com.fearmikey.takwave.engine.RadioStatus
import com.fearmikey.takwave.net.Sharing
import com.fearmikey.takwave.service.TakwaveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun StatusScreen(container: AppContainer, settings: Settings, status: RadioStatus) {
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
        }
        if (needed.isEmpty()) {
            message = null
            TakwaveService.start(context)
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
            Spacer(Modifier.height(12.dp))
            Text("Input level", style = MaterialTheme.typography.labelMedium)
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
            Text("${h.packets} pkt", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun formatDistance(m: Double): String = if (m < 1000) "%.0f m".format(m) else "%.1f km".format(m / 1000)

private fun formatAge(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ${s % 60}s ago"
        else -> "${s / 3600}h ${(s % 3600) / 60}m ago"
    }
}

/** Asks for a battery optimisation exemption so Doze does not suspend the gateway. */
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
            "Android may pause TAKwave when the screen is off. Allow it to ignore battery optimisation so packets keep flowing.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
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
        }) { Text("Allow background running") }
    }
}
