package com.enigmacast.data.tvhome

import android.content.Context
import android.content.pm.PackageManager

/** What kind of TV home screen this is. */
object TvHomeDevice {
    /** Google's own feature flag for Google TV devices (developer.android.com, "Google TV"). */
    const val GOOGLE_TV_FEATURE = "com.google.android.feature.AMATI_EXPERIENCE"
    /** The Google TV home screen app. */
    const val GOOGLE_TV_LAUNCHER = "com.google.android.apps.tv.launcherx"

    fun isGoogleTv(context: Context): Boolean {
        val pm = context.packageManager
        if (runCatching { pm.hasSystemFeature(GOOGLE_TV_FEATURE) }.getOrDefault(false)) return true
        return runCatching { pm.getPackageInfo(GOOGLE_TV_LAUNCHER, 0); true }.getOrDefault(false)
    }

    fun isTv(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}
