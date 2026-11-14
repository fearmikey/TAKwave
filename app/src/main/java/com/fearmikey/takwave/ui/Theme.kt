// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ui

import androidx.compose.foundation.background
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

/** A titled card used for every group of controls. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Box(Modifier.height(8.dp))
            content()
        }
    }
}

/** Card with an attention colour, for warnings the operator must not miss. */
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
 * Audio input meter. The bar shows the peak level; its colour tells the operator whether the
 * receive volume is too low (grey), usable (green) or clipping (red).
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
