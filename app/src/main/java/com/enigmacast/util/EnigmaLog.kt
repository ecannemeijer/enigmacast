package com.enigmacast.util

import timber.log.Timber

object DebugFlags {
    @Volatile
    var verbose: Boolean = false
}

object EnigmaLog {
    fun network(message: String) {
        if (DebugFlags.verbose) Timber.tag("EnigmaNetwork").i(message)
    }

    fun networkError(message: String) {
        Timber.tag("EnigmaNetwork").w(message)
    }

    fun remote(message: String) {
        Timber.tag("EnigmaRemote").i(message)
    }

    fun osd(message: String) {
        Timber.tag("EnigmaOsd").i(message)
    }

    fun player(message: String) {
        Timber.tag("EnigmaPlayer").i(message)
    }

    fun service(message: String) {
        Timber.tag("EnigmaService").i(message)
    }

    fun connection(message: String) {
        Timber.tag("EnigmaConnection").i(message)
    }

    /** OSD channel info: endpoints used, what was trusted for our stream, CA state changes. */
    fun osdInfo(message: String) {
        Timber.tag("EnigmaOsdInfo").i(message)
    }

    /** Autodetect: interfaces, subnet, scan timing, each hit and every `/api/about` answer. */
    fun discovery(message: String) {
        Timber.tag("EnigmaDiscovery").i(message)
    }

    fun discoveryError(message: String) {
        Timber.tag("EnigmaDiscovery").w(message)
    }

    /** [url] with any `user:password@` and password-like query values masked. */
    fun sanitizeUrl(url: String): String {
        return url.replace(Regex("://[^/@\\s]+@"), "://***@")
            // The same inside an encoded service reference (`sRef=…https%253a%252f%252fuser%253apass%2540host…`).
            .replace(ENCODED_USERINFO, "$1***$2")
            .replace(Regex("(?i)([?&](?:password|pass|pwd|token)=)[^&#\\s]*"), "$1***")
    }

    private val ENCODED_USERINFO = Regex(
        "(?i)(%(?:25)?3a(?:%(?:25)?2f|/){2})(?:(?!%(?:25)?2f)[^/\\s&?#@])+?(@|%(?:25)?40)",
    )
}
