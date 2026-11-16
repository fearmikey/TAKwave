// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val RxGreen = Color(0xFF66BB6A)
val TxRed = Color(0xFFEF5350)
val IdleGrey = Color(0xFF78909C)
val WarnAmber = Color(0xFFFFB74D)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DD0E1),
    onPrimary = Color(0xFF00363D),
    secondary = Color(0xFFFFB74D),
    onSecondary = Color(0xFF442B00),
    background = Color(0xFF101417),
    surface = Color(0xFF101417),
    surfaceVariant = Color(0xFF263238),
    error = Color(0xFFFFB4AB),
)

/** Dark only: the app is meant to be glanceable in the field at night next to ATAK. */
@Composable
fun TakwaveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}

/**
 * A small "i" in a circle. Tapping it opens a dialog that explains [title] in plain language.
 * Drawn by hand because the app carries no icon font (see docs/DECISIONS.md).
 */
@Composable
fun InfoButton(title: String, text: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button) { open = true }
            .semantics { contentDescription = "More information: $title" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(22.dp).border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "i",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Got it") } },
            title = { Text(title) },
            text = {
                val scroll = rememberScrollState()
                Text(
                    text,
                    Modifier.verticalScrollbar(scroll).verticalScroll(scroll).padding(end = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
        )
    }
}

/** A titled card used for every group of controls. [info] adds an [InfoButton] next to the title. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, info: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (info != null) InfoButton(title, info)
            }
            Box(Modifier.height(if (info != null) 0.dp else 8.dp))
            content()
        }
    }
}

/** Card with an attention color, for warnings the operator must not miss. */
@Composable
fun WarningCard(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Audio input meter. The bar shows the peak level; its color tells the operator whether the
 * receive volume is too low (gray), usable (green) or clipping (red).
 */
@Composable
fun LevelMeter(rms: Float, peak: Float, modifier: Modifier = Modifier) {
    val color = when {
        peak >= 0.9f -> TxRed
        peak >= 0.05f -> RxGreen
        else -> IdleGrey
    }
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.background),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(peak.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(color),
            )
        }
        Text(
            "peak %d%%   rms %d%%".format((peak * 100).toInt(), (rms * 100).toInt()),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
