package com.enigmacast.ui.components

import com.enigmacast.ui.theme.skinSurface
import com.enigmacast.ui.theme.skinShape
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.enigmacast.ui.animation.LocalMotionSpec
import com.enigmacast.ui.animation.travelTween
import com.enigmacast.ui.theme.EnigmaColors
import kotlin.math.roundToInt

private val DrawerShape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp)
private val CursorShape get() = skinShape(8.dp)

/**
 * One sliding highlight for the side menu. A focused row writes its place;
 * the drawer animates the pill there.
 */
class NavCursorState {
    var originY by mutableFloatStateOf(0f)
    var topPx by mutableFloatStateOf(0f)
    var heightPx by mutableFloatStateOf(0f)
    var visible by mutableStateOf(false)
    var seen by mutableStateOf(false)
    var owner: Any? = null
}

val LocalNavCursor = staticCompositionLocalOf<NavCursorState?> { null }

/**
 * Collapses to icons and expands when [expanded] is true. The content is laid out
 * at the expanded width and clipped, so labels can fade without relaying the rows.
 */
@Composable
fun EnigmaNavigationDrawer(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spec = LocalMotionSpec.current
    val cursor = remember { NavCursorState() }
    val width by animateDpAsState(
        targetValue = if (expanded) 200.dp else 72.dp,
        animationSpec = if (spec.animate) tween(spec.sidebarMs) else snap(),
        label = "drawerWidth",
    )
    val travel = if (cursor.seen) spec.travelTween() else snap()
    val top by animateFloatAsState(cursor.topPx, travel, label = "navCursorTop")
    val pillHeight by animateFloatAsState(cursor.heightPx, travel, label = "navCursorHeight")
    val density = LocalDensity.current
    Box(
        modifier
            .width(width)
            .fillMaxHeight()
            .clip(DrawerShape)
            // Flat skins: the gradient they always had. Glass skins: frosted glass over the photo.
            .then(if (com.enigmacast.ui.theme.ActiveSkin.current.glass == null)
                Modifier.background(Brush.verticalGradient(listOf(Color(0xF0141E33), Color(0xF0080D1C))))
                    .border(1.dp, Color(0x332488FF), DrawerShape)
            else Modifier.skinSurface(com.enigmacast.ui.theme.EnigmaColors.Card, DrawerShape))
            .onGloballyPositioned { cursor.originY = it.positionInRoot().y },
    ) {
        if (cursor.visible && pillHeight > 0f) {
            Box(
                Modifier
                    .offset { IntOffset(0, top.roundToInt()) }
                    .padding(horizontal = 8.dp)
                    .fillMaxWidth()
                    .height(with(density) { pillHeight.toDp() })
                    .background(EnigmaColors.Focus, CursorShape),
            )
        }
        CompositionLocalProvider(LocalNavCursor provides cursor) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .drawerEdges()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content,
            )
        }
        SideEffect {
            if (cursor.visible) cursor.seen = true
        }
    }
}

/**
 * Up on the first menu item and Down on the last one stay in the menu. Without this, focus
 * search runs over the whole screen: with nothing above or below inside the column it picked
 * the nearest focusable of the page on the right. Done on the group, so it holds whichever
 * items are shown (Torrent, IPTV, the open flyout) without knowing which one is first or last.
 */
internal fun Modifier.drawerEdges(): Modifier = this
    .focusProperties {
        onExit = {
            if (NavEdges.blocks(requestedFocusDirection)) cancelFocusChange()
        }
        // Left at a page's left edge (geometric search or an explicit `left = menu`) no longer
        // opens the menu: only Back returns there (requestFocus, which is not a Left move).
        onEnter = {
            if (NavEdges.blocksEnter(requestedFocusDirection)) cancelFocusChange()
        }
    }
    .focusGroup()

internal object NavEdges {
    /** Only vertical moves are kept inside; Right still enters the page, Left has nothing to reach. */
    fun blocks(direction: FocusDirection): Boolean = direction == FocusDirection.Up || direction == FocusDirection.Down

    /** D-pad Left from the page never enters the menu; Back (and Right from it) decide. */
    fun blocksEnter(direction: FocusDirection): Boolean = direction == FocusDirection.Left
}
