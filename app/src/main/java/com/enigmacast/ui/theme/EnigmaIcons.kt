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
    internal const val SOCCER_PATH =
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,5.3l1.35,-0.95c1.82,0.56 3.37,1.76 4.38,3.34" +
            "l-0.39,1.34 -1.35,0.46L13,6.7L13,5.3zM9.65,4.35L11,5.3v1.4L7.01,9.49l-1.35,-0.46 -0.39,-1.34" +
            "c1.01,-1.57 2.56,-2.77 4.38,-3.34zM7.08,17.11l-1.14,0.1C4.73,15.81 4,13.99 4,12c0,-0.12 0.01,-0.23 0.02,-0.35" +
            "l1,-0.73 1.38,0.48 1.46,4.34 -0.78,1.37zM14.5,19.59c-0.79,0.26 -1.63,0.41 -2.5,0.41s-1.71,-0.15 -2.5,-0.41" +
            "l-0.69,-1.49 0.64,-1.1h5.11l0.64,1.11 -0.7,1.48zM14.27,15L9.73,15l-1.35,-4.02L12,8.44l3.63,2.54L14.27,15z" +
            "M18.06,17.21l-1.14,-0.1 -0.79,-1.37 1.46,-4.34 1.39,-0.47 1,0.73c0.01,0.11 0.02,0.22 0.02,0.34 0,1.99 -0.73,3.81 -1.94,5.21z"
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
    internal const val LANGUAGE_PATH =
        "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM18.92,8h-2.95" +
            "c-0.32,-1.25 -0.78,-2.45 -1.38,-3.56 1.84,0.63 3.37,1.91 4.33,3.56zM12,4.04c0.83,1.2 1.48,2.53 1.91,3.96" +
            "h-3.82c0.43,-1.43 1.08,-2.76 1.91,-3.96zM4.26,14C4.1,13.36 4,12.69 4,12s0.1,-1.36 0.26,-2h3.38" +
            "c-0.08,0.66 -0.14,1.32 -0.14,2 0,0.68 0.06,1.34 0.14,2L4.26,14zM5.08,16h2.95c0.32,1.25 0.78,2.45 1.38,3.56" +
            " -1.84,-0.63 -3.37,-1.9 -4.33,-3.56zM8.03,8L5.08,8c0.96,-1.66 2.49,-2.93 4.33,-3.56C8.81,5.55 8.35,6.75 8.03,8z" +
            "M12,19.96c-0.83,-1.2 -1.48,-2.53 -1.91,-3.96h3.82c-0.43,1.43 -1.08,2.76 -1.91,3.96zM14.34,14L9.66,14" +
            "c-0.09,-0.66 -0.16,-1.32 -0.16,-2 0,-0.68 0.07,-1.35 0.16,-2h4.68c0.09,0.65 0.16,1.32 0.16,2 0,0.68 -0.07,1.34 -0.16,2z" +
            "M14.59,19.56c0.6,-1.11 1.06,-2.31 1.38,-3.56h2.95c-0.96,1.65 -2.49,2.93 -4.33,3.56zM16.36,14" +
            "c0.08,-0.66 0.14,-1.32 0.14,-2 0,-0.68 -0.06,-1.34 -0.14,-2h3.38c0.16,0.64 0.26,1.31 0.26,2s-0.1,1.36 -0.26,2h-3.38z"
    internal const val SUBTITLES_PATH =
        "M20,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM4,12h4v2H4v-2zM14,18H4v-2h10v2zM20,18h-4v-2h4v2zM20,14H10v-2h10v2z"
    internal const val VOLUME_UP_PATH =
        "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"
    internal const val AUDIOTRACK_PATH =
        "M12,3v9.28c-0.47,-0.17 -0.97,-0.28 -1.5,-0.28C8.01,12 6,14.01 6,16.5S8.01,21 10.5,21c2.31,0 4.2,-1.75 4.45,-4H15V6h4V3h-7z"
    internal const val ASPECT_RATIO_PATH =
        "M19,12h-2v3h-3v2h5v-5zM7,9h3V7H5v5h2V9zM21,3H3c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM21,19.01H3V4.99h18v14.02z"
    internal const val SYNC_PATH =
        "M12,4V1L8,5l4,4V6c3.31,0 6,2.69 6,6 0,1.01 -0.25,1.97 -0.7,2.8l1.46,1.46C19.54,15.03 20,13.57 20,12c0,-4.42 -3.58,-8 -8,-8zM12,18c-3.31,0 -6,-2.69 -6,-6 0,-1.01 0.25,-1.97 0.7,-2.8L5.24,7.74C4.46,8.97 4,10.43 4,12c0,4.42 3.58,8 8,8v3l4,-4 -4,-4v3z"
    internal const val GUIDE_PATH =
        "M3,13h2v-2H3v2zM3,17h2v-2H3v2zM3,9h2V7H3v2zM7,13h14v-2H7v2zM7,17h14v-2H7v2zM7,7v2h14V7H7z"
    internal const val HISTORY_PATH =
        "M13,3c-4.97,0 -9,4.03 -9,9H1l3.89,3.89 0.07,0.14L9,12H6c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08V8H12z"
    internal const val RECORD_PATH =
        "M4,12a8,8 0,1 0,16 0a8,8 0,1 0,-16 0z"
    internal const val SPEED_PATH =
        "M20.38,8.57l-1.23,1.85a8,8 0,0 1,-0.22 7.58H5.07A8,8 0,0 1,15.58 6.85l1.85,-1.23A10,10 0,0 0,3.35 19a2,2 0,0 0,1.72 1h13.85a2,2 0,0 0,1.74 -1a10,10 0,0 0,-0.27 -10.44zM10.59,15.41a2,2 0,0 0,2.83 0l5.66,-8.49 -8.49,5.66a2,2 0,0 0,0 2.83z"
    internal const val LOCK_PATH =
        "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z"
    internal const val LAYERS_PATH =
        "M11.99,18.54l-7.37,-5.73L3,14.07l9,7 9,-7 -1.63,-1.27 -7.38,5.74zM12,16l7.36,-5.73L21,9l-9,-7 -9,7 1.63,1.27L12,16z"
    internal const val RADIO_PATH =
        "M3.24,6.15C2.51,6.43 2,7.17 2,8v12c0,1.1 0.89,2 2,2h16c1.11,0 2,-0.9 2,-2V8c0,-1.11 -0.89,-2 -2,-2H8.3l8.26,-3.34L15.88,1 3.24,6.15zM7,20c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3zM20,20h-2.15c-0.14,-0.6 -0.39,-1.14 -0.72,-1.62l2.87,-1.15V20zM20,10.5L14.5,12.7V10.5L20,8.3V10.5z"
    internal const val MIC_PATH =
        "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"

    val Power: ImageVector by lazy { icon("Power", POWER_PATH) }
    val Bedtime: ImageVector by lazy { icon("Bedtime", BEDTIME_PATH) }
    val Restart: ImageVector by lazy { icon("Restart", RESTART_PATH) }
    val Timer: ImageVector by lazy { icon("Timer", TIMER_PATH) }
    val Tv: ImageVector by lazy { icon("Tv", TV_PATH) }
    val Soccer: ImageVector by lazy { icon("Soccer", SOCCER_PATH) }
    val Layers: ImageVector by lazy { icon("Layers", LAYERS_PATH) }
    val Palette: ImageVector by lazy { icon("Palette", PALETTE_PATH) }
    val Language: ImageVector by lazy { icon("Language", LANGUAGE_PATH) }
    val Subtitles: ImageVector by lazy { icon("Subtitles", SUBTITLES_PATH) }
    val Audiotrack: ImageVector by lazy { icon("Audiotrack", AUDIOTRACK_PATH) }
    val VolumeUp: ImageVector by lazy { icon("VolumeUp", VOLUME_UP_PATH) }
    val AspectRatio: ImageVector by lazy { icon("AspectRatio", ASPECT_RATIO_PATH) }
    val Sync: ImageVector by lazy { icon("Sync", SYNC_PATH) }
    val Guide: ImageVector by lazy { icon("Guide", GUIDE_PATH) }
    val History: ImageVector by lazy { icon("History", HISTORY_PATH) }
    val Record: ImageVector by lazy { icon("Record", RECORD_PATH) }
    val Speed: ImageVector by lazy { icon("Speed", SPEED_PATH) }
    val Lock: ImageVector by lazy { icon("Lock", LOCK_PATH) }
    val Radio: ImageVector by lazy { icon("Radio", RADIO_PATH) }
    val Mic: ImageVector by lazy { icon("Mic", MIC_PATH) }
    val Film: ImageVector by lazy { icon("Film", FILM_PATH) }
    val Series: ImageVector by lazy { icon("Series", SERIES_PATH) }

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

/** Material "movie" (clapperboard). */
private const val FILM_PATH = "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z"

/** Material "tv" with a stacked frame, for TV Series. */
private const val SERIES_PATH = "M21,3H3C1.9,3 1,3.9 1,5v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5c0,-1.1 -0.9,-2 -2,-2zM21,17H3V5h18v12zM10,8v6l5,-3z"
