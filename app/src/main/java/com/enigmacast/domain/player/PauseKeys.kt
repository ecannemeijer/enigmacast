package com.enigmacast.domain.player

import android.view.KeyEvent

/** Media keys on live TV: Play only resumes, Pause only pauses, Play/Pause (or a remapped key) toggles. */
object PauseKeys {
    fun toggles(keyCode: Int, paused: Boolean): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY -> paused
        KeyEvent.KEYCODE_MEDIA_PAUSE -> !paused
        else -> true
    }
}
