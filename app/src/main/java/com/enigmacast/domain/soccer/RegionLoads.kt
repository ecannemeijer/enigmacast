package com.enigmacast.domain.soccer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Loads the competitions of every region that is chosen, not just the first one: each new set of
 * [SoccerCountries.Country.competitions] starts [load] at once, and a quicker switch cancels the
 * previous region's queue so the 10 requests/minute go to the region on screen. (Before this the
 * page fetched only the region it opened with; any other region waited for the hourly refresh.)
 */
object RegionLoads {
    suspend fun follow(regions: Flow<SoccerCountries.Country>, load: suspend (List<String>) -> Unit) {
        regions.map { it.competitions }.distinctUntilChanged().collectLatest { codes -> load(codes) }
    }
}
