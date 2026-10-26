package com.enigmacast.domain.sync

/**
 * What Settings › Sync server offers on a TV that is not paired yet, after looking at port 8740
 * on the receiver. A running server is joined, not installed over: a second install is only
 * offered behind a warning.
 */
object SyncServerOffer {
    enum class Action { PAIR, UPDATE, REINSTALL, INSTALL }

    /**
     * @param found a sync server answered on the receiver
     * @param version its /api/v1/health version; null for servers before 0.3.1
     * @param latest the latest release, null while unknown
     */
    fun actions(found: Boolean, version: String?, latest: String?): List<Action> {
        if (!found) return listOf(Action.INSTALL)
        return buildList {
            add(Action.PAIR)
            if (outdated(version, latest)) add(Action.UPDATE)
            add(Action.REINSTALL)
        }
    }

    /** Older than [latest]; a server without a version field is older than 0.3.1. */
    fun outdated(version: String?, latest: String?): Boolean =
        SyncServerVersions.verdict(SyncServerVersions.Running(version, null, null), latest) == SyncServerVersions.Verdict.OUTDATED

    /** "v0.3.1", or "< 0.3.1" for an old server that does not report its version. */
    fun label(version: String?): String =
        SyncServerVersions.normalize(version)?.let { "v$it" } ?: SyncServerVersions.describe(SyncServerVersions.Running(null, null, null))
}
