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
    private fun write(settings: AppSettings) = openWebifJson.encodeToString(AppSettings.serializer(), settings)

    @Test
    fun freshInstallsAndNewProfilesGetGlassEarthAlreadyMigrated() {
        assertEquals(OsdSkinId.GLASS_EARTH, OsdSkinId.DEFAULT)
        assertEquals(OsdSkinId.GLASS_EARTH, AppSettings().osdSkin)
        assertEquals(SkinDefaults.CURRENT, AppSettings().skinDefaults)
        assertEquals(OsdSkinId.GLASS_EARTH, ProfileScope().osdSkin)
        assertEquals(SkinDefaults.CURRENT, ProfileScope().skinDefaults)
        assertEquals(OsdSkinId.GLASS_EARTH, ProfileSettingsSnapshot().scope.osdSkin)
    }

    @Test
    fun legacyEnigmaCastMovesToGlassEarthOnce() {
        // Unsaved (older versions left the default out) and saved EnigmaCast both move.
        assertEquals(OsdSkinId.GLASS_EARTH, settings("""{"receivers":[],"streamMode":"OWN_TUNER"}""").osdSkin)
        assertEquals(OsdSkinId.GLASS_EARTH, settings("""{"osdSkin":"STANDARD"}""").osdSkin)
        assertEquals(SkinDefaults.CURRENT, settings("""{"osdSkin":"STANDARD"}""").skinDefaults)
        assertTrue(SkinDefaults.pending(openWebifJson, """{"osdSkin":"STANDARD"}"""))
        assertFalse(SkinDefaults.pending(openWebifJson, write(settings("""{"osdSkin":"STANDARD"}"""))))
    }

    @Test
    fun otherLegacyChoicesAreKept() {
        OsdSkinId.entries.filter { it != OsdSkinId.STANDARD }
            .forEach { assertEquals(it, settings("""{"osdSkin":"${it.name}"}""").osdSkin) }
    }

    @Test
    fun enigmaCastPickedAfterTheMoveIsKept() {
        val migrated = settings("""{"osdSkin":"METRIX"}""")
        val saved = write(migrated.copy(osdSkin = OsdSkinId.STANDARD))
        assertTrue(saved, "\"osdSkin\":\"STANDARD\"" in saved)
        assertTrue(saved, "\"${SkinDefaults.MARKER}\":${SkinDefaults.CURRENT}" in saved)
        assertEquals(OsdSkinId.STANDARD, settings(saved).osdSkin)
        assertEquals(OsdSkinId.STANDARD, settings(write(settings(saved))).osdSkin)
    }

    @Test
    fun theSkinAndMarkerAreAlwaysWritten() {
        val saved = write(AppSettings())
        assertTrue(saved, "\"osdSkin\":\"GLASS_EARTH\"" in saved)
        assertTrue(saved, "\"${SkinDefaults.MARKER}\":1" in saved)
        val scope = profileJson.encodeToString(ProfileScope.serializer(), ProfileScope())
        assertTrue(scope, "\"osdSkin\":\"GLASS_EARTH\"" in scope)
        assertTrue(scope, "\"${SkinDefaults.MARKER}\":1" in scope)
    }

    @Test
    fun savedProfileScopesMoveOnceAndKeepLaterChoices() {
        val legacy = """{"profiles":[{"id":"default","name":"A"}],"scopes":{"kids":{"navigationSounds":false},"std":{"osdSkin":"STANDARD"},"x":{"osdSkin":"METRIX"},"later":{"osdSkin":"STANDARD","skinDefaults":1}}}"""
        assertTrue(SkinDefaults.pendingIn(profileJson, legacy))
        val state = SkinDefaults.decodeScopes(profileJson, ProfileState.serializer(), legacy)
        assertEquals(OsdSkinId.GLASS_EARTH, state.scopes.getValue("kids").osdSkin)
        assertFalse(state.scopes.getValue("kids").navigationSounds)
        assertEquals(OsdSkinId.GLASS_EARTH, state.scopes.getValue("std").osdSkin)
        assertEquals(OsdSkinId.METRIX, state.scopes.getValue("x").osdSkin)
        assertEquals(OsdSkinId.STANDARD, state.scopes.getValue("later").osdSkin)

        val written = profileJson.encodeToString(ProfileState.serializer(), state)
        assertFalse(SkinDefaults.pendingIn(profileJson, written))
        assertEquals(state, SkinDefaults.decodeScopes(profileJson, ProfileState.serializer(), written))
    }

    @Test
    fun switchingToAProfileWithoutSavedSettingsGivesGlassEarth() {
        val state = ProfileState(profiles = listOf(Profile("a", "A", 0), Profile("b", "B", 1)), activeId = "a")
        val (toB, applied) = ProfileRules.switch(state, AppSettings(osdSkin = OsdSkinId.STANDARD), "b")
        assertEquals(OsdSkinId.GLASS_EARTH, applied.osdSkin)
        val (back, restored) = ProfileRules.switch(toB, applied, "a")
        assertEquals(OsdSkinId.STANDARD, restored.osdSkin)
        assertEquals(OsdSkinId.GLASS_EARTH, back.scopes.getValue("b").osdSkin)
    }

    @Test
    fun syncedProfilesMoveTheSameWay() {
        assertEquals(OsdSkinId.GLASS_EARTH, ProfileCloud.decode("""{"scope":{"navigationSounds":true}}""").scope.osdSkin)
        assertEquals(OsdSkinId.GLASS_EARTH, ProfileCloud.decode("""{"scope":{"osdSkin":"STANDARD"}}""").scope.osdSkin)
        assertEquals(OsdSkinId.AURORA, ProfileCloud.decode("""{"scope":{"osdSkin":"AURORA"}}""").scope.osdSkin)
        assertEquals(OsdSkinId.STANDARD, ProfileCloud.decode("""{"scope":{"osdSkin":"STANDARD","skinDefaults":1}}""").scope.osdSkin)

        val picked = ProfileSettingsSnapshot(scope = ProfileScope(osdSkin = OsdSkinId.STANDARD))
        val uploaded = ProfileCloud.encode(picked)
        assertTrue(uploaded, "\"skinDefaults\":1" in uploaded)
        assertEquals(OsdSkinId.STANDARD, ProfileCloud.decode(uploaded).scope.osdSkin)

        assertTrue(ProfileCloud.untouched(ProfileSettingsSnapshot()))
        assertFalse(ProfileCloud.untouched(picked))
    }
}
