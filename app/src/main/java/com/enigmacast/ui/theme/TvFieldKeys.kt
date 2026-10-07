package com.enigmacast.ui.theme

import android.view.KeyEvent

/**
 * Remote keys for [TvTextField], as a pure decision so it can be unit-tested.
 *
 * Browsing (field highlighted, no keyboard): OK / Enter starts editing; every other key is left
 * alone, so the arrows move focus on as for any button.
 *
 * Editing (cursor and keyboard): Back finishes (while the keyboard is open the keyboard gets
 * Back first and closes itself; the field notices that and finishes too; with predictive back
 * Back arrives through the back dispatcher instead). On a single-line field Up / Down finish and
 * move on, and OK / Enter finishes. Left / Right are left to the text field (cursor on a
 * keyboard; Compose moves focus on for a D-pad, which also ends editing).
 */
object TvFieldKeys {
    enum class Action { NONE, CONSUME, START_EDIT, STOP_EDIT, STOP_AND_MOVE_UP, STOP_AND_MOVE_DOWN }

    fun whileBrowsing(keyCode: Int, up: Boolean): Action = when {
        isOk(keyCode) -> if (up) Action.START_EDIT else Action.CONSUME
        else -> Action.NONE
    }

    fun whileEditing(keyCode: Int, up: Boolean, multiline: Boolean): Action = when {
        keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE -> if (up) Action.STOP_EDIT else Action.CONSUME
        multiline -> if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) okInMultiline(up) else Action.NONE
        keyCode == KeyEvent.KEYCODE_DPAD_UP -> if (up) Action.CONSUME else Action.STOP_AND_MOVE_UP
        keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> if (up) Action.CONSUME else Action.STOP_AND_MOVE_DOWN
        isOk(keyCode) -> if (up) Action.STOP_EDIT else Action.CONSUME
        else -> Action.NONE
    }

    /** Whether the keyboard has been seen open during this edit, and whether editing should end now. */
    data class ImeStep(val imeSeen: Boolean, val stop: Boolean)

    /**
     * Keyboard watch while editing: once the keyboard has been open, it closing (the user pressed
     * Back on it, or its own hide key) ends editing. A keyboard that never reports itself open
     * (older TVs, hardware keyboard) never ends editing this way; Back and OK still do.
     */
    fun imeStep(editing: Boolean, imeVisible: Boolean, imeSeen: Boolean): ImeStep = when {
        !editing -> ImeStep(imeSeen = false, stop = false)
        imeVisible -> ImeStep(imeSeen = true, stop = false)
        else -> ImeStep(imeSeen = imeSeen, stop = imeSeen)
    }

    /** In a multi-line field Enter types a new line; the remote's OK finishes. */
    private fun okInMultiline(up: Boolean): Action = if (up) Action.STOP_EDIT else Action.CONSUME

    private fun isOk(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
}
