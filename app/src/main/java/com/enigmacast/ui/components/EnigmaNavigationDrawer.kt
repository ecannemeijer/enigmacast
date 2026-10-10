package com.enigmacast.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.enigmacast.ui.animation.LocalMotionSpec

private val DrawerShape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp)

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
    val width by animateDpAsState(
        targetValue = if (expanded) 260.dp else 76.dp,
        animationSpec = if (spec.animate) tween(spec.sidebarMs) else snap(),
        label = "drawerWidth",
    )
    Column(
        modifier
            .width(width)
            .fillMaxHeight()
            .clip(DrawerShape)
            .background(Brush.verticalGradient(listOf(Color(0xF0141E33), Color(0xF0080D1C))))
            .border(1.dp, Color(0x332488FF), DrawerShape)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}
