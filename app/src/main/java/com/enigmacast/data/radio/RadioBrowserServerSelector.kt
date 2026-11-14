package com.enigmacast.data.radio

import android.util.Log
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RadioBrowserServerSelector @Inject constructor() {

    @Volatile
    private var cachedHost: String = FALLBACK_HOST

    @Volatile
    private var discovered = false

    fun resolveHost(): String {
        if (discovered) return cachedHost
        synchronized(this) {
            if (discovered) return cachedHost
            cachedHost = discoverHost() ?: FALLBACK_HOST
            discovered = true
            Log.i(TAG, "Using Radio Browser host $cachedHost")
            return cachedHost
        }
    }

    private fun discoverHost(): String? {
        return runCatching {
            val addresses = InetAddress.getAllByName(DISCOVERY_HOST)
            val hosts = addresses.mapNotNull { address ->
                val ip = address.hostAddress ?: return@mapNotNull null
                val reverse = runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull()
                reverse?.takeIf { it.isNotBlank() && !looksLikeIp(it) }
            }.distinct()
            hosts.randomOrNull()
        }.onFailure { error ->
            Log.w(TAG, "Radio Browser DNS discovery failed, using $FALLBACK_HOST", error)
        }.getOrNull()
    }

    private fun looksLikeIp(host: String): Boolean {
        return host.contains(":") || host.matches(IPV4_REGEX)
    }

    companion object {
        private const val TAG = "RadioBrowser"
        private const val DISCOVERY_HOST = "all.api.radio-browser.info"
        const val FALLBACK_HOST = "de1.api.radio-browser.info"
        private val IPV4_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    }
}
