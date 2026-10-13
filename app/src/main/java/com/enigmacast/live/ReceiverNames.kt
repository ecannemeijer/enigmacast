package com.enigmacast.live

import com.enigmacast.data.openwebif.OpenWebifApi
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.ReceiverLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The receiver's own brand and model ("Octagon SF8008", from `/api/about`) per box, read once
 * per box and kept for the session. Falls back to the name in Settings until it is known.
 */
@Singleton
class ReceiverNames @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val api: OpenWebifApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val known = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Brand and model per receiver id, as far as known. */
    val labels: StateFlow<Map<String, String>> = known
    private val asked = mutableSetOf<String>()

    /** Label of the active box; empty when there is none. */
    val active: StateFlow<String> = combine(settingsRepository.settings.map { it.activeReceiver() }.distinctUntilChanged(), known) { receiver, map ->
        if (receiver == null) return@combine ""
        ensure(receiver)
        map[receiver.id] ?: receiver.name.trim()
    }.stateIn(scope, SharingStarted.Eagerly, "")

    fun ensure(receiver: com.enigmacast.domain.model.ReceiverConfig) {
        synchronized(asked) { if (!asked.add(receiver.id)) return }
        scope.launch {
            val caps = runCatching { api.about(receiver) }.getOrNull()
            val label = caps?.let { ReceiverLabel.of(it.brand, it.model) }.orEmpty()
            if (label.isNotBlank()) known.value = known.value + (receiver.id to label)
            else synchronized(asked) { asked.remove(receiver.id) }
        }
    }
}
