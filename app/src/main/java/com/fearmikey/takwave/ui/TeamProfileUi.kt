// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fearmikey.takwave.AppContainer
import com.fearmikey.takwave.core.profile.TeamProfile
import com.fearmikey.takwave.data.EncryptionMode
import com.fearmikey.takwave.data.Settings
import com.fearmikey.takwave.data.profileChanges
import com.fearmikey.takwave.data.toTeamProfile
import com.fearmikey.takwave.data.withTeamProfile
import com.fearmikey.takwave.net.Sharing
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/** Settings section: share this station's team settings, or load a profile from a teammate. */
@Composable
internal fun TeamProfileSection(container: AppContainer, settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) { readSmallText(context, uri) }
                if (text == null) message = "Could not read that file." else container.pendingProfile.value = text
            }
        }
    }

    Section("Team profile", info = HelpText.TEAM_PROFILE) {
        Text(
            "Share the settings your team has to agree on, or load a teammate's. Keys and passphrases are never included.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { sharing = true }) { Text("Share my setup") }
        Row {
            OutlinedButton(onClick = {
                message = null
                picker.launch(arrayOf("text/plain", "*/*"))
            }) { Text("Load from file") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val text = clipboardText(context)
                if (text.isNullOrBlank()) {
                    message = "The clipboard is empty. Copy the profile link first."
                } else {
                    message = null
                    container.pendingProfile.value = text.take(TeamProfile.MAX_LENGTH + 1)
                }
            }) { Text("Paste") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = WarnAmber) }
    }
    if (sharing) ProfileShareDialog(settings) { sharing = false }
}

/** Shows this station's profile as a QR code, with options to share it as a file or copy it as text. */
@Composable
private fun ProfileShareDialog(settings: Settings, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var assignText by rememberSaveable { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    val assignments = remember(assignText, settings.slotCount) {
        runCatching { TeamProfile.parseAssignments(assignText, settings.slotCount) }
    }
    val link = remember(name, assignments, settings) {
        assignments.getOrNull()?.let { settings.toTeamProfile(name, it).toUri() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share my setup") },
        text = {
            val scroll = rememberScrollState()
            Column(Modifier.verticalScrollbar(scroll).verticalScroll(scroll).padding(end = 10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(TeamProfile.MAX_NAME) },
                    label = { Text("Profile name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (settings.slotted) {
                    OutlinedTextField(
                        value = assignText,
                        onValueChange = { assignText = it.take(1024) },
                        label = { Text("Slot assignments (optional)") },
                        isError = assignments.isFailure,
                        supportingText = {
                            Text(assignments.exceptionOrNull()?.message ?: "One per line, for example N0CALL=1 or N0CALL-7=2")
                        },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                if (link != null) {
                    Spacer(Modifier.height(8.dp))
                    QrCode(link, Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(
                        "Scan with the other phone's camera app, or send it as a file or text.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (settings.encryption != EncryptionMode.OFF) {
                    Text(
                        "Includes the encryption mode (${settings.encryption.label}) but no keys: load them on each phone separately.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarnAmber,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (copied) Text("Copied.", style = MaterialTheme.typography.bodySmall, color = RxGreen)
            }
        },
        confirmButton = {
            TextButton(enabled = link != null, onClick = {
                val l = link ?: return@TextButton
                scope.launch(Dispatchers.IO) { Sharing.shareProfile(context.applicationContext, l) }
            }) { Text("Share file") }
        },
        dismissButton = {
            Row {
                TextButton(enabled = link != null, onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("TAKwave team profile", link))
                    copied = true
                }) { Text("Copy") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

/**
 * Asks before applying a profile that arrived from a link, a file or the clipboard, and shows
 * exactly what it would change. Nothing is applied without the operator's confirmation.
 */
@Composable
internal fun ProfileImportDialog(container: AppContainer, settings: Settings, text: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val parsed = remember(text, settings) {
        runCatching {
            val profile = TeamProfile.parse(text)
            profile to settings.withTeamProfile(profile)
        }
    }
    val result = parsed.getOrNull()
    val changes = remember(result) { result?.let { profileChanges(settings, it.second) } ?: emptyList() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Load team profile?") },
        text = {
            val scroll = rememberScrollState()
            Column(Modifier.verticalScrollbar(scroll).verticalScroll(scroll).padding(end = 10.dp)) {
                if (result == null) {
                    Text(
                        "This is not a usable TAKwave profile: ${parsed.exceptionOrNull()?.message}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    return@Column
                }
                val (profile, updated) = result
                if (profile.name.isNotEmpty()) {
                    Text("“${profile.name}”", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                }
                if (changes.isEmpty()) {
                    Text("Your settings already match this profile.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("These settings will change:", style = MaterialTheme.typography.bodyMedium)
                    for (c in changes) Text("• $c", style = MaterialTheme.typography.bodySmall)
                }
                if (profile.slotAssignments.isNotEmpty()) {
                    val own = settings.address
                    val note = when {
                        own == null -> "Set your callsign first to pick up your slot number from this profile."
                        profile.slotFor(own.toString()) == null -> "$own is not in this profile's slot list; your slot number stays as it is."
                        else -> null
                    }
                    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = WarnAmber, modifier = Modifier.padding(top = 6.dp)) }
                }
                if (updated.encryption != EncryptionMode.OFF && updated.encryption != settings.encryption) {
                    WarningCard(
                        "This profile turns on encryption (${updated.encryption.label}). Encryption is NOT permitted on " +
                            "amateur radio frequencies. The profile carries no keys: until you load them, TAKwave will not transmit.",
                    )
                }
                if (updated.callsignHidden && !settings.callsignHidden) {
                    WarningCard(
                        "This profile also encrypts your callsign, so your packets no longer identify your station. " +
                            "Identify it some other way as your license requires.",
                    )
                }
                if (changes.isNotEmpty()) {
                    Text(
                        "Changes take effect the next time the gateway starts.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (result != null && changes.isNotEmpty()) {
                TextButton(onClick = {
                    val profile = result.first
                    scope.launch {
                        // Applied to the settings as they are now, in case they changed while the dialog was open.
                        container.settingsStore.update { current -> runCatching { current.withTeamProfile(profile) }.getOrDefault(current) }
                    }
                    onDismiss()
                }) { Text("Apply") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (result != null && changes.isNotEmpty()) "Cancel" else "Close") } },
    )
}

/** Draws [text] as a QR code (error correction level M), or a note when it does not fit. */
@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) {
        runCatching {
            QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
        }.getOrNull()
    }
    if (matrix == null) {
        Text("Too long for a QR code; share it as a file instead.", style = MaterialTheme.typography.bodySmall, color = WarnAmber)
        return
    }
    Canvas(modifier.background(Color.White).semantics { contentDescription = "QR code of the team profile" }) {
        val cell = size.minDimension / matrix.width
        // Round up so neighboring modules overlap slightly instead of leaving hairline gaps.
        val module = Size(ceil(cell), ceil(cell))
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color.Black, topLeft = Offset(x * cell, y * cell), size = module)
            }
        }
    }
}

private fun clipboardText(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

/** Reads at most one byte more than a profile may hold, so an oversized file is refused by the parser, not loaded into memory. */
private fun readSmallText(context: Context, uri: Uri): String? = try {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buf = ByteArray(TeamProfile.MAX_LENGTH + 1)
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        String(buf, 0, n, Charsets.UTF_8)
    }
} catch (_: Exception) {
    null
}
