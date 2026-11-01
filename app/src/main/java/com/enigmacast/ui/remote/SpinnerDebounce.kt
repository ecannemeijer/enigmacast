package com.enigmacast.ui.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * The live banner spinner without flicker: it shows only after [showAfterMs] of continuous
 * loading, and once shown it stays until playback has been stable for [hideAfterMs]
 * (so it is also visible at least that long).
 */
class SpinnerDebounce(private val showAfterMs: Long = 1_000, private val hideAfterMs: Long = 1_000) {
    var visible = false
        private set
    private var since = -1L

    fun update(loading: Boolean, nowMs: Long): Boolean {
        // Counting towards the opposite of what is shown; anything else restarts the count.
        if (loading == visible) { since = -1L; return visible }
        if (since < 0) since = nowMs
        if (nowMs - since >= if (visible) hideAfterMs else showAfterMs) {
            visible = loading
            since = -1L
        }
        return visible
    }
}

@Composable
internal fun rememberDebouncedLoading(loading: Boolean): Boolean {
    val debounce = remember { SpinnerDebounce() }
    var shown by remember { mutableStateOf(false) }
    val raw by rememberUpdatedState(loading)
    LaunchedEffect(Unit) {
        while (true) {
            shown = debounce.update(raw, System.currentTimeMillis())
            delay(100)
        }
    }
    return shown
}
