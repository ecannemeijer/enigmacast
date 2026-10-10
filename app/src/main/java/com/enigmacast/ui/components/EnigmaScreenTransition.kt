package com.enigmacast.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.enigmacast.ui.animation.LocalMotionSpec

/**
 * Fades between destinations without composing both at once, so a [androidx.compose.ui.focus.FocusRequester]
 * stays attached to a single node.
 */
@Composable
fun <T> EnigmaScreenTransition(
    targetState: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val spec = LocalMotionSpec.current
    var displayed by remember { mutableStateOf(targetState) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(targetState) {
        if (displayed == targetState) return@LaunchedEffect
        if (!spec.animate || spec.screenMs == 0) {
            displayed = targetState
            alpha.snapTo(1f)
            return@LaunchedEffect
        }
        val half = (spec.screenMs / 2).coerceAtLeast(1)
        alpha.animateTo(0f, tween(half))
        displayed = targetState
        alpha.animateTo(1f, tween(half))
    }
    Box(modifier.graphicsLayer { this.alpha = alpha.value }) {
        content(displayed)
    }
}
