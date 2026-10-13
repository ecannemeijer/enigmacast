package com.enigmacast.live

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Counts returns from the background; the player screen starts its stream again on each. */
@Singleton
class PlaybackLifecycle @Inject constructor() {
    private val _resumes = MutableStateFlow(0)
    val resumes: StateFlow<Int> = _resumes.asStateFlow()

    fun resumed() { _resumes.value++ }
}
