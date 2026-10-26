package com.enigmacast.ui.hotel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.data.hotel.HotelCleanup
import com.enigmacast.data.hotel.HotelStore
import com.enigmacast.data.openwebif.OpenWebifServiceStateSource
import com.enigmacast.data.profile.ProfileStore
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.xtream.IptvPlaylistStore
import com.enigmacast.data.xtream.IptvPlaylistSync
import com.enigmacast.data.xtream.IptvProvider
import com.enigmacast.data.xtream.XtreamRepository
import com.enigmacast.domain.hotel.HotelConfig
import com.enigmacast.domain.hotel.HotelFilter
import com.enigmacast.domain.hotel.HotelPin
import com.enigmacast.domain.hotel.HotelSchedule
import com.enigmacast.domain.hotel.HotelSource
import com.enigmacast.domain.hotel.HotelWelcome
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.IptvSourceMode
import com.enigmacast.domain.profile.ProfileState
import com.enigmacast.domain.security.PinCheck
import com.enigmacast.domain.xtream.IptvCategoryFilter
import com.enigmacast.domain.xtream.XtreamCategory
import com.enigmacast.domain.xtream.XtreamSection
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** The hotel PIN steps. */
enum class HotelPinStep { UNLOCK, NEW, REPEAT, CURRENT_TO_CHANGE }

/**
 * Settings › Hotel mode, and the guest's menu. Every write goes through [HotelStore], which keeps
 * hotel mode off without a PIN and the HOME alias in step.
 */
@HiltViewModel
class HotelViewModel @Inject constructor(
    private val store: HotelStore,
    private val cleanup: HotelCleanup,
    private val profileStore: ProfileStore,
    private val settings: SettingsRepository,
    private val receivers: OpenWebifServiceStateSource,
    private val playlists: IptvPlaylistStore,
    private val playlistSync: IptvPlaylistSync,
    private val xtream: XtreamRepository,
) : ViewModel() {
    val config: StateFlow<HotelConfig> = store.config
    val pinSet: StateFlow<Boolean> = store.pinSet
    val unlocked: StateFlow<Boolean> = store.unlocked
    val profiles: StateFlow<ProfileState> = profileStore.state

    // --- PIN -------------------------------------------------------------------------------------

    var pinStep by mutableStateOf<HotelPinStep?>(null)
        private set
    var pinError by mutableStateOf<String?>(null)
        private set
    var pinBusy by mutableStateOf(false)
        private set
    private var firstPin: String? = null
    /** What happens after the new PIN is saved (turning hotel mode on right away). */
    private var afterPin: (() -> Unit)? = null

    fun startPin(step: HotelPinStep, then: (() -> Unit)? = null) {
        pinStep = step
        pinError = null
        firstPin = null
        afterPin = then
    }

    fun cancelPin() {
        pinStep = null
        pinError = null
        firstPin = null
        afterPin = null
    }

    fun submitPin(pin: String, wrong: (Int) -> String, cooling: (Int) -> String, mismatch: String, invalid: String) {
        val step = pinStep ?: return
        if (!HotelPin.valid(pin)) { pinError = invalid; return }
        when (step) {
            HotelPinStep.NEW -> { firstPin = pin; pinError = null; pinStep = HotelPinStep.REPEAT; return }
            HotelPinStep.REPEAT -> {
                if (pin != firstPin) { pinError = mismatch; firstPin = null; pinStep = HotelPinStep.NEW; return }
                pinBusy = true
                viewModelScope.launch {
                    val saved = store.setPin(pin)
                    pinBusy = false
                    val then = afterPin
                    cancelPin()
                    if (saved) then?.invoke()
                }
                return
            }
            else -> Unit
        }
        pinBusy = true
        viewModelScope.launch {
            val result = if (step == HotelPinStep.UNLOCK) store.unlock(pin) else store.check(pin)
            pinBusy = false
            when (result) {
                PinCheck.Ok -> if (step == HotelPinStep.CURRENT_TO_CHANGE) startPin(HotelPinStep.NEW) else cancelPin()
                is PinCheck.Wrong -> pinError = wrong(result.attemptsLeft)
                is PinCheck.CoolingDown -> pinError = cooling(result.seconds)
            }
        }
    }

    /** Opened from the normal Settings (hotel mode off, the owner is already in). */
    fun unlockForOwner() = store.unlockForOwner()

    fun relock() = store.relock()

    // --- Settings --------------------------------------------------------------------------------

    /** On needs a PIN first; the hotel profile defaults to the profile that is active now. */
    fun setEnabled(on: Boolean) {
        if (on && !pinSet.value) {
            startPin(HotelPinStep.NEW) { setEnabled(true) }
            return
        }
        val active = profileStore.state.value.activeUuid
        store.update { config ->
            config.copy(
                enabled = on,
                profileUuid = if (on) config.profileUuid.ifBlank { active } else config.profileUuid,
            ).let {
                // Switching on after today's cleanup time does not clean the room right away.
                if (on && !config.enabled) HotelSchedule.armed(it, System.currentTimeMillis(), ZoneId.systemDefault()) else it
            }
        }
    }

    fun setSource(source: HotelSource) = store.update { it.copy(source = source) }
    fun setVod(on: Boolean) = store.update { it.copy(iptvVod = on) }
    fun setProfile(uuid: String) = store.update { it.copy(profileUuid = uuid) }
    fun setWelcome(transform: (HotelWelcome) -> HotelWelcome) = store.update { it.copy(welcome = transform(it.welcome)) }
    fun setLauncher(on: Boolean) = store.update { it.copy(launcher = on) }

    fun setCleanupDaily(on: Boolean) = store.update {
        val next = it.copy(cleanupDaily = on)
        if (on) HotelSchedule.armed(next, System.currentTimeMillis(), ZoneId.systemDefault()) else next
    }

    fun stepCleanupTime(delta: Int) = store.update {
        HotelSchedule.armed(it.copy(cleanupMinute = HotelSchedule.step(it.cleanupMinute, delta)), System.currentTimeMillis(), ZoneId.systemDefault())
    }

    fun stepVolume(delta: Int) = store.update { it.copy(startVolume = HotelVolume.step(it.startVolume, delta)) }

    /** The channel that is remembered now (satellite: the active receiver's; IPTV: [iptvAccountKey]'s). */
    fun useCurrentChannel(iptvAccountKey: String?) = viewModelScope.launch {
        val now = settings.current()
        val receiver = now.activeReceiver()?.id.orEmpty()
        store.update { config ->
            if (config.iptv) {
                val last = iptvAccountKey?.let { now.lastIptvChannels[it] } ?: now.lastIptvChannels.values.firstOrNull()
                config.copy(startIptv = last)
            } else {
                val last = now.lastChannels[receiver] ?: now.lastChannel.takeIf { it.reference.isNotBlank() }
                config.copy(startSat = last, startReceiverId = receiver)
            }
        }
    }

    fun clearStartChannel() = store.update { if (it.iptv) it.copy(startIptv = null) else it.copy(startSat = null, startReceiverId = "") }

    var cleanupMessage by mutableStateOf(false)
        private set

    fun cleanupNow() = viewModelScope.launch {
        cleanupMessage = runCatching { cleanup.run() }.getOrElse {
            Timber.w("Hotel cleanup: %s", it.javaClass.simpleName)
            false
        }
    }

    fun cleanupMessageShown() { cleanupMessage = false }

    // --- Wi-Fi password (SecretStore, read and written off the main thread) ----------------------

    var wifiPassword by mutableStateOf("")
        private set
    private var wifiLoaded = false

    fun loadWifiPassword() {
        if (wifiLoaded) return
        wifiLoaded = true
        viewModelScope.launch { wifiPassword = store.wifiPassword() }
    }

    private var wifiSave: Job? = null

    fun editWifiPassword(value: String) {
        wifiPassword = value
        wifiSave?.cancel()
        wifiSave = viewModelScope.launch { store.setWifiPassword(value) }
    }

    // --- Launcher --------------------------------------------------------------------------------

    var isDefaultLauncher by mutableStateOf(false)
        private set

    fun refreshLauncher() = viewModelScope.launch { isDefaultLauncher = store.isDefaultLauncher() }

    // --- Bouquets (all of them, unfiltered, from the receiver) ----------------------------------

    data class BouquetPage(val loading: Boolean, val bouquets: List<BouquetEntry> = emptyList(), val failed: Boolean = false)

    var bouquetPage by mutableStateOf<BouquetPage?>(null)
        private set

    fun openBouquets() {
        bouquetPage = BouquetPage(loading = true)
        viewModelScope.launch {
            bouquetPage = runCatching {
                val receiver = settings.current().activeReceiver() ?: error("no receiver")
                BouquetPage(false, receivers.televisionBouquets(receiver))
            }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                BouquetPage(false, failed = true)
            }
        }
    }

    fun closeBouquets() { bouquetPage = null }

    fun toggleBouquet(reference: String) {
        val all = bouquetPage?.bouquets.orEmpty().map { it.reference }
        store.update { it.copy(allowedBouquets = HotelBouquets.toggle(it.allowedBouquets, all, reference)) }
    }

    fun allBouquets() = store.update { it.copy(allowedBouquets = null) }
    fun noBouquets() = store.update { it.copy(allowedBouquets = emptySet()) }

    // --- IPTV categories per provider and section ------------------------------------------------

    data class CategoryPage(
        val section: XtreamSection,
        val loading: Boolean,
        val categories: List<XtreamCategory> = emptyList(),
        val source: String = "",
        val failed: Boolean = false,
        val providerId: String = "",
        val providers: List<IptvProvider> = emptyList(),
    )

    var categoryPage by mutableStateOf<CategoryPage?>(null)
        private set
    private var categoryJob: Job? = null

    fun openCategories() = showCategories(XtreamSection.LIVE)

    fun showCategories(section: XtreamSection, providerId: String? = null) {
        categoryJob?.cancel()
        val providers = playlists.enabledProviders()
        categoryPage = CategoryPage(section, loading = true, providers = providers, providerId = providerId.orEmpty())
        categoryJob = viewModelScope.launch {
            categoryPage = runCatching {
                val now = settings.current()
                if (now.iptvSource == IptvSourceMode.PLAYLIST) {
                    val chosen = providers.firstOrNull { it.id == providerId } ?: providers.firstOrNull()
                    if (chosen == null) CategoryPage(section, false, providers = providers)
                    else CategoryPage(
                        section, false, playlistSync.categories(section, chosen.id),
                        source = IptvProvider.sourceKey(chosen.id), providerId = chosen.id, providers = providers,
                    )
                } else {
                    val receiver = now.activeReceiver() ?: error("no receiver")
                    val account = (xtream.cachedDetection(receiver.id) ?: xtream.detect(receiver)).accounts.firstOrNull() ?: error("no playlist")
                    CategoryPage(section, false, xtream.categories(account, section), source = IptvCategoryFilter.sourceKey(null, account.key))
                }
            }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                CategoryPage(section, false, failed = true, providers = providers)
            }
        }
    }

    fun closeCategories() { categoryJob?.cancel(); categoryPage = null }

    fun shownCategories(page: CategoryPage, config: HotelConfig): Set<String> =
        HotelFilter.shownCategories(page.categories, page.source, page.section, config)

    fun toggleCategory(id: String) {
        val page = categoryPage ?: return
        if (page.source.isBlank()) return
        store.update { config ->
            val shown = HotelFilter.shownCategories(page.categories, page.source, page.section, config)
            HotelFilter.withCategories(config, page.source, page.section, if (id in shown) shown - id else shown + id)
        }
    }

    fun allCategories() {
        val page = categoryPage ?: return
        if (page.source.isBlank()) return
        store.update { it.copy(allowedCategories = it.allowedCategories - HotelFilter.categoryKey(page.source, page.section)) }
    }

    fun noCategories() {
        val page = categoryPage ?: return
        if (page.source.isBlank()) return
        store.update { HotelFilter.withCategories(it, page.source, page.section, emptySet()) }
    }
}

/** Start volume steps: "unchanged" (-1), then 0–100 % in steps of 5. */
object HotelVolume {
    fun step(volume: Int, delta: Int): Int {
        if (volume < 0) return if (delta > 0) 20 else -1
        val next = volume + delta * 5
        return when {
            next < 0 -> -1
            next > 100 -> 100
            else -> next
        }
    }
}

/** The bouquet whitelist: null = all visible; toggling from "all" keeps every other bouquet. */
object HotelBouquets {
    fun toggle(allowed: Set<String>?, all: List<String>, reference: String): Set<String>? {
        val current = allowed ?: all.toSet()
        val next = if (reference in current) current - reference else current + reference
        return if (all.isNotEmpty() && next.containsAll(all)) null else next
    }

    fun shown(allowed: Set<String>?, all: List<String>): Int = if (allowed == null) all.size else all.count { it in allowed }
}
