package com.enigmacast.data.tmdb

import android.content.Context
import com.enigmacast.domain.films.CountryResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Home page settings: the country override and an optional TMDB key that replaces the built-in one. */
@Singleton
class HomeFilmSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("home_films", Context.MODE_PRIVATE)

    data class Values(val country: String, val tmdbKey: String)

    private val _values = MutableStateFlow(read())
    val values: StateFlow<Values> = _values.asStateFlow()

    fun countryOverride(): String = _values.value.country
    fun tmdbKeyOverride(): String = _values.value.tmdbKey

    fun setCountry(code: String) {
        prefs.edit().putString(KEY_COUNTRY, code).apply()
        _values.value = read()
    }

    fun setTmdbKey(key: String) {
        prefs.edit().putString(KEY_TMDB, key.trim()).apply()
        _values.value = read()
    }

    /** Last IP lookup, kept a week so the home page does not ask every start. */
    fun cachedIpCountry(nowMs: Long): String? {
        val at = prefs.getLong(KEY_IP_AT, 0L)
        if (nowMs - at > 7L * 24 * 3600 * 1000) return null
        return prefs.getString(KEY_IP, null)
    }

    fun storeIpCountry(code: String, nowMs: Long) {
        prefs.edit().putString(KEY_IP, code).putLong(KEY_IP_AT, nowMs).apply()
    }

    private fun read() = Values(
        country = prefs.getString(KEY_COUNTRY, CountryResolver.AUTO) ?: CountryResolver.AUTO,
        tmdbKey = prefs.getString(KEY_TMDB, "").orEmpty(),
    )

    private companion object {
        const val KEY_COUNTRY = "country"
        const val KEY_TMDB = "tmdb_key"
        const val KEY_IP = "ip_country"
        const val KEY_IP_AT = "ip_country_at"
    }
}
