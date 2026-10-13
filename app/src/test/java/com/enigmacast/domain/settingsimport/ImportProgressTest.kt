package com.enigmacast.domain.settingsimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportProgressTest {
    @Test fun stagesGrowMonotonically() {
        val steps = listOf(
            ImportStep(ImportStage.CONNECT),
            ImportStep(ImportStage.BACKUP, 0, 10), ImportStep(ImportStage.BACKUP, 10, 10),
            ImportStep(ImportStage.UPLOAD, 0, 4), ImportStep(ImportStage.UPLOAD, 4, 4),
            ImportStep(ImportStage.DELETE, 1, 1), ImportStep(ImportStage.RELOAD), ImportStep(ImportStage.SYNC),
        )
        steps.zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", ImportProgress.receiverFraction(a) <= ImportProgress.receiverFraction(b) + 1e-4f) }
    }

    @Test fun overallSplitsPerReceiver() {
        assertEquals(0.5f, ImportProgress.overall(1, 2, null), 0.001f)
        assertEquals(1f, ImportProgress.overall(2, 2, null), 0.001f)
        assertEquals(0f, ImportProgress.overall(0, 0, null), 0.001f)
        assertTrue(ImportProgress.overall(0, 2, ImportStep(ImportStage.SYNC)) < 0.5f)
    }

    @Test fun zeroTotalDoesNotDivide() {
        assertEquals(0.35f, ImportProgress.receiverFraction(ImportStep(ImportStage.UPLOAD, 3, 0)), 0.001f)
    }
}
