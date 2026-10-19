package com.enigmacast.ui.setup

import android.content.Context
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
import com.enigmacast.domain.setup.SetupConditions
import com.enigmacast.domain.setup.SetupFlow
import com.enigmacast.domain.setup.SetupStep
import com.enigmacast.util.EnigmaLog
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
    private val activity: com.enigmacast.activity.BackgroundActivityTracker,
    private val iptvPlaylist: com.enigmacast.data.xtream.IptvPlaylistSync,
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

    /** The sync server found on the receiver; null while searching or when there is none. */
    var syncHost by mutableStateOf<String?>(null)
        private set
    var syncSearching by mutableStateOf(false)
        private set
    /** Pairing is running (Pair was pressed). */
    var syncLinking by mutableStateOf(false)
        private set
    /** The last Pair failed; the wizard shows a plain message, the reason goes to the log. */
    var syncFailed by mutableStateOf(false)
        private set
    var syncLinked by mutableStateOf(false)
        private set
    /** Paired before this wizard run: no sync step, the IPTV offer is still asked. */
    private var prePaired = false
    private var syncSearchStarted = false

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

    /** A server IPTV login waiting for a yes or no. Null hides that wizard step. */
    var iptvOffer by mutableStateOf<RemoteIptvOffer?>(null)
        private set
    private var iptvAnswered by mutableStateOf(false)
    var iptvFailed by mutableStateOf(false)
        private set
    var iptvBusy by mutableStateOf(false)
        private set
    private var iptvOfferReady = false

    /** Leaving the receiver step: the receiver is being saved and the sync check starts. */
    var leavingReceiver by mutableStateOf(false)
        private set

    /** What decides the conditional steps right now. */
    fun conditions(): SetupConditions = SetupConditions(
        syncOffered = !prePaired && (syncSearching || syncHost != null),
        iptvOffered = iptvOffer != null && !iptvAnswered,
        paired = syncPaired(),
        hasReceiver = receiver.value != null,
    )

    fun position(): Pair<Int, Int>? = SetupFlow.position(step, conditions())
    fun mainSteps(): List<SetupStep> = SetupFlow.steps(conditions())
    fun extras(): List<SetupStep> = SetupFlow.extras(conditions())
    fun hasPrevious(): Boolean = SetupFlow.previous(step, conditions()) != null

    fun canGoNext(): Boolean = when (step) {
        SetupStep.ACCOUNT -> accountState.value != null
        SetupStep.RECEIVER -> receiver.value != null && !leavingReceiver
        SetupStep.SYNC_LINK -> syncLinked
        SetupStep.PROFILE -> profileName.isNotBlank() && (creatingProfile || namedProfiles(profiles.state.value).isEmpty())
        SetupStep.IPTV_OFFER -> false
        else -> true
    }

    /** Skip is offered on optional steps until there is nothing left to skip. */
    fun canSkip(): Boolean = step.optional && when (step) {
        SetupStep.ACCOUNT -> accountState.value == null
        SetupStep.SYNC_LINK -> !syncLinked && !syncLinking
        SetupStep.IPTV_OFFER -> !iptvBusy
        else -> true
    }

    fun next() {
        when (step) {
            SetupStep.DONE -> return
            SetupStep.RECEIVER -> leaveReceiver()
            SetupStep.SYNC_LINK -> leaveSyncLink()
            SetupStep.PROFILE -> viewModelScope.launch {
                saveProfile()
                go(forward(SetupStep.PROFILE))
            }
            SetupStep.BOUQUETS -> {
                saveBouquets()
                go(forward(step))
            }
            else -> go(forward(step))
        }
    }

    /** Optional steps: leave what is there and go on. */
    fun skip() {
        when (step) {
            SetupStep.SYNC_LINK -> stayStandalone()
            SetupStep.IPTV_OFFER -> leaveIptv()
            SetupStep.PROFILE -> {
                profiles.confirmActive()
                go(forward(step))
            }
            else -> go(forward(step))
        }
    }

    /** False on the first step: there is nothing to go back to. */
    fun back(): Boolean {
        if (step == SetupStep.PROFILE && creatingProfile && namedProfiles(profiles.state.value).isNotEmpty()) {
            creatingProfile = false
            return true
        }
        val previous = SetupFlow.previous(step, conditions()) ?: return false
        go(previous)
        return true
    }

    /** Done › one of the optional extras (name, channel lists, football). */
    fun openExtra(extra: SetupStep) {
        if (extra.extra) go(extra)
    }

    /** Paired: the profile is chosen after the wizard, on "Who's watching?". */
    private fun syncPaired(): Boolean = syncLinked || syncClient.config() != null

    private fun forward(from: SetupStep): SetupStep = SetupFlow.next(from, conditions())

    /**
     * Receiver connected: check the receiver for a sync server (quick, off the main thread),
     * so the sync step only shows when there is one.
     */
    private fun leaveReceiver() {
        if (leavingReceiver) return
        leavingReceiver = true
        viewModelScope.launch {
            try {
                val host = settings.current().activeReceiver()?.host
                searchSync(host)
                if (prePaired) ensureIptvOffer()
                if (step == SetupStep.RECEIVER) go(forward(SetupStep.RECEIVER))
            } finally {
                leavingReceiver = false
            }
        }
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
        iptvOffer = runCatching { syncEngine.peekPlaylist() }
            .onFailure { EnigmaLog.service("setup iptv offer failed: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    fun takeIptv() {
        val offer = iptvOffer ?: return
        if (iptvBusy) return
        iptvFailed = false
        iptvBusy = true
        viewModelScope.launch {
            val result = runCatching { syncEngine.takePlaylist(offer) }
            iptvBusy = false
            if (result.isFailure) {
                EnigmaLog.service("setup iptv take failed: ${result.exceptionOrNull()?.javaClass?.simpleName}")
                iptvFailed = true
                return@launch
            }
            iptvAnswered = true
            go(SetupFlow.next(SetupStep.IPTV_OFFER, conditions()))
        }
    }

    fun leaveIptv() {
        iptvOffer?.let { syncEngine.declinePlaylist(it.changeId) }
        iptvAnswered = true
        go(SetupFlow.next(SetupStep.IPTV_OFFER, conditions()))
    }

    private fun go(target: SetupStep) {
        step = target
        stepToken++
        when (target) {
            SetupStep.PROFILE -> loadProfile()
            SetupStep.BOUQUETS -> loadBouquets()
            else -> Unit
        }
    }

    fun setMode(mode: StreamMode) {
        viewModelScope.launch { settings.update { it.copy(streamMode = mode) } }
    }

    /** Asks the receiver whether it runs the sync server. A search that ends empty while its step is shown moves on. */
    private fun searchSync(receiverHost: String?) {
        if (syncSearchStarted) return
        syncSearchStarted = true
        val config = syncClient.config()
        if (config != null) {
            prePaired = true
            syncLinked = true
            viewModelScope.launch { runCatching { syncEngine.importProfiles() } }
            return
        }
        syncSearching = true
        viewModelScope.launch {
            val host = runCatching { discovery.onReceiver(receiverHost) }.getOrNull()
            syncHost = host
            syncSearching = false
            if (host == null && step == SetupStep.SYNC_LINK) go(forward(SetupStep.SYNC_LINK))
        }
    }

    fun linkSync() {
        val host = syncHost ?: return
        if (syncLinking || syncSearching) return
        syncLinking = true
        syncFailed = false
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pin = syncClient.capturePin(host)
                    val paired = JSONObject(syncClient.join(host, pin, "EnigmaCast"))
                    syncClient.save(host, pin, paired.getString("token"), paired.getString("deviceId"), paired.getString("role"))
                    syncEngine.importProfiles()
                }
            }
            syncLinking = false
            result.fold(
                onSuccess = {
                    syncLinked = true
                    leaveSyncLink()
                },
                onFailure = {
                    EnigmaLog.service("setup sync pair failed: ${it.javaClass.simpleName} ${it.message.orEmpty()}")
                    syncFailed = true
                },
            )
        }
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
            val playlist = current.iptvSource == com.enigmacast.domain.model.IptvSourceMode.PLAYLIST
            if (playlist) {
                syncEngine.armIptvLoad()
            }
            // The status window shows this first full sync, and only this one. Home films are not
            // listed: they run straight after the guide (when a film key is set) and keep it open.
            val paired = withContext(Dispatchers.IO) { syncClient.config() != null }
            val expected = buildSet {
                add(com.enigmacast.activity.ActivityKind.CHANNELS)
                add(com.enigmacast.activity.ActivityKind.GUIDE)
                if (playlist && iptvPlaylist.hasLogin(current.iptvPlaylistKind)) add(com.enigmacast.activity.ActivityKind.IPTV)
                if (paired) add(com.enigmacast.activity.ActivityKind.SYNC_SERVER)
            }
            activity.beginInitialSetup(expected)
            SyncScheduler.runNow(context, force = true)
        }
        step = SetupStep.WELCOME
        profileLoaded = false
        bouquetsFor = null
        syncSearchStarted = false
        syncHost = null
        syncFailed = false
        prePaired = false
        iptvOfferReady = false
        iptvOffer = null
        iptvAnswered = false
        iptvFailed = false
    }
}
