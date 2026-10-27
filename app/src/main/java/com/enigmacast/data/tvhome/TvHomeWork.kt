package com.enigmacast.data.tvhome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Rebuilds the home-screen rows outside the app's screens (launcher start, app closed, setting changed). */
class TvHomeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Entry {
        fun tvHome(): TvHomePublisher
    }

    override suspend fun doWork(): Result {
        EntryPointAccessors.fromApplication(applicationContext, Entry::class.java).tvHome().refresh()
        return Result.success()
    }

    companion object {
        private const val NAME = "enigmacast-tvhome"

        fun enqueue(context: Context) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<TvHomeWorker>().build(),
                )
            }
        }
    }
}

/** The launcher asks a newly installed app to set up its channels. */
class TvHomeInitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TvContractCompat.ACTION_INITIALIZE_PROGRAMS) TvHomeWorker.enqueue(context)
    }
}
