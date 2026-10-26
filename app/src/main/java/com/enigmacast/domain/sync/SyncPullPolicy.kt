package com.enigmacast.domain.sync

/**
 * How the library pull copes with the receiver's sync server dropping TLS connections:
 * every failed page makes the next request smaller (50 → 20 → 10 changes) and waits a little
 * longer before trying again. Errors are logged on one short line.
 */
object SyncPullPolicy {
    /** Page sizes, largest first; a failure moves one step down and a run never moves back up. */
    val PAGE_SIZES = intArrayOf(LibrarySync.PULL_CHUNK, 20, 10)

    /** Tries per page before the profile is left for the next sync. */
    const val MAX_ATTEMPTS = 5

    const val BASE_BACKOFF_MS = 400L
    const val MAX_BACKOFF_MS = 6_000L

    private const val MAX_ERROR_CHARS = 120

    /** The page size after [failures] failed requests in this run. */
    fun pageSize(failures: Int): Int = PAGE_SIZES[failures.coerceIn(0, PAGE_SIZES.size - 1)]

    /** The wait after failed try number [attempt] (0-based): 400 ms, 800 ms, 1.6 s, … up to [MAX_BACKOFF_MS]. */
    fun backoffMs(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 16)
        return (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    /** True when the page just read was the last one: the cursor stopped, or fewer rows than asked came back. */
    fun lastPage(cursor: Int, next: Int, received: Int?, limit: Int): Boolean =
        received == null || next <= cursor || received < limit

    /**
     * One short line for the log: the exception type and the first line of its message, without
     * native SSL handles (`ssl=0x…`) or repeated spaces, cut to [MAX_ERROR_CHARS].
     */
    fun compactError(error: Throwable): String {
        val root = generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(4).last()
        val shown = if (error.message.isNullOrBlank()) root else error
        val message = shown.message.orEmpty()
            .lineSequence().firstOrNull().orEmpty()
            .replace(Regex("ssl=0x[0-9a-fA-F]+:?\\s*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val text = if (message.isEmpty()) shown.javaClass.simpleName else "${shown.javaClass.simpleName}: $message"
        return if (text.length <= MAX_ERROR_CHARS) text else text.take(MAX_ERROR_CHARS - 1) + "…"
    }
}

/**
 * Outcome of the last sync run, kept for the Sync server card: how many profiles could not be
 * pulled, and whether another part (settings, playlist, push) failed. All zero/false = complete.
 */
data class SyncHealth(
    val failedProfiles: Int = 0,
    val totalProfiles: Int = 0,
    val otherFailed: Boolean = false,
    val atMs: Long = 0L,
) {
    val incomplete: Boolean get() = failedProfiles > 0 || otherFailed
}
