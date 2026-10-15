package com.enigmacast.domain.profile

import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.LastChannel
import com.enigmacast.domain.model.ReceiverConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRulesTest {
    private val base = ProfileRules.ensureDefault(ProfileState(), "Me")

    @Test fun pickerOnFirstRunAndWithSeveralProfiles() {
        assertTrue(ProfileRules.askOnStart(base))
        assertFalse(ProfileRules.askOnStart(base.copy(chosenOnce = true)))
        assertTrue(ProfileRules.askOnStart(ProfileRules.add(base.copy(chosenOnce = true), "p-1", "Kid", 3)))
    }

    @Test fun switchSwapsOnlyProfileSettings() {
        val state = ProfileRules.add(base, "p-1", "Kid", 3)
        val box = ReceiverConfig(id = "r", name = "SF8008")
        val mine = AppSettings(receivers = listOf(box), lastChannels = mapOf("r" to LastChannel("npo1")), navigationSounds = false)
        val (s1, kid) = ProfileRules.switch(state, mine, "p-1")
        assertEquals("p-1", s1.activeId)
        assertEquals(listOf(box), kid.receivers) // shared
        assertTrue(kid.lastChannels.isEmpty())
        assertTrue(kid.navigationSounds)
        val (s2, back) = ProfileRules.switch(s1, kid.copy(lastChannels = mapOf("r" to LastChannel("cartoon"))), ProfileState.DEFAULT_ID)
        assertEquals("npo1", back.lastChannels["r"]?.reference)
        assertFalse(back.navigationSounds)
        assertEquals("cartoon", s2.scopes["p-1"]?.lastChannels?.get("r")?.reference)
    }

    @Test fun scopedKeysKeepDefaultData() {
        assertEquals("rec1", ProfileRules.scopedKey(ProfileState.DEFAULT_ID, "rec1"))
        assertEquals("p-1|rec1", ProfileRules.scopedKey("p-1", "rec1"))
        assertEquals("rec1", ProfileRules.unscope("p-1", "p-1|rec1"))
        assertNull(ProfileRules.unscope(ProfileState.DEFAULT_ID, "p-1|rec1"))
        assertNull(ProfileRules.unscope("p-1", "rec1"))
        assertEquals("1:0:19:1|100", ProfileRules.unscope(ProfileState.DEFAULT_ID, "1:0:19:1|100"))
        val uuid = "11111111-1111-4111-8111-111111111111"
        assertNull(ProfileRules.unscope(ProfileState.DEFAULT_ID, "$uuid|rec1"))
        assertEquals("rec1", ProfileRules.unscope(uuid, "$uuid|rec1"))
    }

    @Test fun playlistAudienceEmptyMeansEveryone() {
        val kid = ProfileRules.add(base, "p-1", "Kid", 3, uuid = "kid")
        assertTrue(ProfileRules.seesPlaylist(kid, ProfileState.LEGACY_UUID))
        assertTrue(ProfileRules.seesPlaylist(kid, "kid"))
        val onlyKid = ProfileRules.playlistAccess(kid, "kid", allowed = true)
            .let { ProfileRules.playlistAccess(it, ProfileState.LEGACY_UUID, allowed = false) }
        assertFalse(ProfileRules.seesPlaylist(onlyKid, ProfileState.LEGACY_UUID))
        assertTrue(ProfileRules.seesPlaylist(onlyKid, "kid"))
        assertTrue(ProfileRules.playlistAccess(onlyKid, ProfileState.LEGACY_UUID, allowed = true).playlistAudience.isEmpty())
    }

    @Test fun legacyProfileKeepsStableUuid() {
        val ready = ProfileRules.ensureDefault(ProfileState(), "Me")
        assertEquals(ProfileState.LEGACY_UUID, ready.active.uuid)
        val again = ProfileRules.ensureDefault(ready, "Me")
        assertEquals(ProfileState.LEGACY_UUID, again.active.uuid)
    }

    @Test fun cannotDeleteActiveOrLast() {
        assertFalse(ProfileRules.canDelete(base, ProfileState.DEFAULT_ID))
        val two = ProfileRules.add(base, "p-1", "Kid", 3)
        assertTrue(ProfileRules.canDelete(two, "p-1"))
        assertEquals(1, ProfileRules.delete(two, "p-1").profiles.size)
    }
}
