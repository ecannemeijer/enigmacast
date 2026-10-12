package com.enigmacast.domain.resume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumePolicyTest {
    @Test fun offersOnlyMeaningfulPositions() {
        assertNull(ResumePolicy.offer(null))
        assertNull(ResumePolicy.offer(ResumePoint("k", 30_000, 3_600_000)))
        assertEquals(600_000L, ResumePolicy.offer(ResumePoint("k", 600_000, 3_600_000)))
        assertNull(ResumePolicy.offer(ResumePoint("k", 3_550_000, 3_600_000)))
    }

    @Test fun fractionAndClock() {
        assertEquals(0.5f, ResumePolicy.fraction(ResumePoint("k", 1_800_000, 0), 3_600_000), 0.001f)
        assertEquals("12:05", ResumePolicy.clock(725_000))
        assertEquals("1:02:03", ResumePolicy.clock(3_723_000))
    }
}
