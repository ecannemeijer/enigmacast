package com.enigmacast.data.settingsimport

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.enigmacast.data.account.AccountRepository
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.account.Entitlements
import com.enigmacast.domain.account.PremiumFeature
import com.enigmacast.domain.settingsimport.IptvBouquets
import com.enigmacast.domain.settingsimport.RefreshInterval
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ImportRefreshEntryPoint {
    fun importer(): ReceiverListImporter
    fun configs(): ImportConfigStore
    fun settings(): SettingsRepository
    fun account(): AccountRepository
}

/**
 * Auto-refresh of imported channel lists and IPTV bouquets. Runs every few hours and only does
 * what is due per receiver; a failed run is tried again next time.
 */
class ImportRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, ImportRefreshEntryPoint::class.java)
        val now = System.currentTimeMillis()
        if (!Entitlements.allows(entry.account().account.value, PremiumFeature.SETTINGS_IMPORT, now)) return Result.success()
        val configs = entry.configs()
        val importer = entry.importer()
        val receivers = entry.settings().current().receivers
        val packageCache = HashMap<String, Map<String, ByteArray>>()
        receivers.forEach { receiver ->
            val config = configs.get(receiver.id)
            val packageId = config.settingsPackageId
            if (packageId != null && config.settingsRefresh.isDue(config.settingsLastRunMs, now)) {
                val outcome = runCatching {
                    val pkg = importer.findPackage(packageId) ?: return@runCatching null
                    val files = packageCache.getOrPut(packageId) { importer.download(pkg) }
                    importer.loadPackage(pkg, files, receiver)
                }.getOrNull()
                if (outcome?.ok == true) configs.update(receiver.id) { it.copy(settingsLastRunMs = now) }
            }
            if (config.iptvGroups.isNotEmpty() && config.iptvRefresh.isDue(config.iptvLastRunMs, now)) {
                val outcome = runCatching {
                    val entries = importer.playlists(config.iptvSources)
                    importer.loadIptv(IptvBouquets.build(entries, config.iptvGroups, config.iptvServiceType), receiver)
                }.getOrNull()
                if (outcome?.ok == true) configs.update(receiver.id) { it.copy(iptvLastRunMs = now) }
            }
        }
        return Result.success()
    }
}

object ImportRefreshScheduler {
    private const val NAME = "settings-import-refresh"

    /** Keeps the periodic check while any receiver has auto-refresh on. */
    fun update(context: Context, configs: ImportConfigs) {
        val any = configs.receivers.values.any {
            (it.settingsPackageId != null && it.settingsRefresh != RefreshInterval.OFF) ||
                (it.iptvGroups.isNotEmpty() && it.iptvRefresh != RefreshInterval.OFF)
        }
        val work = WorkManager.getInstance(context)
        if (!any) {
            work.cancelUniqueWork(NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<ImportRefreshWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
