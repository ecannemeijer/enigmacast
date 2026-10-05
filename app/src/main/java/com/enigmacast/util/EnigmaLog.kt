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

    fun sanitizeUrl(url: String): String {
        return url.replace(Regex("://[^/@\\s]+@"), "://***@")
    }
}
