package com.enigmacast.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The previous navigation artwork, kept intact so the rail can be reverted without
 * reconstructing anything. Only the main rail uses the new set; the classic icons
 * can be enabled with [NavIconStyle.CLASSIC].
 */
object ClassicNavIcons {
    val Home: ImageVector = Icons.Filled.Home
    val Enigma: ImageVector = EnigmaIcons.Tv
    val Iptv: ImageVector = EnigmaIcons.Tv
    val Sport: ImageVector = EnigmaIcons.Soccer
    val Search: ImageVector = Icons.Filled.Search
    val MyItems: ImageVector = EnigmaIcons.Record
    val Torrent: ImageVector = Icons.Filled.PlayArrow
    val Settings: ImageVector = Icons.Filled.Settings
}

/**
 * One switch for the whole left rail. Set CLASSIC to true and rebuild to return to
 * the old icons; the new animated set can be brought back the same way.
 */
object NavIconStyle {
    const val CLASSIC = false

    val Home: ImageVector get() = if (CLASSIC) ClassicNavIcons.Home else NavIcons.Home
    val Enigma: ImageVector get() = if (CLASSIC) ClassicNavIcons.Enigma else NavIcons.Enigma
    val Iptv: ImageVector get() = if (CLASSIC) ClassicNavIcons.Iptv else NavIcons.Iptv
    val Sport: ImageVector get() = if (CLASSIC) ClassicNavIcons.Sport else NavIcons.Sport
    val Search: ImageVector get() = if (CLASSIC) ClassicNavIcons.Search else NavIcons.Search
    val MyItems: ImageVector get() = if (CLASSIC) ClassicNavIcons.MyItems else NavIcons.MyItems
    val Torrent: ImageVector get() = if (CLASSIC) ClassicNavIcons.Torrent else NavIcons.Torrent
    val Settings: ImageVector get() = if (CLASSIC) ClassicNavIcons.Settings else NavIcons.Settings
}
