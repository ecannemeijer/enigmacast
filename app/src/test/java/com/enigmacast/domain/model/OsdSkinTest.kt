package com.enigmacast.domain.model

import com.enigmacast.data.openwebif.openWebifJson
import org.junit.Assert.assertEquals
import org.junit.Test

class OsdSkinTest {
    @Test
    fun settingsFromBeforeSkinsUseTheStandardSkin() {
        val saved = """{"receivers":[],"streamMode":"OWN_TUNER"}"""
        assertEquals(OsdSkin.STANDARD, openWebifJson.decodeFromString<AppSettings>(saved).osdSkin)
    }

    @Test
    fun theSkinChoiceIsSavedByName() {
        val saved = openWebifJson.encodeToString(AppSettings.serializer(), AppSettings(osdSkin = OsdSkin.STANDARD))
        assertEquals(OsdSkin.STANDARD, openWebifJson.decodeFromString<AppSettings>(saved).osdSkin)
        assertEquals("Standaard", OsdSkin.STANDARD.label)
    }
}
