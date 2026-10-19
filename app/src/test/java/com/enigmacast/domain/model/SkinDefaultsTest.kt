package com.enigmacast.domain.model

import com.enigmacast.data.openwebif.openWebifJson
import com.enigmacast.domain.profile.Profile
import com.enigmacast.domain.profile.ProfileCloud
import com.enigmacast.domain.profile.ProfileRules
import com.enigmacast.domain.profile.ProfileScope
import com.enigmacast.domain.profile.ProfileSettingsSnapshot
import com.enigmacast.domain.profile.ProfileState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinDefaultsTest {
    /** The profile store's own JSON settings. */
    private val profileJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun settings(raw: String) = SkinDefaults.decodeSettings(openWebifJson, AppSettings.serializer(), raw)

    @Test
    fun freshInstallsAndNewProfilesGetGlassEarth() {
        assertEquals(OsdSkinId.GLASS_EARTH, OsdSkinId.DEFAULT)
        assertEquals(OsdSkinId.GLASS_EARTH, AppSettings().osdSkin)
        assertEquals(OsdSkinId.GLASS_EARTH, ProfileScope().osdSkin)
        assertEquals(OsdSkinId.GLASS_EARTH, ProfileSettingsSnapshot().scope.osdSkin)
    }

    @Test
    fun settingsSavedBeforeTheNewDefaultKeepEnigmaCast() {
        // Older versions left the skin out while it was EnigmaCast.
        assertEquals(OsdSkinId.STANDARD, settings("""{"receivers":[],"streamMode":"OWN_TUNER"}""").osdSkin)
        assertEquals(OsdSkinId.STANDARD, settings("""{}""").osdSkin)
    }

    @Test
    fun anExplicitChoiceIsNeverReplaced() {
        for (id in OsdSkinId.entries) assertEquals(id, settings("""{"osdSkin":"${id.name}"}""").osdSkin)
    }

    @Test
    fun theSkinIsAlwaysWrittenSoTheDefaultSurvivesAReload() {
        val saved = openWebifJson.encodeToString(AppSettings.serializer(), AppSettings())
        assertTrue(saved, "\"osdSkin\":\"GLASS_EARTH\"" in saved)
        assertEquals(OsdSkinId.GLASS_EARTH, settings(saved).osdSkin)
        val scope = profileJson.encodeToString(ProfileScope.serializer(), ProfileScope())
        assertTrue(scope, "\"osdSkin\":\"GLASS_EARTH\"" in scope)
    }

    @Test
    fun savedProfileScopesFromBeforeKeepEnigmaCastAndNewOnesRoundTrip() {
        val legacy = """{"profiles":[{"id":"default","name":"A"},{"id":"kids","name":"B"}],"scopes":{"kids":{"navigationSounds":false},"x":{"osdSkin":"METRIX"}}}"""
        val state = SkinDefaults.decodeScopes(profileJson, ProfileState.serializer(), legacy)
        assertEquals(OsdSkinId.STANDARD, state.scopes.getValue("kids").osdSkin)
        assertFalse(state.scopes.getValue("kids").navigationSounds)
        assertEquals(OsdSkinId.METRIX, state.scopes.getValue("x").osdSkin)

        val written = profileJson.encodeToString(ProfileState.serializer(), ProfileState(scopes = mapOf("n" to ProfileScope())))
        assertEquals(OsdSkinId.GLASS_EARTH, SkinDefaults.decodeScopes(profileJson, ProfileState.serializer(), written).scopes.getValue("n").osdSkin)
    }

    @Test
    fun switchingToAProfileWithoutSavedSettingsGivesGlassEarth() {
        val state = ProfileState(profiles = listOf(Profile("a", "A", 0), Profile("b", "B", 1)), activeId = "a")
        val (_, applied) = ProfileRules.switch(state, AppSettings(osdSkin = OsdSkinId.MIDNIGHT), "b")
        assertEquals(OsdSkinId.GLASS_EARTH, applied.osdSkin)
        val (back, restored) = ProfileRules.switch(ProfileRules.switch(state, AppSettings(osdSkin = OsdSkinId.MIDNIGHT), "b").first, applied, "a")
        assertEquals(OsdSkinId.MIDNIGHT, restored.osdSkin)
        assertEquals(OsdSkinId.GLASS_EARTH, back.scopes.getValue("b").osdSkin)
    }

    @Test
    fun syncedProfilesWithoutASkinKeepEnigmaCastAndUntouchedDevicesDoNotUpload() {
        assertEquals(OsdSkinId.STANDARD, ProfileCloud.decode("""{"scope":{"navigationSounds":true}}""").scope.osdSkin)
        assertEquals(OsdSkinId.AURORA, ProfileCloud.decode("""{"scope":{"osdSkin":"AURORA"}}""").scope.osdSkin)
        assertEquals(OsdSkinId.GLASS_EARTH, ProfileCloud.decode(ProfileCloud.encode(ProfileSettingsSnapshot())).scope.osdSkin)

        assertTrue(ProfileCloud.untouched(ProfileSettingsSnapshot()))
        assertTrue(ProfileCloud.untouched(ProfileSettingsSnapshot(scope = ProfileScope(osdSkin = OsdSkinId.STANDARD))))
        assertFalse(ProfileCloud.untouched(ProfileSettingsSnapshot(scope = ProfileScope(osdSkin = OsdSkinId.GLASS_WAVE))))
    }
}
