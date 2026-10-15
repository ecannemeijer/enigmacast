package com.enigmacast

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class EnigmaCastApp : Application() {
    @javax.inject.Inject lateinit var recordingTunerGuard: com.enigmacast.live.RecordingTunerGuard
    @javax.inject.Inject lateinit var userSync: com.enigmacast.data.enigmasync.UserSyncScheduler

    override fun onCreate() {
        super.onCreate()
        recordingTunerGuard.start()
        userSync.start()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
