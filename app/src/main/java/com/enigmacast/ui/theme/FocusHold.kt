package com.enigmacast.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Keeps the focus on a page while its content is replaced (skeleton → hero, rows reloaded).
 * When the focused node disappears, Compose drops the focus and the first menu item (Home)
 * takes it. Once the page has had the focus, a change of [contentKey] puts it back on
 * [entry] (the hero's Watch button). Focus that leaves the page at any other moment
 * (Left to the menu, Back) was the user's choice and is left alone.
 */
@Composable
fun Modifier.holdPageFocus(entry: FocusRequester, contentKey: Any?): Modifier {
    var held by remember { mutableStateOf(false) }
    var inside by remember { mutableStateOf(false) }
    val box = remember { object { var key: Any? = Unit; var changedAt = 0L } }
    if (box.key != contentKey) { box.key = contentKey; box.changedAt = System.currentTimeMillis() }
    LaunchedEffect(contentKey) {
        // Only when the change took the focus away; a user browsing the page keeps their place.
        androidx.compose.runtime.withFrameNanos { }
        if (held && !inside) entry.requestWhenReady()
    }
    return this.onFocusChanged {
        inside = it.hasFocus
        if (it.hasFocus) held = true
        else if (System.currentTimeMillis() - box.changedAt > SETTLE_MS) held = false
    }
}

private const val SETTLE_MS = 600L
