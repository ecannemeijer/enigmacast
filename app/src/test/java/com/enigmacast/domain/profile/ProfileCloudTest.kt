package com.enigmacast.domain.profile

import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.StartupLive
import com.enigmacast.domain.sync.CacheWorkOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCloudTest {
    @Test fun defaultsAreNotUploadedOverAnotherDevice() {
        val fresh = ProfileSettingsSnapshot(scope = ProfileScope.of(AppSettings()))
        assertTrue(ProfileCloud.untouched(fresh))
        val edited = fresh.copy(scope = fresh.scope.copy(navigationSounds = false))
        assertFalse(ProfileCloud.untouched(edited))
    }

    @Test fun snapshotDoesNotCarryTheIptvLogin() {
        val encoded = ProfileCloud.encode(ProfileSettingsSnapshot(language = "nl"))
        assertFalse(encoded.contains("password"))
        assertFalse(encoded.contains("m3uUrl"))
        assertEquals("nl", ProfileCloud.decode(encoded).language)
    }

    @Test fun startupLiveIsStoredPerProfile() {
        val encoded = ProfileCloud.encode(
            ProfileSettingsSnapshot(scope = ProfileScope(startupLive = StartupLive.SATELLITE)),
        )
        assertTrue(encoded.contains("startupLive"))
        assertEquals(StartupLive.SATELLITE, ProfileCloud.decode(encoded).scope.startupLive)
        assertTrue(ProfileCloud.includesStartupLive(encoded))
    }

    @Test fun oldSnapshotWithoutStartupLiveKeepsTheStoredChoice() {
        val raw = """{"scope":{"navigationSounds":false}}"""
        val decoded = ProfileCloud.decode(raw)
        assertFalse(ProfileCloud.includesStartupLive(raw))
        assertEquals(StartupLive.OFF, decoded.scope.startupLive)
        val kept = ProfileCloud.keepStartupWhenMissing(decoded, raw, StartupLive.SATELLITE)
        assertEquals(StartupLive.SATELLITE, kept.scope.startupLive)
        assertFalse(kept.scope.navigationSounds)
        val explicit = ProfileCloud.encode(ProfileSettingsSnapshot(scope = ProfileScope(startupLive = StartupLive.OFF)))
        val merged = ProfileCloud.keepStartupWhenMissing(ProfileCloud.decode(explicit), explicit, StartupLive.SATELLITE)
        assertEquals(StartupLive.OFF, merged.scope.startupLive)
    }

    @Test fun iptvLoadStaysBehindSatelliteDownloads() {
        val withReceiver = CacheWorkOrder.steps(hasReceiver = true)
        assertEquals(CacheWorkOrder.Step.SATELLITE, withReceiver.first())
        assertEquals(CacheWorkOrder.Step.IPTV, withReceiver.last())
        assertEquals(listOf(CacheWorkOrder.Step.IPTV), CacheWorkOrder.steps(hasReceiver = false))
    }
}
