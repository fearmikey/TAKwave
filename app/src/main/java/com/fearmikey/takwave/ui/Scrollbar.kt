// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

/*
 * Compose draws no scrollbar on its own. These modifiers draw a thin thumb along the right edge
 * whenever the content is taller than the viewport, so the operator can see there is more below
 * and roughly where they are. The thumb is always shown (dim at rest, brighter while scrolling)
 * rather than fading out, because a hidden scrollbar tells a glance nothing.
 *
 * Place the modifier *before* verticalScroll / on the LazyColumn itself so it draws in viewport
 * coordinates:  Modifier.verticalScrollbar(state).verticalScroll(state)
 *
 * State is only read in the draw phase, so scrolling never causes recomposition.
 */

private val ThumbColor = Color(0xFFB0BEC5)
private val ThumbWidth = 4.dp
private val ThumbMinLength = 32.dp
private val EdgeInset = 2.dp
private const val ALPHA_IDLE = 0.4f
private const val ALPHA_ACTIVE = 0.85f

/** Scrollbar for a [androidx.compose.foundation.verticalScroll] container. */
fun Modifier.verticalScrollbar(state: ScrollState): Modifier = drawWithContent {
    drawContent()
    val max = state.maxValue
    // Int.MAX_VALUE until the first layout pass.
    if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
    val viewport = size.height
    drawThumb(
        length = viewport * viewport / (viewport + max),
        fraction = state.value.toFloat() / max,
        active = state.isScrollInProgress,
    )
}

/**
 * Scrollbar for a LazyColumn. Item heights are unknown until laid out, so the thumb size and
 * position are estimated from the average height of the visible items.
 */
fun Modifier.verticalScrollbar(state: LazyListState): Modifier = drawWithContent {
    drawContent()
    val canBack = state.canScrollBackward
    val canForward = state.canScrollForward
    if (!canBack && !canForward) return@drawWithContent
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return@drawWithContent

    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    val avgItem = visible.sumOf { it.size }.toFloat() / visible.size + info.mainAxisItemSpacing
    val total = avgItem * info.totalItemsCount + info.beforeContentPadding + info.afterContentPadding
    if (total <= viewport) return@drawWithContent

    val first = visible.first()
    val scrolled = first.index * avgItem - first.offset
    val fraction = when {
        !canBack -> 0f
        !canForward -> 1f
        else -> scrolled / (total - viewport)
    }
    drawThumb(length = viewport * viewport / total, fraction = fraction, active = state.isScrollInProgress)
}

private fun DrawScope.drawThumb(length: Float, fraction: Float, active: Boolean) {
    val width = ThumbWidth.toPx()
    val len = length.coerceIn(minOf(ThumbMinLength.toPx(), size.height), size.height)
    val top = (size.height - len) * fraction.coerceIn(0f, 1f)
    drawRoundRect(
        color = ThumbColor,
        topLeft = Offset(size.width - width - EdgeInset.toPx(), top),
        size = Size(width, len),
        cornerRadius = CornerRadius(width / 2),
        alpha = if (active) ALPHA_ACTIVE else ALPHA_IDLE,
    )
}
