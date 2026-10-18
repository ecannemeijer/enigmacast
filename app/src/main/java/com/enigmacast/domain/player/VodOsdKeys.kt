package com.enigmacast.domain.player

import android.view.KeyEvent

/**
 * Remote keys of the VOD player (IPTV films/episodes, recordings, torrents), modelled on the
 * Stremio Android TV player and Android TV's playback guidelines:
 *
 * OSD hidden: OK pauses/resumes and shows the OSD; Left/Right seek 10 s at once (held: faster)
 * and show the OSD with the seek preview; Up/Down only show the OSD. OSD shown: the focused
 * control takes the D-pad (Up/Down between rows, Left/Right on the bar seeks). Media keys work
 * in both. Back hides the OSD, Back again leaves the player.
 */
object VodOsdKeys {
    const val SEEK_STEP_MS = 10_000L
    const val SKIP_MS = 30_000L
    const val HIDE_AFTER_MS = 5_000L

    sealed interface Action {
        /** Pause or play; [show] also brings up the OSD. */
        data class TogglePause(val show: Boolean) : Action
        /** Start a seek preview on the bar (shows the OSD): [deltaMs] from the shown place. */
        data class Scrub(val deltaMs: Long) : Action
        data object Show : Action
        /** Not for the OSD: the focused control (OSD shown) or the host player. */
        data object Pass : Action
    }

    fun action(keyCode: Int, repeatCount: Int, osdShown: Boolean): Action = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
            if (repeatCount == 0) Action.TogglePause(show = true) else Action.Show
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Action.Scrub(SKIP_MS)
        KeyEvent.KEYCODE_MEDIA_REWIND -> Action.Scrub(-SKIP_MS)
        else -> if (osdShown) Action.Pass else when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (repeatCount == 0) Action.TogglePause(show = true) else Action.Show
            KeyEvent.KEYCODE_DPAD_LEFT -> Action.Scrub(-scrubStep(repeatCount))
            KeyEvent.KEYCODE_DPAD_RIGHT -> Action.Scrub(scrubStep(repeatCount))
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> Action.Show
            else -> Action.Pass
        }
    }

    /** Holding Left/Right speeds up: 10 s steps, after ~1 s 30 s, after ~2.5 s 60 s. */
    fun scrubStep(repeatCount: Int): Long = when {
        repeatCount >= 20 -> 60_000L
        repeatCount >= 8 -> 30_000L
        else -> SEEK_STEP_MS
    }

    /** "0:04 / 1:45:45" (hours only when the file has them). */
    fun times(positionMs: Long, durationMs: Long): String =
        if (durationMs > 0) "${clock(positionMs)} / ${clock(durationMs)}" else clock(positionMs)

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** "Häxan (1922)", or "Show · S1E2 · Name" for an episode. */
    fun title(name: String, year: Int? = null, season: Int? = null, episode: Int? = null, episodeName: String? = null): String {
        if (season != null && season > 0) {
            val se = "S${season}E${episode ?: 0}"
            return listOf(name, se, episodeName.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
        }
        return if (year != null && year > 0 && !name.contains("($year)")) "$name ($year)" else name
    }

    val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    fun speedLabel(speed: Float): String =
        (if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString().trimEnd('0')) + "×"

    /**
     * The buffered bar's share of the seek bar: [bufferedMs] of [durationMs], never behind the
     * played [positionMs] (a seek ahead of the buffer shows none). 0 when the length is unknown.
     */
    fun bufferedFraction(positionMs: Long, bufferedMs: Long, durationMs: Long): Float {
        if (durationMs <= 0 || bufferedMs <= positionMs) return 0f
        return (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }
}
