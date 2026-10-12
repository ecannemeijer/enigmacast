package com.enigmacast.live

import android.content.Context
import android.telephony.TelephonyManager
import com.enigmacast.data.tmdb.HomeFilmSettings
import com.enigmacast.domain.films.CountryResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The viewer's country: override, network, IP lookup (3 s, cached a week), then the TV locale. */
@Singleton
class CountryDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val homeSettings: HomeFilmSettings,
) {
    private val ipClient by lazy { OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build() }

    suspend fun country(): String {
        val override = homeSettings.countryOverride()
        if (override != CountryResolver.AUTO) return CountryResolver.resolve(override, null, null, null)
        val network = runCatching {
            (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.networkCountryIso
        }.getOrNull()
        val ip = if (CountryResolver.canonical(network) == null) ipCountry() else null
        return CountryResolver.resolve(null, network, ip, Locale.getDefault().country)
    }

    fun appLanguage(): String = context.resources.configuration.locales[0]?.language ?: "en"

    private suspend fun ipCountry(): String? {
        val now = System.currentTimeMillis()
        homeSettings.cachedIpCountry(now)?.let { return it }
        val code = withTimeoutOrNull(3_000) {
            withContext(Dispatchers.IO) {
                runCatching {
                    ipClient.newCall(Request.Builder().url("https://ipapi.co/country/").build()).execute().use { response ->
                        if (response.isSuccessful) response.body.string().trim() else null
                    }
                }.getOrNull()
            }
        }
        return CountryResolver.canonical(code)?.also { homeSettings.storeIpCountry(it, now) }
    }
}
