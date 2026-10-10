package com.enigmacast.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.enigmacast.ui.animation.LocalMotionSpec

/** Slides the channel banner up from the bottom. Visibility stays owned by the existing timeout. */
@Composable
fun EnigmaAnimatedOsd(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val spec = LocalMotionSpec.current
    val enter = if (spec.animate && spec.osdMs > 0) {
        fadeIn(tween(spec.osdMs)) + slideInVertically(tween(spec.osdMs)) { it / 3 }
    } else {
        fadeIn(snap()) + slideInVertically(snap()) { 0 }
    }
    val exit = if (spec.animate && spec.osdMs > 0) {
        fadeOut(tween(spec.osdMs)) + slideOutVertically(tween(spec.osdMs)) { it / 3 }
    } else {
        fadeOut(snap()) + slideOutVertically(snap()) { 0 }
    }
    AnimatedVisibility(visible = visible, modifier = modifier, enter = enter, exit = exit) {
        content()
    }
}
