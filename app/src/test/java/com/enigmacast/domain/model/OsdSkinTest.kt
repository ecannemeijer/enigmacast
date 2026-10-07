package com.enigmacast.domain.model

import com.enigmacast.R
import com.enigmacast.data.openwebif.openWebifJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OsdSkinTest {
    @Test
    fun settingsFromBeforeSkinsUseTheStandardSkin() {
        val saved = """{"receivers":[],"streamMode":"OWN_TUNER"}"""
        assertEquals(OsdSkinId.STANDARD, openWebifJson.decodeFromString<AppSettings>(saved).osdSkin)
    }

    @Test
    fun theSkinChoiceIsSavedByName() {
        val saved = openWebifJson.encodeToString(AppSettings.serializer(), AppSettings(osdSkin = OsdSkinId.STANDARD))
        assertEquals(OsdSkinId.STANDARD, openWebifJson.decodeFromString<AppSettings>(saved).osdSkin)
        assertEquals(R.string.skin_standard, OsdSkinId.STANDARD.label)
    }

    @Test
    fun settingsFromBeforeTheInfoStripShowSignalSatelliteAndEncryption() {
        val saved = """{"receivers":[]}"""
        val fields = openWebifJson.decodeFromString<AppSettings>(saved).osdInfo
        assertTrue(fields.signal)
        assertTrue(fields.satellite)
        assertTrue(fields.encryption)
        assertTrue(fields.tuner)
        assertFalse(fields.technical)
    }
}
