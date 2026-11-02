package com.enigmacast.ui.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.enigmacast.domain.model.MotionQuality

/**
 * Reads the system animator scale once and provides [LocalMotionSpec] for the whole window.
 * [fast]: Fast mode, no motion at all (also for scrolling, through [FastMotion]).
 */
@Composable
fun ProvideEnigmaMotion(quality: MotionQuality, fast: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scale = remember {
        FastMotion.readSystem(context)
        FastMotion.systemScale
    }
    SideEffect { FastMotion.fast = fast }
    val spec = remember(quality, scale, fast) { EnigmaMotion.spec(quality, scale, fast) }
    CompositionLocalProvider(LocalMotionSpec provides spec, content = content)
}
