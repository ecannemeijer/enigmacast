package com.enigmacast.data.channel

import com.enigmacast.domain.channel.ChannelSwitchRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One pending "play this equivalent channel" request. The app shell follows [events] to switch
 * screens; the player that owns the target source reads [request] and consumes it.
 */
@Singleton
class ChannelSwitchBus @Inject constructor() {
    private val _request = MutableStateFlow<ChannelSwitchRequest?>(null)
    val request: StateFlow<ChannelSwitchRequest?> = _request.asStateFlow()
    private val _events = MutableSharedFlow<ChannelSwitchRequest>(extraBufferCapacity = 4)
    val events: SharedFlow<ChannelSwitchRequest> = _events.asSharedFlow()

    fun publish(request: ChannelSwitchRequest) {
        _request.value = request
        _events.tryEmit(request)
    }

    fun clear(id: Long) {
        if (_request.value?.id == id) _request.value = null
    }
}
