package com.enigmacast.domain.model

import com.enigmacast.data.openwebif.openWebifJson
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionQualitySettingTest {
    @Test
    fun settingsSavedBeforeMotionExistedStayPremium() {
        val old = """{"streamMode":"OWN_TUNER","navigationSounds":true}"""
        val read = openWebifJson.decodeFromString(AppSettings.serializer(), old)
        assertEquals(MotionQuality.PREMIUM, read.motionQuality)
        assertEquals(MotionQuality.PREMIUM, AppSettings().motionQuality)
    }

    @Test
    fun performanceIsSavedAndRead() {
        val saved = openWebifJson.encodeToString(
            AppSettings.serializer(),
            AppSettings(motionQuality = MotionQuality.PERFORMANCE),
        )
        assertEquals(
            MotionQuality.PERFORMANCE,
            openWebifJson.decodeFromString(AppSettings.serializer(), saved).motionQuality,
        )
    }
}
