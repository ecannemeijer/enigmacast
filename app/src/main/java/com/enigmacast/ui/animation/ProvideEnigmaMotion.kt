package com.enigmacast.ui.animation

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.enigmacast.domain.model.MotionQuality

/** Reads the system animator scale once and provides [LocalMotionSpec] for the whole window. */
@Composable
fun ProvideEnigmaMotion(quality: MotionQuality, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scale = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
    }
    CompositionLocalProvider(LocalMotionSpec provides EnigmaMotion.spec(quality, scale), content = content)
}
