package com.enigmacast.data.channel

import com.enigmacast.domain.channel.ChannelAlternative
import com.enigmacast.domain.channel.ChannelSource
import com.enigmacast.domain.channel.ChannelSwitchRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class EquivalentChannelsState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val currentName: String = "",
    val channels: List<ChannelAlternative> = emptyList(),
    val message: String = "",
)

/**
 * Loads and shows the matching channels for the live picture. Kept outside the player
 * ViewModels so the Enigma2 and IPTV players share exactly the same list and switch path.
 */
@Singleton
class EquivalentChannelsController @Inject constructor(
    private val repository: ChannelAlternativesRepository,
    private val bus: ChannelSwitchBus,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    var state by mutableStateOf(EquivalentChannelsState())
        private set

    fun open(
        name: String,
        receiverId: String?,
        account: com.enigmacast.domain.xtream.XtreamAccount?,
        playlistMode: Boolean,
    ) {
        if (state.open) return
        job?.cancel()
        state = EquivalentChannelsState(open = true, loading = true, currentName = name)
        job = scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { repository.find(name, receiverId, account, playlistMode) }
            }
            val list = found.getOrDefault(emptyList())
            state = if (found.isFailure) {
                EquivalentChannelsState(open = true, currentName = name, message = "Kanaal zoeken mislukt.")
            } else {
                EquivalentChannelsState(
                    open = true,
                    currentName = name,
                    channels = list,
                    message = if (list.isEmpty()) "Geen equivalent kanaal gevonden." else "",
                )
            }
        }
    }

    fun choose(channel: ChannelAlternative) {
        when (channel.source) {
            ChannelSource.ENIGMA2 -> bus.publish(ChannelSwitchRequest.Enigma(channel.bouquet))
            ChannelSource.IPTV -> channel.item?.let {
                bus.publish(ChannelSwitchRequest.Iptv(it, channel.account))
            }
        }
        close()
    }

    fun close() {
        job?.cancel()
        job = null
        state = EquivalentChannelsState()
    }
}
