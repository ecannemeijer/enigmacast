package com.enigmacast

import android.app.Application
import android.os.StrictMode
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class EnigmaCastApp : Application() {
    @javax.inject.Inject lateinit var recordingTunerGuard: com.enigmacast.live.RecordingTunerGuard
    @javax.inject.Inject lateinit var userSync: com.enigmacast.data.enigmasync.UserSyncScheduler
    @javax.inject.Inject lateinit var secrets: com.enigmacast.data.security.SecretStore

    override fun onCreate() {
        if (BuildConfig.DEBUG) {
            // Debug builds only: disk and network access on the main thread shows up in logcat
            // (tag StrictMode). Log only, never a crash or dialog.
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build(),
            )
        }
        super.onCreate()
        // Decrypt the stored secrets off Main, before the first screen asks for settings.
        Thread({ runCatching { secrets.preload() } }, "secrets-preload").start()
        // The saved subtitle look, read off Main.
        com.enigmacast.playback.SubtitleStyleStore.init(this)
        recordingTunerGuard.start()
        userSync.start()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
