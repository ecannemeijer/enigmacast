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
import com.enigmacast.domain.profile.ProfileState
import com.enigmacast.data.enigmasync.EnigmaSyncClient
import com.enigmacast.data.enigmasync.EnigmaSyncEngine
import com.enigmacast.data.enigmasync.RemoteIptvOffer
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
    /** True while the profile step is asking the sync server for existing profiles. */
    var profilesLoading by mutableStateOf(false)
        private set
    /** The name form, after the viewer chose to make a new profile. */
    var creatingProfile by mutableStateOf(false)
        private set
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
        SetupStep.PROFILE -> profileName.isNotBlank() && (creatingProfile || namedProfiles(profiles.state.value).isEmpty())
        SetupStep.IPTV_OFFER -> false
        else -> true
    }

    /** A server IPTV login waiting for a yes or no. Null hides that wizard step. */
    var iptvOffer by mutableStateOf<RemoteIptvOffer?>(null)
        private set
    var iptvMessage by mutableStateOf("")
        private set
    private var iptvOfferReady = false

    fun next() {
        if (step == SetupStep.DONE) return
        if (step == SetupStep.SYNC_LINK) {
            leaveSyncLink()
            return
        }
        if (step == SetupStep.PROFILE) {
            viewModelScope.launch {
                saveProfile()
                go(forward(SetupStep.PROFILE))
            }
            return
        }
        if (step == SetupStep.BOUQUETS) saveBouquets()
        go(forward(step))
    }

    /** Optional steps: leave what is there and go on. */
    fun skip() {
        if (step == SetupStep.PROFILE) profiles.confirmActive()
        go(forward(step))
    }

    /** False on the first step: there is nothing to go back to. */
    fun back(): Boolean {
        if (step == SetupStep.PROFILE && creatingProfile && namedProfiles(profiles.state.value).isNotEmpty()) {
            creatingProfile = false
            return true
        }
        val previous = backward(step) ?: return false
        go(previous)
        return true
    }

    /** Paired: the profile is chosen after the wizard, on "Who's watching?". */
    private fun syncPaired(): Boolean = syncLinked || syncClient.config() != null

    private fun omitted(target: SetupStep): Boolean = when (target) {
        SetupStep.PROFILE -> syncPaired()
        SetupStep.IPTV_OFFER -> iptvOffer == null
        else -> false
    }

    private fun forward(from: SetupStep): SetupStep {
        var target = SetupStep.next(from)
        while (omitted(target) && target != SetupStep.DONE) target = SetupStep.next(target)
        return target
    }

    private fun backward(from: SetupStep): SetupStep? {
        var target = SetupStep.previous(from)
        while (target != null && omitted(target)) target = SetupStep.previous(target)
        return target
    }

    private fun leaveSyncLink() {
        if (!syncPaired()) {
            go(forward(SetupStep.SYNC_LINK))
            return
        }
        viewModelScope.launch {
            ensureIptvOffer()
            if (step == SetupStep.SYNC_LINK) go(forward(SetupStep.SYNC_LINK))
        }
    }

    private suspend fun ensureIptvOffer() {
        if (iptvOfferReady) return
        iptvOfferReady = true
        iptvOffer = runCatching { syncEngine.peekPlaylist() }.getOrNull()
    }

    fun takeIptv() {
        val offer = iptvOffer ?: return
        iptvMessage = ""
        viewModelScope.launch {
            val result = runCatching { syncEngine.takePlaylist(offer) }
            if (result.isFailure) {
                iptvMessage = result.exceptionOrNull()?.message.orEmpty()
                return@launch
            }
            go(forward(SetupStep.IPTV_OFFER))
        }
    }

    fun leaveIptv() {
        iptvOffer?.let { syncEngine.declinePlaylist(it.changeId) }
        go(forward(SetupStep.IPTV_OFFER))
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
            val host = runCatching { discovery.onReceiver(receiver.value?.host) }.getOrNull()
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
                    leaveSyncLink()
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
        leaveSyncLink()
    }

    fun openDashboard(context: Context) {
        val url = dashboardUrl
        if (url.isBlank()) return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { syncMessage = it.message.orEmpty() }
    }

    fun stayStandalone() {
        leaveSyncLink()
    }

    val profileState = profiles.state

    /** Named profiles already on this device. Empty on a first run that has only the blank default. */
    fun namedProfiles(state: ProfileState): List<com.enigmacast.domain.profile.Profile> =
        state.profiles.filter { it.name.isNotBlank() }

    fun startNewProfile() {
        creatingProfile = true
        profileName = ""
        avatar = 0
    }

    fun chooseProfile(id: String) {
        viewModelScope.launch {
            profiles.switchTo(id)
            val active = profiles.state.value.active
            profileName = active.name
            avatar = active.avatar
            go(forward(step))
        }
    }

    private fun loadProfile() {
        creatingProfile = false
        if (!profileLoaded) {
            profileLoaded = true
            val state = profiles.state.value
            val active = state.profiles.firstOrNull { it.id == state.activeId }
            // A first run has only the unnamed default profile; suggest nothing so the name is the user's own.
            profileName = if (state.chosenOnce) active?.name.orEmpty() else ""
            avatar = active?.avatar ?: 0
        }
        if (syncClient.config() == null) {
            profilesLoading = false
            return
        }
        profilesLoading = true
        viewModelScope.launch {
            runCatching { syncEngine.importProfiles() }
            profilesLoading = false
        }
    }

    private suspend fun saveProfile() {
        val state = profiles.state.value
        val named = namedProfiles(state)
        if (named.isEmpty()) {
            if (state.profiles.any { it.id == state.activeId }) profiles.edit(state.activeId, profileName.trim(), avatar)
            else profiles.add(profileName.trim(), avatar)
            profiles.confirmActive()
            return
        }
        val id = profiles.add(profileName.trim(), avatar)
        profiles.switchTo(id)
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

    /** Done: the first full sync of guide, channels and Home starts now. IPTV waits until that sync has finished. */
    fun finish() {
        profiles.confirmActive()
        viewModelScope.launch {
            settings.update { it.copy(setupCompleted = true) }
            val current = settings.current()
            if (current.iptvSource == com.enigmacast.domain.model.IptvSourceMode.PLAYLIST) {
                syncEngine.armIptvLoad()
            }
            SyncScheduler.runNow(context, force = true)
        }
        step = SetupStep.WELCOME
        profileLoaded = false
        bouquetsFor = null
    }
}
