package com.enigmacast.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few Material symbols the dashboard needs that are not in material-icons-core.
 * Paths are the 24dp Material Design originals; pulling in material-icons-extended
 * for five icons would add several megabytes to the APK.
 */
object EnigmaIcons {
    internal const val POWER_PATH =
        "M13,3h-2v10h2L13,3zM17.83,5.17l-1.42,1.42C17.99,7.86 19,9.81 19,12c0,3.87 -3.13,7 -7,7s-7,-3.13 -7,-7" +
            "c0,-2.19 1.01,-4.14 2.58,-5.42L6.17,5.17C4.23,6.82 3,9.26 3,12c0,4.97 4.03,9 9,9s9,-4.03 9,-9" +
            "c0,-2.74 -1.23,-5.18 -3.17,-6.83z"
    internal const val BEDTIME_PATH =
        "M12.34,2.02C6.59,1.82 2,6.42 2,12c0,5.52 4.48,10 10,10c3.71,0 6.93,-2.02 8.66,-5.02" +
            "C13.15,16.73 8.57,8.55 12.34,2.02z"
    internal const val RESTART_PATH =
        "M12,5V2L8,6l4,4V7c3.31,0 6,2.69 6,6c0,2.97 -2.17,5.43 -5,5.91v2.02c3.95,-0.49 7,-3.85 7,-7.93" +
            "C20,8.58 16.42,5 12,5zM6,13c0,-1.65 0.67,-3.15 1.76,-4.24L6.34,7.34C4.9,8.79 4,10.79 4,13" +
            "c0,4.08 3.05,7.44 7,7.93v-2.02C8.17,18.43 6,15.97 6,13z"
    internal const val TIMER_PATH =
        "M9,1h6v2H9zM19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42C16.07,4.74 14.12,4 12,4" +
            "c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9c0,-2.12 -0.74,-4.07 -1.97,-5.61zM12,20" +
            "c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7zM13,8h-2v6h2z"
    internal const val TV_PATH =
        "M21,3L3,3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5" +
            "c0,-1.1 -0.9,-2 -2,-2zM21,17L3,17L3,5h18v12z"
    internal const val PALETTE_PATH =
        "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9c0.83,0 1.5,-0.67 1.5,-1.5c0,-0.39 -0.15,-0.74 -0.39,-1.01" +
            "c-0.23,-0.26 -0.38,-0.61 -0.38,-0.99c0,-0.83 0.67,-1.5 1.5,-1.5L16,16c2.76,0 5,-2.24 5,-5" +
            "c0,-4.42 -4.03,-8 -9,-8zM6.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S5.67,9 6.5,9 8,9.67 8,10.5 7.33,12 6.5,12z" +
            "M9.5,8C8.67,8 8,7.33 8,6.5S8.67,5 9.5,5s1.5,0.67 1.5,1.5S10.33,8 9.5,8zM14.5,8c-0.83,0 -1.5,-0.67 -1.5,-1.5" +
            "S13.67,5 14.5,5s1.5,0.67 1.5,1.5S15.33,8 14.5,8zM17.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S16.67,9 17.5,9" +
            "s1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z"
    internal const val LAYERS_PATH =
        "M11.99,18.54l-7.37,-5.73L3,14.07l9,7 9,-7 -1.63,-1.27 -7.38,5.74zM12,16l7.36,-5.73L21,9l-9,-7 -9,7 1.63,1.27L12,16z"

    val Power: ImageVector by lazy { icon("Power", POWER_PATH) }
    val Bedtime: ImageVector by lazy { icon("Bedtime", BEDTIME_PATH) }
    val Restart: ImageVector by lazy { icon("Restart", RESTART_PATH) }
    val Timer: ImageVector by lazy { icon("Timer", TIMER_PATH) }
    val Tv: ImageVector by lazy { icon("Tv", TV_PATH) }
    val Layers: ImageVector by lazy { icon("Layers", LAYERS_PATH) }
    val Palette: ImageVector by lazy { icon("Palette", PALETTE_PATH) }

    private fun icon(name: String, path: String): ImageVector {
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(path),
            fill = SolidColor(Color.Black),
        ).build()
    }
}
