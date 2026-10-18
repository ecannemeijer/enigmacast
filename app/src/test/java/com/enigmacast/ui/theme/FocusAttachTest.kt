package com.enigmacast.ui.theme

import androidx.compose.ui.focus.FocusRequester
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusAttachTest {
    @Test
    fun countsEachAttachedNode() {
        val r = FocusRequester()
        assertFalse(FocusAttach.isAttached(r))
        FocusAttach.attach(r)
        FocusAttach.attach(r)
        FocusAttach.detach(r)
        assertTrue(FocusAttach.isAttached(r))
        FocusAttach.detach(r)
        assertFalse(FocusAttach.isAttached(r))
        FocusAttach.detach(r)
        assertFalse(FocusAttach.isAttached(r))
    }
}
