package com.enigmacast.domain.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyBindingsTest {
    private val none = emptyMap<String, List<String>>()

    @Test fun defaultsMatchOldOwnTunerKeys() {
        val own = KeyBindings.effective(none, KeyMode.OWN)
        assertEquals(PlayerAction.CHANNEL_UP, KeyBindings.lookup(own, KeyBindings.CHANNEL_UP, false))
        assertEquals(PlayerAction.CHANNEL_LIST, KeyBindings.lookup(own, KeyBindings.DPAD_LEFT, false))
        assertEquals(PlayerAction.INFO, KeyBindings.lookup(own, KeyBindings.GUIDE, false))
        assertEquals(PlayerAction.BANNER, KeyBindings.lookup(own, KeyBindings.DPAD_RIGHT, false))
    }

    @Test fun followHoldInfoOpensOptions() {
        val follow = KeyBindings.effective(none, KeyMode.FOLLOW)
        assertTrue(KeyBindings.hasLong(follow, KeyBindings.INFO))
        assertEquals(PlayerAction.OPTIONS, KeyBindings.lookup(follow, KeyBindings.INFO, true))
        assertNull(KeyBindings.lookup(follow, KeyBindings.INFO, false))
        assertFalse(PlayerAction.CHANNEL_UP in follow)
    }

    @Test fun assignMovesKeyAwayFromOtherAction() {
        val red = KeyBinding(183)
        var stored = KeyBindings.assign(none, KeyMode.OWN, PlayerAction.GUIDE, red)
        assertEquals(PlayerAction.GUIDE, KeyBindings.lookup(KeyBindings.effective(stored, KeyMode.OWN), 183, false))
        val up = KeyBinding(KeyBindings.DPAD_UP)
        val own = KeyBindings.effective(stored, KeyMode.OWN)
        assertEquals(PlayerAction.CHANNEL_UP, KeyBindings.conflict(own, PlayerAction.GUIDE, up))
        stored = KeyBindings.assign(stored, KeyMode.OWN, PlayerAction.GUIDE, up)
        val after = KeyBindings.effective(stored, KeyMode.OWN)
        assertEquals(listOf(up), after[PlayerAction.GUIDE])
        assertFalse(up in after.getValue(PlayerAction.CHANNEL_UP))
        assertTrue(KeyBinding(KeyBindings.CHANNEL_UP) in after.getValue(PlayerAction.CHANNEL_UP))
        // Modes are separate.
        assertEquals(KeyBindings.effective(none, KeyMode.FOLLOW), KeyBindings.effective(stored, KeyMode.FOLLOW))
    }

    @Test fun shortAndLongAreDifferentKeys() {
        val stored = KeyBindings.assign(none, KeyMode.OWN, PlayerAction.RECORD, KeyBinding(KeyBindings.INFO, long = true))
        val own = KeyBindings.effective(stored, KeyMode.OWN)
        assertEquals(PlayerAction.RECORD, KeyBindings.lookup(own, KeyBindings.INFO, true))
        assertEquals(PlayerAction.INFO, KeyBindings.lookup(own, KeyBindings.INFO, false))
    }

    @Test fun resetRestoresDefaults() {
        var stored = KeyBindings.clear(none, KeyMode.OWN, PlayerAction.CHANNEL_LIST)
        stored = KeyBindings.assign(stored, KeyMode.FOLLOW, PlayerAction.GUIDE, KeyBinding(183))
        assertEquals(emptyList<KeyBinding>(), KeyBindings.effective(stored, KeyMode.OWN)[PlayerAction.CHANNEL_LIST])
        stored = KeyBindings.reset(stored, KeyMode.OWN)
        assertEquals(KeyBindings.effective(none, KeyMode.OWN), KeyBindings.effective(stored, KeyMode.OWN))
        assertEquals(PlayerAction.GUIDE, KeyBindings.lookup(KeyBindings.effective(stored, KeyMode.FOLLOW), 183, false))
    }

    @Test fun resetActionTakesDefaultKeyBack() {
        var stored = KeyBindings.assign(none, KeyMode.OWN, PlayerAction.GUIDE, KeyBinding(KeyBindings.DPAD_RIGHT))
        stored = KeyBindings.resetAction(stored, KeyMode.OWN, PlayerAction.BANNER)
        val own = KeyBindings.effective(stored, KeyMode.OWN)
        assertEquals(PlayerAction.BANNER, KeyBindings.lookup(own, KeyBindings.DPAD_RIGHT, false))
        assertEquals(emptyList<KeyBinding>(), own[PlayerAction.GUIDE])
    }

    @Test fun fixedKeysAndEncoding() {
        assertTrue(KeyBindings.isFixed(KeyBindings.BACK))
        assertTrue(KeyBindings.isFixed(7))
        assertFalse(KeyBindings.isFixed(KeyBindings.PROG_BLUE))
        assertEquals(KeyBinding(165, true), KeyBinding.decode("165L"))
        assertEquals("186", KeyBinding(186).encode())
        assertNull(KeyBinding.decode("x"))
    }

    @Test fun redOpensSwitchReceiverOutsideThePlayer() {
        val menu = KeyBindings.effective(none, KeyMode.MENU)
        assertEquals(listOf(PlayerAction.SWITCH_RECEIVER), menu.keys.toList())
        assertEquals(PlayerAction.SWITCH_RECEIVER, KeyBindings.lookup(menu, KeyBindings.PROG_RED, false))
    }

    @Test fun redOpensSwitchReceiverInTheOwnTunerPlayer() {
        val own = KeyBindings.effective(none, KeyMode.OWN)
        assertEquals(PlayerAction.SWITCH_RECEIVER, KeyBindings.lookup(own, KeyBindings.PROG_RED, false))
        // Following the receiver, Red still goes to the box; Switch receiver can be assigned.
        val follow = KeyBindings.effective(none, KeyMode.FOLLOW)
        assertEquals(emptyList<KeyBinding>(), follow[PlayerAction.SWITCH_RECEIVER])
        val stored = KeyBindings.assign(none, KeyMode.FOLLOW, PlayerAction.SWITCH_RECEIVER, KeyBinding(KeyBindings.PROG_GREEN))
        assertEquals(PlayerAction.SWITCH_RECEIVER, KeyBindings.lookup(KeyBindings.effective(stored, KeyMode.FOLLOW), KeyBindings.PROG_GREEN, false))
    }

    @Test fun colourKeysCanBeAssigned() {
        listOf(KeyBindings.PROG_RED, KeyBindings.PROG_GREEN, KeyBindings.PROG_YELLOW, KeyBindings.PROG_BLUE).forEach { code ->
            assertFalse(KeyBindings.isFixed(code))
            val stored = KeyBindings.assign(none, KeyMode.FOLLOW, PlayerAction.RECORD, KeyBinding(code))
            assertEquals(PlayerAction.RECORD, KeyBindings.lookup(KeyBindings.effective(stored, KeyMode.FOLLOW), code, false))
        }
        // Blue was the own-tuner options key; giving it to Guide moves it.
        val stored = KeyBindings.assign(none, KeyMode.OWN, PlayerAction.GUIDE, KeyBinding(KeyBindings.PROG_BLUE))
        assertEquals(emptyList<KeyBinding>(), KeyBindings.effective(stored, KeyMode.OWN)[PlayerAction.OPTIONS])
    }
}
