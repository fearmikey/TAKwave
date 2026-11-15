// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val DEVELOPER = "fearmikey"
private const val DEVELOPER_URL = "https://github.com/fearmikey"
private const val REPO_URL = "https://github.com/fearmikey/TAKwave"
private const val ISSUES_URL = "https://github.com/fearmikey/TAKwave/issues"
private const val COFFEE_URL = "https://buymeacoffee.com/XimW7nXI1j"

/**
 * Who made the app and where to find the source, report bugs or support development. Links only
 * open when the operator taps them, in the system browser; the app itself opens no connection.
 */
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val version = remember { appVersion(context) }

    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScrollbar(scroll)
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Section("TAKwave") {
            Text("Version $version", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Carries TAK position reports, chat, markers and emergency alerts over analog VHF/UHF radios. " +
                    "Free and open source under the GNU GPL v3 or later: no ads, no tracking, no accounts.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Section("Developer") {
            Text(DEVELOPER, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { openLink(context, DEVELOPER_URL) }) { Text("GitHub profile") }
        }

        Section("Source code") {
            Text(
                "The complete source, documentation and protocol specification are on GitHub.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { openLink(context, REPO_URL) }) { Text("Open GitHub repository") }
        }

        Section("Report a bug") {
            Text(
                "Open an issue on GitHub. Please include the app version below, your radio interface, and an " +
                    "exported log (Log tab, Export) if you can. Check the log for anything private before you post it.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "TAKwave $version, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { openLink(context, ISSUES_URL) }) { Text("Report a bug") }
        }

        Section("Support development") {
            Text(
                "TAKwave is free and always will be. If it is useful to you, you can buy the developer a coffee.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { openLink(context, COFFEE_URL) }) { Text("Buy me a coffee") }
        }

        Text(
            "Links open in your browser and need an internet connection. TAKwave itself works fully offline.",
            style = MaterialTheme.typography.labelSmall,
            color = IdleGrey,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

private fun appVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
} catch (e: Exception) {
    "?"
}

private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No browser installed. The address is $url", Toast.LENGTH_LONG).show()
    }
}
