package com.enigmacast.domain.sync

/**
 * Which sync server a receiver runs and whether the app should offer the latest release.
 * Servers from 0.3.1 report their version; older ones are told apart by what they send back.
 */
object SyncServerVersions {
    /** First release that answers pulls in pages (`limit` in the response). */
    const val FIRST_PAGING = "0.2.6"

    /** First release that keeps a 0.2.4 database in an unmounted /media folder when upgrading. */
    const val SAFE_LEGACY_UPGRADE = "0.3.1"

    /** "v0.3.0", "0.3.0", "enigmacast-sync - 0.3.0" → [0, 3, 0]; null when there is no version. */
    fun parse(text: String?): List<Int>? {
        val match = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""").find(text.orEmpty()) ?: return null
        return listOf(match.groupValues[1], match.groupValues[2], match.groupValues[3].ifEmpty { "0" }).map { it.toInt() }
    }

    fun normalize(text: String?): String? = parse(text)?.joinToString(".")

    /** Negative when [a] is older than [b]; null when either is unknown. */
    fun compare(a: String?, b: String?): Int? {
        val left = parse(a) ?: return null
        val right = parse(b) ?: return null
        for (i in 0 until 3) {
            val diff = left[i].compareTo(right[i])
            if (diff != 0) return diff
        }
        return 0
    }

    fun older(running: String?, latest: String?): Boolean = (compare(running, latest) ?: 0) < 0

    /**
     * What the app knows about the running server.
     * @param reported `version` from /api/v1/health (0.3.1 and newer)
     * @param installed the opkg version read over SSH during the last update, for this host
     * @param pagesSeen true/false once a complete pull showed whether `limit` came back; null = no pull yet
     */
    data class Running(val reported: String?, val installed: String?, val pagesSeen: Boolean?) {
        /** The version to show and compare, best source first. */
        val version: String?
            get() = normalize(reported) ?: normalize(installed)?.takeUnless {
                // A remembered pre-paging version while pages arrive: updated by hand since then.
                pagesSeen == true && compare(it, FIRST_PAGING)!! < 0
            }

        /** Known to lack paged pulls: the cause of endless "unexpected end of stream" syncs. */
        val withoutPaging: Boolean get() = version?.let { compare(it, FIRST_PAGING)!! < 0 } ?: (pagesSeen == false)
    }

    enum class Verdict { UP_TO_DATE, OUTDATED, UNKNOWN }

    /**
     * Outdated when the running version is older than [latest], or when the version is unknown and
     * the server is older than 0.3.1 anyway (no version field). UNKNOWN only when even [latest] is unknown.
     */
    fun verdict(running: Running, latest: String?): Verdict {
        val version = running.version
        if (latest == null) return if (running.withoutPaging) Verdict.OUTDATED else Verdict.UNKNOWN
        if (version == null) {
            // No version field: older than 0.3.1. Offer the update when the release is newer than that.
            return if (running.withoutPaging || older("0.3.0", latest)) Verdict.OUTDATED else Verdict.UNKNOWN
        }
        return if (older(version, latest)) Verdict.OUTDATED else Verdict.UP_TO_DATE
    }

    /** Text for "you have X": the exact version, or a range for old servers without a version field. */
    fun describe(running: Running): String = running.version ?: when (running.pagesSeen) {
        false -> "≤ 0.2.5"
        true -> "0.2.6 – 0.3.0"
        null -> "< 0.3.1"
    }

    /**
     * The one-time prompt at startup: only for the owner (never in hotel mode), only when outdated,
     * and not again for a release the owner already postponed with Later.
     */
    fun shouldPrompt(verdict: Verdict, latest: String?, postponed: String?, hotel: Boolean): Boolean =
        !hotel && verdict == Verdict.OUTDATED && latest != null && normalize(postponed) != normalize(latest)
}
