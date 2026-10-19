package com.enigmacast.data.soccer

import android.content.Context
import com.enigmacast.domain.soccer.SoccerCountries
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings › General › Sport: refresh interval, live updates and an optional own key. The
 * [Values.region] is not a setting any more: it is the region last opened on the Sport landing
 * page, kept for that page's initial focus and shared by the football-data and PitchAPI pages.
 */
@Singleton
class SoccerSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("soccer", Context.MODE_PRIVATE)

    data class Values(
        /** Region id of [com.enigmacast.domain.soccer.SoccerCountries], last chosen on the landing page. */
        val region: String,
        /** Minutes between fixture/standings refreshes; 0 = only when asked. */
        val refreshMinutes: Int = 60,
        val liveUpdates: Boolean = true,
        val apiKey: String = "",
    )

    private val _values = MutableStateFlow(read())
    val values: StateFlow<Values> = _values.asStateFlow()

    /** Updates [values] at once; the disk write is [android.content.SharedPreferences.Editor.apply]'s, off the main thread. */
    fun setRegion(id: String) = edit { putString(KEY_REGION, id).remove(LEGACY_COUNTRY) }
    fun setRefreshMinutes(minutes: Int) = edit { putInt(KEY_REFRESH, minutes) }
    fun setLiveUpdates(on: Boolean) = edit { putBoolean(KEY_LIVE, on) }
    fun setApiKey(key: String) = edit { putString(KEY_API, key.trim()) }

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _values.value = read()
    }

    private fun read() = Values(
        region = regionOf(prefs.getString(KEY_REGION, null) ?: prefs.getString(LEGACY_COUNTRY, null), Locale.getDefault().country),
        refreshMinutes = prefs.getInt(KEY_REFRESH, 60),
        liveUpdates = prefs.getBoolean(KEY_LIVE, true),
        apiKey = prefs.getString(KEY_API, "").orEmpty(),
    )

    companion object {
        val REFRESH_CHOICES = listOf(15, 30, 60, 180, 360, 0)
        private const val KEY_REGION = "last_region"

        /** The old "followed country" setting: read once as the last region, removed on the next choice. */
        private const val LEGACY_COUNTRY = "country"

        /** A known stored id, else the TV locale's default; unknown or old values fall back harmlessly. */
        fun regionOf(stored: String?, localeIso: String?): String =
            stored?.takeIf { id -> SoccerCountries.ALL.any { it.id == id } } ?: SoccerCountries.defaultFor(localeIso)
        private const val KEY_REFRESH = "refresh_minutes"
        private const val KEY_LIVE = "live_updates"
        private const val KEY_API = "api_key"
    }
}
