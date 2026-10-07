package com.enigmacast.ui.theme

import android.view.KeyEvent
import com.enigmacast.ui.theme.TvFieldKeys.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class TvFieldKeysTest {
    private fun browse(code: Int, up: Boolean) = TvFieldKeys.whileBrowsing(code, up)
    private fun edit(code: Int, up: Boolean, multiline: Boolean = false) = TvFieldKeys.whileEditing(code, up, multiline)

    @Test
    fun `arrows pass through while browsing so focus moves on without Back`() {
        listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
            .forEach { code ->
                assertEquals(Action.NONE, browse(code, up = false))
                assertEquals(Action.NONE, browse(code, up = true))
            }
    }

    @Test
    fun `Back while browsing is left to the screen`() {
        assertEquals(Action.NONE, browse(KeyEvent.KEYCODE_BACK, up = false))
        assertEquals(Action.NONE, browse(KeyEvent.KEYCODE_BACK, up = true))
    }

    @Test
    fun `OK, Enter and numpad Enter start editing on release`() {
        listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER).forEach { code ->
            assertEquals(Action.CONSUME, browse(code, up = false))
            assertEquals(Action.START_EDIT, browse(code, up = true))
        }
    }

    @Test
    fun `typing keys while browsing do nothing`() {
        assertEquals(Action.NONE, browse(KeyEvent.KEYCODE_1, up = true))
        assertEquals(Action.NONE, browse(KeyEvent.KEYCODE_A, up = true))
    }

    @Test
    fun `Back while editing ends editing and is consumed so the screen stays`() {
        assertEquals(Action.CONSUME, edit(KeyEvent.KEYCODE_BACK, up = false))
        assertEquals(Action.STOP_EDIT, edit(KeyEvent.KEYCODE_BACK, up = true))
        assertEquals(Action.STOP_EDIT, edit(KeyEvent.KEYCODE_ESCAPE, up = true))
        assertEquals(Action.STOP_EDIT, edit(KeyEvent.KEYCODE_BACK, up = true, multiline = true))
    }

    @Test
    fun `Up and Down while editing a single line end editing and move on at once`() {
        assertEquals(Action.STOP_AND_MOVE_UP, edit(KeyEvent.KEYCODE_DPAD_UP, up = false))
        assertEquals(Action.CONSUME, edit(KeyEvent.KEYCODE_DPAD_UP, up = true))
        assertEquals(Action.STOP_AND_MOVE_DOWN, edit(KeyEvent.KEYCODE_DPAD_DOWN, up = false))
        assertEquals(Action.CONSUME, edit(KeyEvent.KEYCODE_DPAD_DOWN, up = true))
    }

    @Test
    fun `OK while editing a single line finishes`() {
        assertEquals(Action.CONSUME, edit(KeyEvent.KEYCODE_DPAD_CENTER, up = false))
        assertEquals(Action.STOP_EDIT, edit(KeyEvent.KEYCODE_DPAD_CENTER, up = true))
        assertEquals(Action.STOP_EDIT, edit(KeyEvent.KEYCODE_ENTER, up = true))
    }

    @Test
    fun `typing and Left Right go to the text while editing`() {
        listOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
            .forEach { code -> assertEquals(Action.NONE, edit(code, up = false)) }
    }

    @Test
    fun `multi-line field keeps Enter and arrows for the text, OK still finishes`() {
        assertEquals(Action.NONE, edit(KeyEvent.KEYCODE_ENTER, up = true, multiline = true))
        assertEquals(Action.NONE, edit(KeyEvent.KEYCODE_DPAD_UP, up = false, multiline = true))
        assertEquals(Action.NONE, edit(KeyEvent.KEYCODE_DPAD_DOWN, up = false, multiline = true))
        assertEquals(Action.STOP_EDIT, edit(KeyEvent.KEYCODE_DPAD_CENTER, up = true, multiline = true))
    }

    @Test
    fun `keyboard closing after it was open ends editing`() {
        var step = TvFieldKeys.imeStep(editing = true, imeVisible = false, imeSeen = false)
        assertEquals(TvFieldKeys.ImeStep(imeSeen = false, stop = false), step) // keyboard not up yet
        step = TvFieldKeys.imeStep(editing = true, imeVisible = true, imeSeen = step.imeSeen)
        assertEquals(TvFieldKeys.ImeStep(imeSeen = true, stop = false), step)
        step = TvFieldKeys.imeStep(editing = true, imeVisible = false, imeSeen = step.imeSeen)
        assertEquals(TvFieldKeys.ImeStep(imeSeen = true, stop = true), step)
    }

    @Test
    fun `keyboard state is forgotten when not editing`() {
        assertEquals(TvFieldKeys.ImeStep(imeSeen = false, stop = false), TvFieldKeys.imeStep(editing = false, imeVisible = true, imeSeen = true))
        assertEquals(TvFieldKeys.ImeStep(imeSeen = false, stop = false), TvFieldKeys.imeStep(editing = false, imeVisible = false, imeSeen = true))
    }
}
