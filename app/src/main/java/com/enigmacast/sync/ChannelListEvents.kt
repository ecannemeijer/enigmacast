package com.enigmacast.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A receiver's bouquets or channels were replaced in the cache while the app runs (settings
 * import, "Reload settings"). Screens that hold lists in memory reload them for the active box;
 * other boxes read the fresh cache when they become active.
 */
@Singleton
class ChannelListEvents @Inject constructor() {
    private val _changed = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** The receiver id whose lists changed. */
    val changed: SharedFlow<String> = _changed.asSharedFlow()

    fun notifyChanged(receiverId: String) { _changed.tryEmit(receiverId) }
}
