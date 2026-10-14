package com.enigmacast.domain.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupChannelCheckTest {
    private val cached = listOf("ref1" to "NL", "ref2" to "Sport")

    @Test fun sameListMeansNoChannelFetch() = assertFalse(StartupChannelCheck.bouquetsChanged(cached, cached.toList()))
    @Test fun renamedAddedOrReordered() {
        assertTrue(StartupChannelCheck.bouquetsChanged(cached, listOf("ref1" to "NL", "ref2" to "Sports")))
        assertTrue(StartupChannelCheck.bouquetsChanged(cached, cached + ("ref3" to "Films")))
        assertTrue(StartupChannelCheck.bouquetsChanged(cached, cached.reversed()))
    }
    @Test fun emptyAnswerKeepsCache() = assertFalse(StartupChannelCheck.bouquetsChanged(cached, emptyList()))
}
