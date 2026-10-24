package com.enigmacast.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Line icons for the main navigation. They share one 24dp grid, 1.8dp stroke and
 * rounded caps, so the rail reads as one set instead of a mix of Material styles.
 */
enum class NavIconMotion { NONE, FLOAT, WOBBLE, SPIN, BOUNCE, PULSE }

object NavIcons {
    val Home: ImageVector by lazy {
        lineIcon(
            "NavHome",
            "M3.5,10.5 L12,3.5 L20.5,10.5",
            "M5.5,9.5 V20 H18.5 V9.5",
            "M10,20 V14 H14 V20",
        )
    }

    /** The EnigmaCast TV / Enigma receiver: a screen with rabbit-ear antennae. */
    val Enigma: ImageVector by lazy {
        lineIcon(
            "NavEnigma",
            "M4,7.5 H20 V18 H4 Z",
            "M8,7.5 L6,3.5",
            "M16,7.5 L18,3.5",
            "M9,21 H15",
            "M12,18 V21",
        )
    }

    /** IPTV: a play button in a cloud, clearly different from the receiver. */
    val Iptv: ImageVector by lazy {
        lineIcon(
            "NavIptv",
            listOf(
                "M7,18 H17 A4,4 0 0,0 17,10 A6,6 0 0,0 6,9 A4.5,4.5 0 0,0 7,18 Z",
            ),
            listOf("M9.5,10.5 L14,13 L9.5,15.5 Z"),
        )
    }

    val Sport: ImageVector by lazy {
        lineIcon(
            "NavSport",
            "M12,3 a9,9 0 1,0 0,18 a9,9 0 1,0 0,-18",
            "M12,8 L15.8,10.8 L14.4,15.2 L9.6,15.2 L8.2,10.8 Z",
            "M12,3 V8",
            "M20.6,9.8 L15.8,10.8",
            "M18.1,18.1 L14.4,15.2",
            "M5.9,18.1 L9.6,15.2",
            "M3.4,9.8 L8.2,10.8",
        )
    }

    val Search: ImageVector by lazy {
        lineIcon(
            "NavSearch",
            "M10.5,4 a6.5,6.5 0 1,0 0,13 a6.5,6.5 0 1,0 0,-13",
            "M15.2,15.2 L21,21",
        )
    }

    /** My items: a bookmark, used for saved items and recordings. */
    val MyItems: ImageVector by lazy {
        lineIcon(
            "NavMyItems",
            "M6,3.5 H18 V21 L12,16.5 L6,21 Z",
        )
    }

    val Torrent: ImageVector by lazy {
        lineIcon(
            "NavTorrent",
            "M12,3.5 V15",
            "M7.5,10.5 L12,15 L16.5,10.5",
            "M4,17 V20.5 H20 V17",
        )
    }

    /** Settings as three sliders; softer and more readable than a detailed gear at 24dp. */
    val Settings: ImageVector by lazy {
        lineIcon(
            "NavSettings",
            "M4,7 H20",
            "M4,12 H20",
            "M4,17 H20",
            "M6,7 a2,2 0 1,0 4,0 a2,2 0 1,0 -4,0",
            "M13,12 a2,2 0 1,0 4,0 a2,2 0 1,0 -4,0",
            "M8,17 a2,2 0 1,0 4,0 a2,2 0 1,0 -4,0",
        )
    }

    private fun lineIcon(name: String, vararg paths: String): ImageVector =
        lineIcon(name, paths.toList(), emptyList())

    private fun lineIcon(name: String, paths: List<String>, filledPaths: List<String>): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        paths.forEach { path ->
            builder.addPath(
                pathData = addPathNodes(path),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        filledPaths.forEach { path ->
            builder.addPath(
                pathData = addPathNodes(path),
                fill = SolidColor(Color.Black),
            )
        }
        return builder.build()
    }
}
