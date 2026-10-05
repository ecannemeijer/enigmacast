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
}
