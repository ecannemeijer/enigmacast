package com.enigmacast.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.ChannelGuide
import com.enigmacast.domain.source.ServiceStateSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StreamGuideViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val serviceSource: ServiceStateSource,
) : ViewModel() {
    var guide by mutableStateOf<ChannelGuide?>(null)
        private set
    var status by mutableStateOf("")
        private set

    fun refresh() {
        viewModelScope.launch { loadGuide() }
    }

    fun refreshIfServiceChanged() {
        viewModelScope.launch {
            val receiver = settingsRepository.current().activeReceiver() ?: return@launch
            val reference = runCatching { serviceSource.currentService(receiver).reference }.getOrNull()
            if (!reference.isNullOrBlank() && reference != guide?.serviceReference) {
                loadGuide()
            }
        }
    }

    private suspend fun loadGuide() {
        val receiver = settingsRepository.current().activeReceiver()
        if (receiver == null) {
            status = "Geen ontvanger"
            return
        }
        runCatching { serviceSource.channelGuide(receiver) }
            .onSuccess { loaded ->
                guide = loaded
                status = ""
            }
            .onFailure { error ->
                status = error.message ?: "EPG niet beschikbaar"
            }
    }
}
