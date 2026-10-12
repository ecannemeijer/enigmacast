package com.enigmacast.ui.home

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.enigmacast.ui.animation.LocalMotionSpec

/** Where each section sits and which one has focus. */
class PivotState {
    internal val tops = mutableStateMapOf<Int, Int>()
    internal var focused by mutableIntStateOf(0)

    fun section(index: Int): Modifier = Modifier
        .onGloballyPositioned { tops[index] = it.positionInParent().y.toInt() }
        .onFocusChanged { if (it.hasFocus) focused = index }

    /** Section 0 (the hero) shows from the top; any other focused section is moved up to the pivot line. */
    fun targetOffset(pivotPx: Int, contentHeight: Int, viewport: Int): Int {
        if (focused == 0) return 0
        val top = tops[focused] ?: return 0
        return (top - pivotPx).coerceIn(0, (contentHeight - viewport).coerceAtLeast(0))
    }
}

/**
 * A TV-style column: no scroll container, so focusing a button never auto-scrolls the page.
 * The column moves by an animated offset to the focused section instead, which keeps the hero
 * whole while its buttons have focus and puts a row at a fixed line when it gets focus.
 */
@Composable
fun PivotColumn(state: PivotState, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    var viewport by androidx.compose.runtime.remember { mutableIntStateOf(0) }
    var contentHeight by androidx.compose.runtime.remember { mutableIntStateOf(0) }
    val pivotPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val spec = LocalMotionSpec.current
    val offset by animateIntAsState(
        state.targetOffset(pivotPx, contentHeight, viewport),
        animationSpec = tween(if (spec.animate) 280 else 0),
        label = "pivot",
    )
    Box(modifier.clipToBounds().onSizeChanged { viewport = it.height }) {
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .onSizeChanged { contentHeight = it.height }
                .offset { IntOffset(0, -offset) },
            verticalArrangement = Arrangement.spacedBy(18.dp),
            content = content,
        )
    }
}
