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
        ).forEach { path ->
            val nodes = addPathNodes(path)
            assertTrue(path, nodes.size > 3)
            assertTrue(path, nodes.last() is PathNode.Close)
        }
    }
}
