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
        // A title stopped after seconds still shows its (small) bar.
        assertEquals(10_000f / 3_600_000f, ResumePolicy.fraction(ResumePoint("k", 10_000, 3_600_000), 0), 0.0001f)
        assertEquals(0f, ResumePolicy.fraction(ResumePoint("k", 0, 3_600_000), 0), 0f)
        assertEquals("12:05", ResumePolicy.clock(725_000))
        assertEquals("1:02:03", ResumePolicy.clock(3_723_000))
    }
}
