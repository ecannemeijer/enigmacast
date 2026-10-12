package com.enigmacast.data.tmdb

import android.content.Context
import com.enigmacast.domain.sync.SyncPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Settings › General › Data & sync. Times per day (0 = Off); films can follow the EPG setting. */
@Singleton
class SyncSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("sync_settings", Context.MODE_PRIVATE)

    data class Values(
        val channelsPerDay: Int = 1,
        val epgPerDay: Int = 2,
        val filmsPerDay: Int = SyncPolicy.FOLLOW_EPG,
        /** Days of guide to keep ahead: 1 = today, 2 = today and tomorrow, 3 = two days ahead. */
        val epgDays: Int = 2,
    ) {
        val effectiveFilmsPerDay: Int get() = SyncPolicy.filmsTimesPerDay(filmsPerDay, epgPerDay)
    }

    private val _values = MutableStateFlow(read())
    val values: StateFlow<Values> = _values.asStateFlow()

    fun setChannels(value: Int) = put("channels", value)
    fun setEpg(value: Int) = put("epg", value)
    fun setFilms(value: Int) = put("films", value)
    fun setEpgDays(value: Int) = put("epg_days", value.coerceIn(1, 3))

    private fun put(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
        _values.value = read()
    }

    private fun read(): Values {
        val d = Values()
        return Values(
            channelsPerDay = prefs.getInt("channels", d.channelsPerDay),
            epgPerDay = prefs.getInt("epg", d.epgPerDay),
            filmsPerDay = prefs.getInt("films", d.filmsPerDay),
            epgDays = prefs.getInt("epg_days", d.epgDays),
        )
    }
}
