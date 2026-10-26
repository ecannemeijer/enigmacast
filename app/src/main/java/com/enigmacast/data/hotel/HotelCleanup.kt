package com.enigmacast.data.hotel

import android.content.Context
import android.media.AudioManager
import com.enigmacast.data.db.ResumeDao
import com.enigmacast.data.profile.ProfileStore
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.xtream.IptvLibrary
import com.enigmacast.data.xtream.RecentIptvStore
import com.enigmacast.domain.hotel.HotelCleanupPlan
import com.enigmacast.domain.hotel.HotelConfig
import com.enigmacast.domain.hotel.HotelRuntime
import com.enigmacast.domain.hotel.HotelSchedule
import com.enigmacast.domain.profile.ProfileState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * "Clean up for the next guest": everything the previous guest left behind in the hotel
 * profile — watch history, Continue watching, episode/film history, recent channels and the
 * live subtitle choice — goes, and the start channel and start volume come back.
 *
 * Scope: only rows and settings of the hotel profile. The owner's other profiles, on this TV
 * and on the sync server, are never touched. Continue watching is removed through the
 * library's normal tombstones (synced), the watch history only on this TV.
 * Audio boost stays: it is a per-channel loudness fix (a quiet channel stays quiet for every
 * guest), not something that tells about the previous guest. Favourites stay too: the owner sets them.
 */
@Singleton
class HotelCleanup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: HotelStore,
    private val profiles: ProfileStore,
    private val settings: SettingsRepository,
    private val library: IptvLibrary,
    private val recentIptv: RecentIptvStore,
    private val resume: ResumeDao,
    private val ownTuner: com.enigmacast.live.OwnTunerSession,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var daily: Job? = null

    /** The hotel profile's uuid: the linked one, or the active one while hotel mode is on. */
    fun hotelUuid(config: HotelConfig = store.current): String =
        config.profileUuid.ifBlank { if (config.enabled) profiles.state.value.activeUuid else "" }

    suspend fun run(): Boolean = mutex.withLock {
        val config = store.current
        val uuid = hotelUuid(config)
        if (uuid.isBlank()) return@withLock false
        val state = profiles.state.value
        // The scoped settings (recent channels, subtitles, start channel) belong to the active
        // profile; they are only rewritten when that is the hotel profile.
        if (state.activeUuid == uuid) {
            settings.update { HotelCleanupPlan.settings(it, config) }
        }
        runCatching { library.clearProfileHistory(uuid) }.onFailure { Timber.w("Hotel cleanup library: %s", it.javaClass.simpleName) }
        recentIptv.clearProfile(uuid)
        val profileId = state.profiles.firstOrNull { it.uuid.ifBlank { ProfileState.LEGACY_UUID } == uuid }?.id
        // Recording positions are scoped by profile id; the default profile's are unscoped and shared, so they stay.
        if (profileId != null && profileId != ProfileState.DEFAULT_ID) resume.deletePrefix("$profileId|")
        val now = System.currentTimeMillis()
        store.update { it.copy(lastCleanupDay = HotelSchedule.epochDay(now, ZoneId.systemDefault())) }
        if (config.enabled) {
            applyVolume(config)
            withContext(Dispatchers.Main) { ownTuner.reloadChannels() }
        }
        HotelRuntime.cleaned()
        true
    }

    /** At every hotel start: the start channel becomes the remembered one and the volume is set. */
    suspend fun applyStart(config: HotelConfig = store.current) {
        if (!config.enabled) return
        settings.update { HotelCleanupPlan.dropHiddenLastChannel(HotelCleanupPlan.withStartChannel(it, config), config) }
        applyVolume(config)
    }

    private suspend fun applyVolume(config: HotelConfig) = withContext(Dispatchers.IO) {
        if (config.startVolume !in 0..100) return@withContext
        runCatching {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * config.startVolume + 50) / 100, 0)
        }.onFailure { Timber.w("Hotel start volume: %s", it.javaClass.simpleName) }
    }

    /** The daily cleanup, checked every half minute while the app runs (and once at start, for a TV that was off). */
    fun startDaily() {
        if (daily?.isActive == true) return
        daily = scope.launch {
            while (isActive) {
                val config = store.current
                if (HotelSchedule.due(config, System.currentTimeMillis(), ZoneId.systemDefault())) {
                    runCatching { run() }.onFailure { Timber.w("Hotel daily cleanup: %s", it.javaClass.simpleName) }
                }
                delay(30_000)
            }
        }
    }
}
