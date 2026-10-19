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
        assertEquals(OsdSkinId.STANDARD, SkinDefaults.decodeSettings(openWebifJson, AppSettings.serializer(), saved).osdSkin)
    }

    @Test
    fun theSkinChoiceIsSavedByName() {
        for (id in OsdSkinId.entries) {
            val saved = openWebifJson.encodeToString(AppSettings.serializer(), AppSettings(osdSkin = id))
            assertEquals(id, openWebifJson.decodeFromString<AppSettings>(saved).osdSkin)
        }
        assertEquals(R.string.skin_enigmacast, OsdSkinId.STANDARD.label)
        assertEquals(R.string.skin_openpli, OsdSkinId.OPENPLI.label)
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
