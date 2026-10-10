package com.enigmacast.ui.animation

import com.enigmacast.domain.model.MotionQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnigmaMotionTest {
    @Test
    fun premiumKeepsTheFullDurations() {
        val spec = EnigmaMotion.spec(MotionQuality.PREMIUM, 1f)
        assertEquals(EnigmaMotion.FOCUS_MS, spec.focusMs)
        assertEquals(EnigmaMotion.CARD_MS, spec.cardMs)
        assertEquals(EnigmaMotion.SIDEBAR_MS, spec.sidebarMs)
        assertEquals(EnigmaMotion.SCREEN_MS, spec.screenMs)
        assertEquals(EnigmaMotion.OSD_MS, spec.osdMs)
        assertEquals(EnigmaMotion.CARD_SCALE, spec.cardScale)
        assertTrue(spec.glow)
        assertTrue(spec.shadows)
        assertTrue(spec.animate)
    }

    @Test
    fun performanceShortensMotionAndDropsGlow() {
        val spec = EnigmaMotion.spec(MotionQuality.PERFORMANCE, 1f)
        assertTrue(spec.focusMs < EnigmaMotion.FOCUS_MS)
        assertTrue(spec.sidebarMs < EnigmaMotion.SIDEBAR_MS)
        assertFalse(spec.glow)
        assertFalse(spec.shadows)
        assertTrue(spec.animate)
        assertEquals(1.03f, spec.cardScale)
    }

    @Test
    fun zeroAnimatorScaleTurnsDecorativeMotionOff() {
        val spec = EnigmaMotion.spec(MotionQuality.PREMIUM, 0f)
        assertFalse(spec.animate)
        assertFalse(spec.glow)
        assertEquals(0, spec.focusMs)
        assertEquals(0, spec.osdMs)
        assertEquals(1f, spec.cardScale)
    }
}
