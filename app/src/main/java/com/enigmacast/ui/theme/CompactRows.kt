package com.enigmacast.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import com.enigmacast.ui.animation.LocalMotionSpec
import com.enigmacast.ui.animation.focusTween

private val CompactShape get() = skinShape(6.dp)

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
    drawFocus: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
    onPlaced: (LayoutCoordinates) -> Unit = {},
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // The menu's focus rail moves on placement. Focus that returns without a new layout
    // (back from Live TV) placed nothing, so the rail stayed hidden and no highlight showed.
    var lastCoords by remember { androidx.compose.runtime.mutableStateOf<LayoutCoordinates?>(null) }
    androidx.compose.runtime.LaunchedEffect(focused) {
        if (focused) lastCoords?.takeIf { it.isAttached }?.let(onPlaced)
    }
    val spec = LocalMotionSpec.current
    val background by animateColorAsState(
        targetValue = when {
            focused && drawFocus -> EnigmaColors.Focus
            selected -> EnigmaColors.Selected
            else -> Color.Transparent
        },
        animationSpec = if (spec.animate) tween(spec.focusMs, easing = FastOutSlowInEasing) else snap(),
        label = "rowBackground",
    )
    val grow by animateFloatAsState(
        targetValue = if (focused && drawFocus && spec.animate) 1.02f else 1f,
        animationSpec = spec.focusTween(),
        label = "rowGrow",
    )
    val contentColor = when {
        focused -> EnigmaColors.OnFocus
        selected -> EnigmaColors.Accent
        else -> Color.White
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = grow
                scaleY = grow
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .onGloballyPositioned { lastCoords = it; if (focused) onPlaced(it) }
            .background(background, CompactShape)
            .then(
                ActiveSkin.current.palette.focusBrush?.takeIf { focused && drawFocus }
                    ?.let { Modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(it), CompactShape) } ?: Modifier,
            )
            .skinFocusEdge(focused && drawFocus, 6.dp)
            .edgeLeft()
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
        // The Boolean overload says whether focus really moved; the plain requestFocus() only logs
        // "FocusRequester is not initialized" for a target that is not attached yet and returns.
        if (runCatching { requestFocus(androidx.compose.ui.focus.FocusDirection.Enter) }.getOrDefault(false)) return true
    }
    return false
}

/**
 * Up on the first row and Down on the last row jump to the other end.
 * A list of one row stays put. The caller scrolls the target on screen and focuses it.
 * Holding the key repeats it; wrapping on a repeat would loop the list, so the end stays put.
 */
fun Modifier.wrapListEnds(
    index: Int,
    lastIndex: Int,
    onWrapToFirst: () -> Unit,
    onWrapToLast: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown || lastIndex <= 0) return@onPreviewKeyEvent false
    val atFirst = event.key == Key.DirectionUp && index == 0
    val atLast = event.key == Key.DirectionDown && index == lastIndex
    if (!atFirst && !atLast) return@onPreviewKeyEvent false
    if (event.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
    if (atFirst) onWrapToLast() else onWrapToFirst()
    true
}

/**
 * Left from the left edge of a column. Full-width rows and the first control of a row
 * read this so D-pad left returns to the menu item that opened the column, not to a
 * nearer row in the column on the left.
 */
val LocalEdgeLeft = staticCompositionLocalOf<FocusRequester?> { null }

/** Sends D-pad left to [LocalEdgeLeft] when that column set one. */
fun Modifier.edgeLeft(): Modifier = composed {
    val edge = LocalEdgeLeft.current ?: return@composed this
    focusProperties { left = edge }
}

/** Scrolls [index] into view, then focuses this requester once the row is attached. */
suspend fun FocusRequester.focusListRow(listState: LazyListState, index: Int) {
    listState.scrollToItem(index)
    requestWhenReady()
}

/**
 * Page boundary: Up/Down that would leave this group (top or bottom edge) stay put, so focus
 * never jumps to the drawer or a header. Left/Right still leave normally (the menu refuses Left; Back returns there).
 */
fun Modifier.trapVertical(): Modifier = this
    .focusProperties {
        onExit = {
            if (com.enigmacast.ui.theme.FocusEdges.blocks(requestedFocusDirection)) cancelFocusChange()
        }
    }
    .focusGroup()

object FocusEdges {
    fun blocks(direction: androidx.compose.ui.focus.FocusDirection): Boolean =
        direction == androidx.compose.ui.focus.FocusDirection.Up || direction == androidx.compose.ui.focus.FocusDirection.Down
}
