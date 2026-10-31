package com.enigmacast.ui.person

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * App-level destinations for the actor page's "where to watch": info pages open on top of any
 * screen (Home, IPTV, Torrent), so the target screen comes from the app root.
 */
data class TitleNavigation(
    val onXtream: () -> Unit,
    val onTorrent: () -> Unit,
    val onPlayed: () -> Unit,
)

val LocalTitleNavigation = staticCompositionLocalOf<TitleNavigation?> { null }
