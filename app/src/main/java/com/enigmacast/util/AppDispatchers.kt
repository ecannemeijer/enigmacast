package com.enigmacast.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where blocking IO ([io]) and CPU work such as JSON parsing and name matching ([default])
 * run, so repositories stay main-safe. Tests pass their own dispatchers.
 */
@Singleton
class AppDispatchers(val io: CoroutineDispatcher, val default: CoroutineDispatcher) {
    @Inject constructor() : this(Dispatchers.IO, Dispatchers.Default)
}
