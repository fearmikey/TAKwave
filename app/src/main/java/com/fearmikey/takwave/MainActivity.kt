// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fearmikey.takwave.core.profile.TeamProfile
import com.fearmikey.takwave.data.Settings
import com.fearmikey.takwave.ui.AboutScreen
import com.fearmikey.takwave.ui.CalibrateScreen
import com.fearmikey.takwave.ui.LogScreen
import com.fearmikey.takwave.ui.OnboardingScreen
import com.fearmikey.takwave.ui.ProfileImportDialog
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
        if (savedInstanceState == null) handleProfileLink(intent)
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

private val TABS = listOf("Status", "Settings", "Log", "Calibrate", "About")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TakwaveRoot(container: AppContainer) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val settings: Settings? by container.settingsStore.settings.collectAsStateWithLifecycle(initialValue = null)
    val status by container.status.collectAsStateWithLifecycle()
    val pendingProfile by container.pendingProfile.collectAsStateWithLifecycle()

    // A profile from a link, file or the clipboard is reviewed here, over whatever screen is showing.
    val forReview = pendingProfile
    settings?.let { s ->
        if (forReview != null) ProfileImportDialog(container, s, forReview) { container.pendingProfile.value = null }
    }

    // First run (or "Run the setup guide again" in Settings): the guide replaces the tabs.
    val current = settings
    if (current != null && !current.onboardingDone) {
        OnboardingScreen(container, current)
        return
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("TAKwave") })
                // Scrollable: five labels do not fit a fixed row on narrow phones.
                PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                    TABS.forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                    }
                }
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
            when (tab) {
                0 -> StatusScreen(container, s, status)
                1 -> SettingsScreen(container, s, status)
                2 -> LogScreen(container)
                3 -> CalibrateScreen(container, status)
                else -> AboutScreen()
            }
        }
    }
}
