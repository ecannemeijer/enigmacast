package com.enigmacast.ui.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.BuildConfig
import com.enigmacast.data.access.RemoteAccessRepository
import com.enigmacast.data.account.AccountRepository
import com.enigmacast.data.telemetry.TelemetryRepository
import com.enigmacast.domain.access.AccessConfig
import com.enigmacast.domain.access.AccessMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AccessUiState(
    val loading: Boolean = true,
    val mode: AccessMode = AccessMode.FREE,
    val needsLogin: Boolean = false,
    val updateRequired: Boolean = false,
    val minVersionName: String = "",
    val updateUrl: String = "https://update.velixatv.com/enigmacast.apk",
    val message: String = "",
)

/** Decides which screen must be in front: the app, a login gate or the update-required gate. */
@HiltViewModel
class AccessViewModel @Inject constructor(
    private val access: RemoteAccessRepository,
    private val account: AccountRepository,
    private val telemetry: TelemetryRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AccessUiState())
    val state: StateFlow<AccessUiState> = _state.asStateFlow()

    init {
        telemetry.start()
        viewModelScope.launch {
            access.refresh()
            recompute()
        }
        viewModelScope.launch {
            account.account.collect { recompute() }
        }
    }

    fun retry() {
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            access.refresh()
            recompute()
        }
    }

    private fun recompute() {
        val config = access.current()
        val update = config.blocksThisVersion && config.minVersionCode > BuildConfig.VERSION_CODE
        _state.value = AccessUiState(
            loading = false,
            mode = config.mode,
            needsLogin = !update && config.mode == AccessMode.PREMIUM && !account.isSignedIn(),
            updateRequired = update,
            minVersionName = config.minVersionName,
            updateUrl = config.updateUrl,
            message = config.message,
        )
    }
}
