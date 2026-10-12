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
}

/** Runs [SyncEngine] for the active receiver. Hourly it only does the steps that are due. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, SyncEntryPoint::class.java)
        val receiver = entry.settings().current().activeReceiver() ?: return Result.success()
        entry.engine().run(receiver, force = inputData.getBoolean(KEY_FORCE, false))
        return Result.success()
    }

    companion object {
        const val KEY_FORCE = "force"
    }
}

/** WorkManager setup: an hourly check plus "now" requests (first connect, receiver change, Refresh now). */
object SyncScheduler {
    private const val PERIODIC = "cache-sync-periodic"
    private const val NOW = "cache-sync-now"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(SyncPolicy.CHECK_INTERVAL_MIN, TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** [force] refreshes everything; otherwise only what is due or missing. */
    fun runNow(context: Context, force: Boolean) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setInputData(workDataOf(SyncWorker.KEY_FORCE to force))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }
}
