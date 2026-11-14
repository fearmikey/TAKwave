// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fearmikey.takwave.AppContainer
import com.fearmikey.takwave.engine.LogEntry
import com.fearmikey.takwave.engine.LogKind
import com.fearmikey.takwave.net.Sharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by container.log.entries.collectAsStateWithLifecycle()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.ROOT) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("TAKwave log", container.log.asText()))
            }) { Text("Copy") }
            OutlinedButton(onClick = {
                val text = container.log.asText()
                scope.launch(Dispatchers.IO) {
                    try {
                        Sharing.shareLog(context.applicationContext, text)
                    } catch (e: Exception) {
                        container.log.add(LogKind.ERROR, "Log export failed: ${e.message}")
                    }
                }
            }) { Text("Export") }
            OutlinedButton(onClick = { container.log.clear() }) { Text("Clear") }
        }
        if (entries.isEmpty()) {
            Text(
                "No packets yet. Received and transmitted packets appear here, newest first.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) {
            items(entries.asReversed(), key = { it.id }) { entry ->
                LogRow(entry, timeFormat.format(Date(entry.timeMs)))
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, time: String) {
    val color = when (entry.kind) {
        LogKind.RX -> RxGreen
        LogKind.TX -> TxRed
        LogKind.INFO -> IdleGrey
        LogKind.ERROR -> WarnAmber
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(time, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text(entry.kind.name, color = color, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            if (entry.hex != null) Text("${entry.size} bytes", fontSize = 12.sp, color = Color.Gray)
        }
        Text(entry.summary, style = MaterialTheme.typography.bodyMedium)
        entry.hex?.let {
            Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp, color = Color.Gray)
        }
    }
}
