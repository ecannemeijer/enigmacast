package com.enigmacast.domain.soccer

import com.enigmacast.data.soccer.SoccerSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegionLoadsTest {
    /** The device bug: only the region the page opened with was ever fetched. */
    @Test
    fun everyChosenRegionIsLoaded() = runTest {
        val region = MutableStateFlow(SoccerCountries.byId("NL"))
        val loaded = mutableListOf<List<String>>()
        val job = launch { RegionLoads.follow(region) { loaded += it } }
        runCurrent()
        SoccerCountries.ALL.drop(1).forEach { region.value = it; runCurrent() }
        job.cancel()
        assertEquals(SoccerCountries.ALL.map { it.competitions }, loaded)
    }

    @Test
    fun aQuickSwitchCancelsTheOldRegionsQueue() = runTest {
        val region = MutableStateFlow(SoccerCountries.byId("NL"))
        val started = mutableListOf<String>()
        val finished = mutableListOf<String>()
        val job = launch {
            RegionLoads.follow(region) { codes ->
                for (code in codes) {
                    started += code
                    delay(6_000) // waiting for the request window
                    finished += code
                }
            }
        }
        runCurrent()
        region.value = SoccerCountries.byId("ENG")
        runCurrent()
        region.value = SoccerCountries.byId("BR")
        advanceUntilIdle()
        job.cancel()
        assertEquals(listOf("DED", "PL", "BSA"), started.take(3))
        assertEquals(listOf("BSA", "CLI"), finished)
    }

    @Test
    fun sameCompetitionsAgainDoNotRefetch() = runTest {
        val region = MutableStateFlow(SoccerCountries.byId("EU"))
        var loads = 0
        val job = launch { RegionLoads.follow(region) { loads++ } }
        runCurrent()
        region.value = SoccerCountries.byId("EU").copy()
        runCurrent()
        job.cancel()
        assertEquals(1, loads)
    }

    @Test
    fun resolveNarrowsToThePlanAndKeepsUnknownHarmless() {
        assertEquals(listOf("PL"), SoccerCountries.resolve("ENG", setOf("PL", "DED")).competitions)
        assertEquals(listOf("PL", "ELC"), SoccerCountries.resolve("ENG", null).competitions)
        assertEquals("NL", SoccerCountries.resolve("XX", null).id)
    }

    /** The old "followed country" value becomes the last region; junk falls back to the locale default. */
    @Test
    fun storedRegionMigratesHarmlessly() {
        assertEquals("BR", SoccerSettings.regionOf("BR", "NL"))
        assertEquals("DE", SoccerSettings.regionOf(null, "AT"))
        assertEquals("ENG", SoccerSettings.regionOf("bogus", "GB"))
        assertEquals("NL", SoccerSettings.regionOf("", null))
    }
}
