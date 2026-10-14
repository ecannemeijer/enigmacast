package com.enigmacast.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.sync.SyncPolicy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncEntryPoint {
    fun engine(): SyncEngine
    fun settings(): SettingsRepository
    fun iptvPlaylist(): com.enigmacast.data.xtream.IptvPlaylistSync
}

/** Runs [SyncEngine] for the active receiver. Hourly it only does the steps that are due. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, SyncEntryPoint::class.java)
        val settings = entry.settings().current()
        // Settings › IPTV › Playlist: reloaded once a day on the same schedule, receiver or not.
        if (settings.iptvSource == com.enigmacast.domain.model.IptvSourceMode.PLAYLIST) {
            runCatching { entry.iptvPlaylist().refreshIfDue(settings.iptvPlaylistKind) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
        val receiver = settings.activeReceiver() ?: return Result.success()
        entry.engine().run(
            receiver,
            force = inputData.getBoolean(KEY_FORCE, false),
            refilm = inputData.getBoolean(KEY_REFILM, false),
            check = inputData.getBoolean(KEY_CHECK, false),
            carryFrom = inputData.getString(KEY_FROM),
        )
        return Result.success()
    }

    companion object {
        const val KEY_FORCE = "force"
        const val KEY_REFILM = "refilm"
        const val KEY_CHECK = "check"
        const val KEY_FROM = "carry_from"
    }
}

/** WorkManager setup: an hourly check plus "now" requests (first connect, receiver change, Refresh now). */
object SyncScheduler {
    private const val PERIODIC = "cache-sync-periodic"
    private const val NOW = "cache-sync-now"
    private const val REFILM = "cache-sync-refilm"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(SyncPolicy.CHECK_INTERVAL_MIN, TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    @Volatile private var lastCheckMs = 0L

    /**
     * App start/resume: compares the box's bouquets and channels with the cache (at most every
     * 2 minutes) and refreshes them, and then the guide, when the box changed them.
     */
    fun checkForChanges(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastCheckMs < 2 * 60_000) return
        lastCheckMs = now
        runNow(context, force = false, check = true)
    }

    /** [force] refreshes everything; otherwise only what is due or missing. */
    fun runNow(context: Context, force: Boolean, refilm: Boolean = false, check: Boolean = false, carryFrom: String? = null) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setInputData(workDataOf(SyncWorker.KEY_FORCE to force, SyncWorker.KEY_REFILM to refilm, SyncWorker.KEY_CHECK to check, SyncWorker.KEY_FROM to carryFrom))
            .build()
        // Re-matching films after a bouquet change has its own queue, so it never cancels a running full sync.
        val name = if (refilm && !force) REFILM else NOW
        WorkManager.getInstance(context).enqueueUniqueWork(name, if (force || refilm || carryFrom != null) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }
}
