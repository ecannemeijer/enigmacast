package com.enigmacast.domain.xtream

import com.enigmacast.domain.tuner.RestartBudget

/**
 * IPTV providers answer "too many connections for this account" with a 4xx/5xx status instead
 * of a stream: 458 (XUI / Xtream UI "max connections"), 429 (too many requests), 509
 * (bandwidth/connection limit) and, on several panels, 403. Retrying such an answer at once
 * only adds connections; the player backs off a couple of times and then says why.
 */
object ProviderLimit {
    val STATUS_CODES = setOf(403, 429, 458, 509)

    /** Shown instead of a generic error once the retries are used up (mapped to a string by the screen). */
    const val ERROR = "PROVIDER_LIMIT"

    const val RETRIES = 2
    const val FIRST_DELAY_MS = 5_000L
    const val MAX_DELAY_MS = 15_000L

    fun isLimit(status: Int?): Boolean = status != null && status in STATUS_CODES

    /** A fresh budget: 2 retries, 5 s then 10 s apart (the provider needs time to drop the old session). */
    fun budget(): RestartBudget = RestartBudget(RETRIES, FIRST_DELAY_MS, MAX_DELAY_MS)

    /** Host of a stream address (for "is the PiP inset on the same provider?"); null when unparseable. */
    fun host(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val rest = url.substringAfter("://", "")
        if (rest.isEmpty()) return null
        val authority = rest.substringBefore('/').substringBefore('?').substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        return host.lowercase().ifBlank { null }
    }

    fun sameProvider(a: String?, b: String?): Boolean {
        val ha = host(a) ?: return false
        return ha == host(b)
    }
}
