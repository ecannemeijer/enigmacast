package com.enigmacast.ui.components

import androidx.compose.ui.unit.dp

/** Widths of the live TV side panels (Enigma2 and IPTV): bouquets/categories, channels, channels beside the programme panel. */
object LivePanelWidths {
    /** Enigma2 bouquets (was 210 dp). */
    val CATEGORIES = 190.dp
    /** IPTV categories (was 240 dp). */
    val IPTV_CATEGORIES = 216.dp
    /** Channel list alone or beside the categories (was 600 dp). */
    val CHANNELS = 510.dp
    /** Channel list beside the programme panel (was 460 dp). */
    val CHANNELS_WITH_GUIDE = 390.dp
}
