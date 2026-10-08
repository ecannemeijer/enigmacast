package com.enigmacast.domain.tuner

import com.enigmacast.data.openwebif.openWebifJson
import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.StreamMode
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamModeTest {
    @Test
    fun freshInstallUsesOwnTuner() {
        assertEquals(StreamMode.OWN_TUNER, AppSettings().streamMode)
    }

    @Test
    fun settingsSavedBeforeTheOptionExistedSwitchToOwnTuner() {
        val saved = """{"receivers":[{"id":"a","name":"Box","host":"192.168.1.100"}],"activeReceiverId":"a"}"""
        val settings = openWebifJson.decodeFromString<AppSettings>(saved)
        assertEquals(StreamMode.OWN_TUNER, settings.streamMode)
        assertEquals("192.168.1.100", settings.receivers.single().host)
    }

    @Test
    fun choosingTheOldModeIsKept() {
        val saved = openWebifJson.encodeToString(AppSettings(streamMode = StreamMode.FOLLOW_RECEIVER))
        assertEquals(StreamMode.FOLLOW_RECEIVER, openWebifJson.decodeFromString<AppSettings>(saved).streamMode)
    }

    @Test
    fun onlyFollowModeDrivesTheReceiver() {
        val own = StreamMode.OWN_TUNER
        val follow = StreamMode.FOLLOW_RECEIVER
        assertFalse(StreamModePolicy.zapsReceiver(own))
        assertFalse(StreamModePolicy.asksToWakeReceiver(own, inStandby = true))
        assertFalse(StreamModePolicy.stopsStreamOnStandby(own))
        assertFalse(StreamModePolicy.mirrorsReceiverOsd(own))
        assertFalse(StreamModePolicy.forwardsRemoteKeys(own))
        assertTrue(StreamModePolicy.checksTuners(own))
        assertTrue(StreamModePolicy.putsReceiverInStandby(own))
        assertFalse(StreamModePolicy.putsReceiverInStandby(follow))
        assertTrue(StreamModePolicy.zapsReceiver(follow))
        assertTrue(StreamModePolicy.asksToWakeReceiver(follow, inStandby = true))
        assertFalse(StreamModePolicy.asksToWakeReceiver(follow, inStandby = false))
        assertTrue(StreamModePolicy.stopsStreamOnStandby(follow))
        assertTrue(StreamModePolicy.forwardsRemoteKeys(follow))
        assertFalse(StreamModePolicy.checksTuners(follow))
    }
}
