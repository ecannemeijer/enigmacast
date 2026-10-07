package com.enigmacast.domain.model

import com.enigmacast.data.openwebif.openWebifJson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationSoundsSettingTest {
    @Test
    fun settingsSavedBeforeTheToggleExistedKeepSoundsOn() {
        val old = """{"streamMode":"OWN_TUNER","osd":{"format":"png"}}"""
        assertTrue(openWebifJson.decodeFromString(AppSettings.serializer(), old).navigationSounds)
        assertTrue(AppSettings().navigationSounds)
    }

    @Test
    fun switchingSoundsOffIsSavedAndRead() {
        val saved = openWebifJson.encodeToString(AppSettings.serializer(), AppSettings(navigationSounds = false))
        assertFalse(openWebifJson.decodeFromString(AppSettings.serializer(), saved).navigationSounds)
    }
}
