package com.enigmacast.ui.remote

import com.enigmacast.domain.model.EpgEvent
import com.enigmacast.domain.model.OsdSkinId
import com.enigmacast.ui.osd.OrbitOsdSkin
import com.enigmacast.ui.osd.OsdSkins
import com.enigmacast.ui.osd.StandardOsdSkin
import com.enigmacast.ui.osd.WaveOsdSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassBannerMathTest {
    private fun event(begin: Long, duration: Long) = EpgEvent(
        title = "Journaal", shortDescription = "", plot = "", beginLabel = "20:00", endLabel = "20:25",
        beginEpochSec = begin, durationSec = duration, current = true,
    )

    @Test
    fun fractionAndMinutesLeft() {
        val e = event(1_000, 1_500)
        assertEquals(0.5f, GlassBannerMath.fraction(e, 1_750), 0.001f)
        assertEquals(13, GlassBannerMath.minutesLeft(e, 1_750))
        assertEquals(1f, GlassBannerMath.fraction(e, 9_999), 0f)
        assertNull(GlassBannerMath.minutesLeft(e, 9_999))
        assertEquals(0f, GlassBannerMath.fraction(null, 1_000), 0f)
        assertEquals(0f, GlassBannerMath.fraction(event(0, 0), 1_000), 0f)
    }

    @Test
    fun rangeJoinsTheLabels() {
        assertEquals("20:00 – 20:25", GlassBannerMath.range(event(1, 1)))
    }

    @Test
    fun particleDotsStayVisibleButVary() {
        val alphas = (0 until 60).map { GlassBannerMath.dotAlpha(it) }
        assertTrue(alphas.all { it in 0.15f..0.85f })
        assertTrue(alphas.max() - alphas.min() > 0.5f)
    }

    @Test
    fun glassSkinsGetTheirOwnInfoStripsTheOthersKeepTheStandard() {
        assertSame(OrbitOsdSkin, OsdSkins.of(OsdSkinId.GLASS_EARTH))
        assertSame(WaveOsdSkin, OsdSkins.of(OsdSkinId.GLASS_WAVE))
        OsdSkinId.entries.filter { it != OsdSkinId.GLASS_EARTH && it != OsdSkinId.GLASS_WAVE }.forEach { assertSame(StandardOsdSkin, OsdSkins.of(it)) }
    }
}
