package com.enigmacast.domain.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureTargetTest {
    @Test fun transcodedFirstThenStreamPortThenOriginal() {
        assertEquals(listOf("t:8002", "t:8001", "o:8001"), CaptureTarget.order("t:8002", "t:8001", "o:8001"))
        assertEquals(listOf("o:8001"), CaptureTarget.order("o:8001", null, null))
        assertEquals(listOf("t:8001", "o:8001"), CaptureTarget.order("t:8001", "t:8001", "o:8001"))
    }
}
