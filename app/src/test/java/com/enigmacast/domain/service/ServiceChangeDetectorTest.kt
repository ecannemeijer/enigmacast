package com.enigmacast.domain.service

import com.enigmacast.domain.model.EnigmaService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceChangeDetectorTest {
    @Test
    fun reportsOnlyARealReferenceChange() {
        val detector = ServiceChangeDetector()
        val first = EnigmaService(name = "NPO 1", reference = "1:0:1:A:0:0:0:0:0:0:")
        val renamed = first.copy(name = "NPO 1 HD")
        val next = first.copy(name = "NPO 2", reference = "1:0:1:B:0:0:0:0:0:0:")

        assertEquals(first.reference, detector.onSample(first)?.current?.reference)
        assertNull(detector.onSample(renamed))
        assertNull(detector.onSample(EnigmaService(reference = "")))
        assertEquals(next.reference, detector.onSample(next)?.current?.reference)
    }
}
