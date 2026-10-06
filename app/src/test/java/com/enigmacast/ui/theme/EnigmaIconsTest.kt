package com.enigmacast.ui.theme

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import org.junit.Assert.assertTrue
import org.junit.Test

class EnigmaIconsTest {
    @Test
    fun everyIconPathParsesAndCloses() {
        listOf(
            EnigmaIcons.POWER_PATH,
            EnigmaIcons.BEDTIME_PATH,
            EnigmaIcons.RESTART_PATH,
            EnigmaIcons.TIMER_PATH,
            EnigmaIcons.TV_PATH,
            EnigmaIcons.LAYERS_PATH,
            EnigmaIcons.PALETTE_PATH,
            EnigmaIcons.LANGUAGE_PATH,
            EnigmaIcons.SUBTITLES_PATH,
            EnigmaIcons.AUDIOTRACK_PATH,
            EnigmaIcons.ASPECT_RATIO_PATH,
            EnigmaIcons.SYNC_PATH,
            EnigmaIcons.GUIDE_PATH,
            EnigmaIcons.HISTORY_PATH,
            EnigmaIcons.RECORD_PATH,
            EnigmaIcons.SPEED_PATH,
            EnigmaIcons.LOCK_PATH,
        ).forEach { path ->
            val nodes = addPathNodes(path)
            assertTrue(path, nodes.size > 3)
            assertTrue(path, nodes.last() is PathNode.Close)
        }
    }
}
