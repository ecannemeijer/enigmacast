package com.enigmacast.data.settings

import com.enigmacast.data.openwebif.openWebifJson
import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.FastMode
import com.enigmacast.domain.model.MotionQuality
import com.enigmacast.domain.model.SkinDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SettingsBootstrapTest {
    @Test
    fun firstInstallUsesPerformanceAndFastModeOn() {
        val fresh = AppSettings.firstInstall()
        assertEquals(MotionQuality.PERFORMANCE, fresh.motionQuality)
        assertEquals(FastMode.ON, fresh.fastMode)
        assertEquals(fresh, SettingsBootstrap.parse(null))
    }

    @Test
    fun existingFileWithoutThoseKeysKeepsPremiumAndAuto() {
        val old = """{"streamMode":"OWN_TUNER","navigationSounds":true}"""
        val read = SettingsBootstrap.parse(old)
        assertEquals(MotionQuality.PREMIUM, read.motionQuality)
        assertEquals(FastMode.AUTO, read.fastMode)
        assertEquals(MotionQuality.PREMIUM, AppSettings().motionQuality)
        assertEquals(FastMode.AUTO, AppSettings().fastMode)
        assertNotEquals(AppSettings.firstInstall().motionQuality, AppSettings().motionQuality)
        assertNotEquals(AppSettings.firstInstall().fastMode, AppSettings().fastMode)
    }

    @Test
    fun storedValuesAreKept() {
        val performanceOn = openWebifJson.encodeToString(
            AppSettings.serializer(),
            AppSettings(motionQuality = MotionQuality.PERFORMANCE, fastMode = FastMode.ON),
        )
        val premiumAuto = openWebifJson.encodeToString(
            AppSettings.serializer(),
            AppSettings(motionQuality = MotionQuality.PREMIUM, fastMode = FastMode.AUTO),
        )
        val performanceOnRead = SettingsBootstrap.parse(performanceOn)
        val premiumAutoRead = SettingsBootstrap.parse(premiumAuto)
        assertEquals(MotionQuality.PERFORMANCE, performanceOnRead.motionQuality)
        assertEquals(FastMode.ON, performanceOnRead.fastMode)
        assertEquals(MotionQuality.PREMIUM, premiumAutoRead.motionQuality)
        assertEquals(FastMode.AUTO, premiumAutoRead.fastMode)
    }

    @Test
    fun skinDefaultsStillRunOnExistingFiles() {
        val old = """{"streamMode":"OWN_TUNER"}"""
        val read = SettingsBootstrap.parse(old)
        assertEquals(SkinDefaults.CURRENT, read.skinDefaults)
    }
}
