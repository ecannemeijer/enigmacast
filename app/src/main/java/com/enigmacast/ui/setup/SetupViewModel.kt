package com.enigmacast.ui.setup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.data.openwebif.OpenWebifApi
import com.enigmacast.data.profile.ProfileStore
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.tmdb.HomeBouquetStore
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.model.StreamMode
import com.enigmacast.data.enigmasync.EnigmaSyncClient
import com.enigmacast.data.enigmasync.EnigmaSyncEngine
import com.enigmacast.data.enigmasync.SyncDiscovery
import com.enigmacast.data.enigmasync.SyncServerInstaller
import com.enigmacast.domain.setup.SetupStep
import com.enigmacast.live.HomeFilmsRepository
import com.enigmacast.live.ReceiverNames
import com.enigmacast.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val profiles: ProfileStore,
    private val api: OpenWebifApi,
    private val homeFilms: HomeFilmsRepository,
    private val bouquetStore: HomeBouquetStore,
    private val account: com.enigmacast.data.account.AccountRepository,
    private val discovery: SyncDiscovery,
    private val syncClient: EnigmaSyncClient,
    private val syncEngine: EnigmaSyncEngine,
    private val installer: SyncServerInstaller,
    names: ReceiverNames,
) : ViewModel() {
    var step by mutableStateOf(SetupStep.WELCOME)
        private set

    /** Bumped on every step change, so the new step's first control takes the focus. */
    var stepToken by mutableIntStateOf(0)
        private set

    val receiver: StateFlow<ReceiverConfig?> = settings.settings.map { it.activeReceiver() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val streamMode: StateFlow<StreamMode> = settings.settings.map { it.streamMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamMode.OWN_TUNER)
    val receiverName: StateFlow<String> = names.active
    val accountState = account.account

    var syncHost by mutableStateOf<String?>(null)
        private set
    var syncSearching by mutableStateOf(false)
        private set
    var syncMessage by mutableStateOf("")
        private set
    var syncLinked by mutableStateOf(false)
    var dashboardUrl by mutableStateOf("")
    var dashboardPassword by mutableStateOf("")
        private set
    private var syncSearchStarted = false

    var sshUser by mutableStateOf("root")
    var sshPassword by mutableStateOf("")

    var profileName by mutableStateOf("")
    var avatar by mutableIntStateOf(0)
    private var profileLoaded = false

    var bouquets by mutableStateOf<List<BouquetEntry>>(emptyList())
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set
    var bouquetsLoading by mutableStateOf(false)
        private set
    var bouquetsFailed by mutableStateOf(false)
        private set
    private var bouquetsFor: String? = null

    fun canGoNext(): Boolean = when (step) {
        SetupStep.ACCOUNT -> accountState.value != null
        SetupStep.RECEIVER -> receiver.value != null
        SetupStep.SYNC_LINK -> syncLinked || syncClient.config() != null
        SetupStep.PROFILE -> profileName.isNotBlank()
        else -> true
    }

    fun next() {
        if (step == SetupStep.DONE) return
        when (step) {
            SetupStep.PROFILE -> saveProfile()
            SetupStep.BOUQUETS -> saveBouquets()
            else -> Unit
        }
        go(SetupStep.next(step))
    }

    /** Optional steps: leave what is there and go on. */
    fun skip() {
        if (step == SetupStep.PROFILE) profiles.confirmActive()
        go(SetupStep.next(step))
    }

    /** False on the first step: there is nothing to go back to. */
    fun back(): Boolean {
        val previous = SetupStep.previous(step) ?: return false
        go(previous)
        return true
    }

    private fun go(target: SetupStep) {
        step = target
        stepToken++
        when (target) {
            SetupStep.SYNC_LINK -> searchSync()
            SetupStep.PROFILE -> loadProfile()
            SetupStep.BOUQUETS -> loadBouquets()
            else -> Unit
        }
    }

    fun setMode(mode: StreamMode) {
        viewModelScope.launch { settings.update { it.copy(streamMode = mode) } }
    }

    fun searchSync() {
        if (syncSearchStarted) return
        syncSearchStarted = true
        if (syncClient.config() != null) {
            syncLinked = true
            syncHost = syncClient.config()?.host
            viewModelScope.launch { runCatching { syncEngine.importProfiles() } }
            return
        }
        syncSearching = true
        syncMessage = ""
        viewModelScope.launch {
            val host = runCatching { discovery.find(receiver.value?.host) }.getOrNull()
            syncSearching = false
            syncHost = host
            if (host == null) syncMessage = "missing"
        }
    }

    fun linkSync() {
        val host = syncHost ?: return
        if (syncSearching) return
        syncSearching = true
        syncMessage = ""
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pin = syncClient.capturePin(host)
                    val paired = JSONObject(syncClient.join(host, pin, "EnigmaCast"))
                    syncClient.save(host, pin, paired.getString("token"), paired.getString("deviceId"), paired.getString("role"))
                    syncEngine.importProfiles()
                }
            }
            syncSearching = false
            result.fold(
                onSuccess = {
                    syncLinked = true
                    go(SetupStep.next(step))
                },
                onFailure = { syncMessage = it.message ?: it.javaClass.simpleName },
            )
        }
    }

    fun installSync() {
        val address = (syncHost ?: receiver.value?.host).orEmpty().trim()
        if (address.isBlank() || sshPassword.isBlank() || syncSearching) return
        syncSearching = true
        syncMessage = ""
        val user = sshUser.trim().ifBlank { "root" }
        val password = sshPassword
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val report = installer.install(address, user, password)
                    val paired = JSONObject(syncClient.join(address, report.pin, "EnigmaCast"))
                    syncClient.save(address, report.pin, paired.getString("token"), paired.getString("deviceId"), paired.getString("role"))
                    syncEngine.importProfiles()
                    report
                }
            }
            syncSearching = false
            sshPassword = ""
            result.fold(
                onSuccess = { report ->
                    syncHost = address
                    syncLinked = true
                    dashboardUrl = report.dashboardUrl
                    dashboardPassword = report.dashboardPassword
                },
                onFailure = { syncMessage = it.message ?: it.javaClass.simpleName },
            )
        }
    }

    fun continueAfterInstall() {
        dashboardPassword = ""
        go(SetupStep.next(step))
    }

    fun openDashboard(context: Context) {
        val url = dashboardUrl
        if (url.isBlank()) return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { syncMessage = it.message.orEmpty() }
    }

    fun stayStandalone() {
        go(SetupStep.next(step))
    }

    fun profilesForPicker() = profiles.state.value.profiles.filter { profile ->
        (syncLinked || syncClient.config() != null) && profile.name.isNotBlank()
    }

    fun chooseProfile(id: String) {
        viewModelScope.launch {
            profiles.switchTo(id)
            val active = profiles.state.value.active
            profileName = active.name
            avatar = active.avatar
            go(SetupStep.next(step))
        }
    }

    private fun loadProfile() {
        if (profileLoaded) return
        profileLoaded = true
        val state = profiles.state.value
        val active = state.profiles.firstOrNull { it.id == state.activeId }
        // A first run has only the unnamed default profile; suggest nothing so the name is the user's own.
        profileName = if (state.chosenOnce) active?.name.orEmpty() else ""
        avatar = active?.avatar ?: 0
    }

    private fun saveProfile() {
        val state = profiles.state.value
        if (state.profiles.any { it.id == state.activeId }) profiles.edit(state.activeId, profileName.trim(), avatar)
        else profiles.add(profileName.trim(), avatar)
        // The profile is chosen now; "Who's watching" does not ask again on this start.
        profiles.confirmActive()
    }

    fun loadBouquets(force: Boolean = false) {
        val config = receiver.value ?: return
        val key = homeFilms.receiverKey(config)
        if (!force && bouquetsFor == key && bouquets.isNotEmpty()) return
        bouquetsFor = key
        bouquetsLoading = true
        bouquetsFailed = false
        viewModelScope.launch {
            val list = runCatching { api.televisionBouquets(config) }.getOrNull()
            bouquetsLoading = false
            if (list == null) {
                bouquetsFailed = true
                return@launch
            }
            bouquets = list
            val stored = bouquetStore.selected(key)
            selected = stored?.intersect(list.map { it.reference }.toSet()) ?: list.map { it.reference }.toSet()
        }
    }

    fun toggle(reference: String) {
        selected = if (reference in selected) selected - reference else selected + reference
    }

    fun selectAll() { selected = bouquets.map { it.reference }.toSet() }
    fun selectNone() { selected = emptySet() }

    private fun saveBouquets() {
        val config = receiver.value ?: return
        if (bouquets.isEmpty()) return
        val all = selected.size == bouquets.size
        // Everything selected is stored as "all", so new bouquets on the receiver show up too.
        bouquetStore.set(homeFilms.receiverKey(config), if (all) null else selected)
    }

    /** Done: the first full sync of guide, channels and Home starts now. */
    fun finish() {
        profiles.confirmActive()
        viewModelScope.launch { settings.update { it.copy(setupCompleted = true) } }
        SyncScheduler.runNow(context, force = true)
        step = SetupStep.WELCOME
        profileLoaded = false
        bouquetsFor = null
    }
}
