package com.enigmacast.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test
    fun offersOnlyAHigherVersionCode() {
        val same = RemoteRelease(versionCode = 1, versionName = "1.0.0-alpha")
        val newer = RemoteRelease(versionCode = 2, versionName = "1.0.0-alpha.2", notes = "Fixes")
        assertFalse(isNewerRelease(1, same))
        assertTrue(isNewerRelease(1, newer))
    }

    @Test
    fun reinstallFlagAndMessages() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<RemoteRelease>("""{"versionCode":30,"notes":"x"}""")
        assertFalse(old.reinstallRequired)
        val release = json.decodeFromString<RemoteRelease>(
            """{"versionCode":30,"reinstallRequired":true,"reinstallMessage":"EN text","reinstallMessageNl":"NL tekst"}""",
        )
        assertTrue(release.reinstallRequired)
        org.junit.Assert.assertEquals("NL tekst", release.reinstallText("nl"))
        org.junit.Assert.assertEquals("EN text", release.reinstallText("en"))
        org.junit.Assert.assertEquals("EN text", release.copy(reinstallMessageNl = "").reinstallText("nl"))
    }
}
