package com.enigmacast.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor

private val CompactShape = RoundedCornerShape(6.dp)

/**
 * A dense, D-pad focusable list row for long lists (channels, bouquets). TV buttons have
 * a 40 dp minimum height; this one is as tall as its content so more rows fit on screen.
 * Uses the same focus colours as the other TV components; OK/Enter clicks on release.
 */
@Composable
fun CompactListRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val background = when {
        focused -> EnigmaColors.Focus
        selected -> EnigmaColors.Selected
        else -> Color.Transparent
    }
    val contentColor = when {
        focused -> EnigmaColors.OnFocus
        selected -> EnigmaColors.Accent
        else -> Color.White
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background, CompactShape)
            .onKeyEvent { event ->
                val confirm = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (confirm && event.type == KeyEventType.KeyUp) onClick()
                confirm
            }
            .focusable(interactionSource = interaction)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content(focused)
        }
    }
}

/**
 * Requests focus once the target is laid out. A lazy list item that was just scrolled to
 * may attach a frame or two later; a single `requestFocus()` then throws or does nothing
 * and focus stays wherever it was (for example a menu item).
 */
suspend fun FocusRequester.requestWhenReady(attempts: Int = 10): Boolean {
    repeat(attempts) {
        withFrameNanos { }
        if (runCatching { requestFocus() }.isSuccess) return true
    }
    return false
}

/**
 * Up on the first row and Down on the last row jump to the other end.
 * A list of one row stays put. The caller scrolls the target on screen and focuses it.
 */
fun Modifier.wrapListEnds(
    index: Int,
    lastIndex: Int,
    onWrapToFirst: () -> Unit,
    onWrapToLast: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown || lastIndex <= 0) return@onPreviewKeyEvent false
    when (event.key) {
        Key.DirectionUp -> if (index == 0) {
            onWrapToLast()
            true
        } else {
            false
        }
        Key.DirectionDown -> if (index == lastIndex) {
            onWrapToFirst()
            true
        } else {
            false
        }
        else -> false
    }
}

/** Scrolls [index] into view, then focuses this requester once the row is attached. */
suspend fun FocusRequester.focusListRow(listState: LazyListState, index: Int) {
    listState.scrollToItem(index)
    requestWhenReady()
}
