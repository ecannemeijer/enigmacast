package com.enigmacast.domain.model

import com.enigmacast.data.openwebif.openWebifJson
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickStartSettingTest {
    @Test
    fun quickStartIsOffByDefault() {
        assertFalse(AppSettings().playback.fastStart)
        assertFalse(openWebifJson.decodeFromString(AppSettings.serializer(), """{"streamMode":"OWN_TUNER"}""").playback.fastStart)
    }

    @Test
    fun theOldDefaultOnKeyIsNoLongerRead() {
        // beta.113/114: "fastStart" defaulted to on; files written with defaults carried it as true.
        val old = """{"playback":{"fastStart":true,"lowLatency":false}}"""
        assertFalse(openWebifJson.decodeFromString(AppSettings.serializer(), old).playback.fastStart)
    }

    @Test
    fun turningItOnIsSavedAndRead() {
        val on = AppSettings().let { it.copy(playback = it.playback.copy(fastStart = true)) }
        val saved = openWebifJson.encodeToString(AppSettings.serializer(), on)
        assertTrue(saved.contains("\"quickStart\":true"))
        assertTrue(openWebifJson.decodeFromString(AppSettings.serializer(), saved).playback.fastStart)
        val withDefaults = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val off = withDefaults.encodeToString(AppSettings.serializer(), AppSettings())
        assertFalse(withDefaults.decodeFromString(AppSettings.serializer(), off).playback.fastStart)
    }
}
