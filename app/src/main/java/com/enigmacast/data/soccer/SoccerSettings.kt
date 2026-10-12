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

/** Settings › General › Soccer: one followed country, refresh interval, live updates and an optional own key. */
@Singleton
class SoccerSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("soccer", Context.MODE_PRIVATE)

    data class Values(
        val country: String,
        /** Minutes between fixture/standings refreshes; 0 = only when asked. */
        val refreshMinutes: Int = 60,
        val liveUpdates: Boolean = true,
        val apiKey: String = "",
    )

    private val _values = MutableStateFlow(read())
    val values: StateFlow<Values> = _values.asStateFlow()

    fun setCountry(id: String) = edit { putString(KEY_COUNTRY, id) }
    fun setRefreshMinutes(minutes: Int) = edit { putInt(KEY_REFRESH, minutes) }
    fun setLiveUpdates(on: Boolean) = edit { putBoolean(KEY_LIVE, on) }
    fun setApiKey(key: String) = edit { putString(KEY_API, key.trim()) }

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _values.value = read()
    }

    private fun read() = Values(
        country = prefs.getString(KEY_COUNTRY, null) ?: SoccerCountries.defaultFor(Locale.getDefault().country),
        refreshMinutes = prefs.getInt(KEY_REFRESH, 60),
        liveUpdates = prefs.getBoolean(KEY_LIVE, true),
        apiKey = prefs.getString(KEY_API, "").orEmpty(),
    )

    companion object {
        val REFRESH_CHOICES = listOf(15, 30, 60, 180, 360, 0)
        private const val KEY_COUNTRY = "country"
        private const val KEY_REFRESH = "refresh_minutes"
        private const val KEY_LIVE = "live_updates"
        private const val KEY_API = "api_key"
    }
}
