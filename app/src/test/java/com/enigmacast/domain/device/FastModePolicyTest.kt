package com.enigmacast.domain.device

import com.enigmacast.domain.model.FastMode
import com.enigmacast.domain.model.MotionQuality
import com.enigmacast.ui.animation.EnigmaMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastModePolicyTest {
    @Test fun `low-end devices are recognised`() {
        assertTrue(FastModePolicy.isLowEnd(lowRamDevice = true, memoryClassMb = 512, cores = 8))
        assertTrue(FastModePolicy.isLowEnd(false, 192, 4))
        assertTrue(FastModePolicy.isLowEnd(false, 256, 2))
        assertFalse(FastModePolicy.isLowEnd(false, 256, 4))
        assertFalse(FastModePolicy.isLowEnd(false, 0, 0))
    }

    @Test fun `auto follows the device, on and off override`() {
        assertTrue(FastModePolicy.isOn(FastMode.AUTO, lowEnd = true))
        assertFalse(FastModePolicy.isOn(FastMode.AUTO, lowEnd = false))
        assertTrue(FastModePolicy.isOn(FastMode.ON, lowEnd = false))
        assertFalse(FastModePolicy.isOn(FastMode.OFF, lowEnd = true))
    }

    @Test fun `fast mode turns all motion and effects off`() {
        val spec = EnigmaMotion.spec(MotionQuality.PREMIUM, 1f, fast = true)
        assertFalse(spec.animate)
        assertFalse(spec.glow)
        assertFalse(spec.shadows)
        assertEquals(1f, spec.cardScale)
        assertEquals(0, spec.sidebarMs)
        assertTrue(EnigmaMotion.spec(MotionQuality.PREMIUM, 1f).animate)
    }
}
