// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave

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
import androidx.compose.material3.PrimaryTabRow
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fearmikey.takwave.data.Settings
import com.fearmikey.takwave.ui.CalibrateScreen
import com.fearmikey.takwave.ui.LogScreen
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
        setContent {
            TakwaveTheme {
                TakwaveRoot(container)
            }
        }
    }
}

private val TABS = listOf("Status", "Settings", "Log", "Calibrate")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TakwaveRoot(container: AppContainer) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val settings: Settings? by container.settingsStore.settings.collectAsStateWithLifecycle(initialValue = null)
    val status by container.status.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("TAKwave") })
                PrimaryTabRow(selectedTabIndex = tab) {
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
                else -> CalibrateScreen(container, status)
            }
        }
    }
}
