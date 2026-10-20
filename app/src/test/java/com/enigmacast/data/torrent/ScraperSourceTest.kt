package com.enigmacast.data.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScraperSourceTest {
    @Test
    fun torrentIsOnExactlyWhenAUrlIsSaved() {
        assertEquals(ScraperSource.CUSTOM, ScraperSource.of(hasUrl = true))
        assertEquals(ScraperSource.NONE, ScraperSource.of(hasUrl = false))
        // No preset any more: nothing but NONE and the user's own URL.
        assertEquals(listOf("NONE", "CUSTOM"), ScraperSource.entries.map { it.name })
    }

    @Test
    fun noAddonIsNamedInTheStrings() {
        val files = File("src/main/res").listFiles { f -> f.name == "values" || f.name.startsWith("values-") }.orEmpty()
            .map { File(it, "strings.xml") }.filter { it.exists() }
        assertTrue(files.size >= 5)
        files.forEach { assertFalse(it.path, it.readText().contains("torrentio", ignoreCase = true)) }
    }
}
