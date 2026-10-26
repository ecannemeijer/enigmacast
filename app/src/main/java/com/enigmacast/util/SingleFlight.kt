package com.enigmacast.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One load per key at a time, kept for [ttlMs] afterwards. Callers that ask for a key while it
 * is loading wait for that same load instead of starting their own; a result younger than
 * [ttlMs] is handed out again. Failures are not kept: the next caller loads anew. When the
 * caller that runs a load is cancelled, a waiting caller takes the load over.
 */
class SingleFlight<V>(
    private val ttlMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Entry<V>(val result: CompletableDeferred<V>) {
        var doneAtMs = -1L
    }

    private val lock = Mutex()
    private val entries = HashMap<String, Entry<V>>()

    suspend fun get(key: String, load: suspend () -> V): V {
        while (true) {
            var owned = false
            val entry = lock.withLock {
                val at = now()
                val existing = entries[key]
                if (existing != null && (existing.doneAtMs < 0 || at - existing.doneAtMs < ttlMs)) {
                    existing
                } else {
                    entries.entries.removeAll { it.value.doneAtMs >= 0 && at - it.value.doneAtMs >= ttlMs }
                    owned = true
                    Entry<V>(CompletableDeferred()).also { entries[key] = it }
                }
            }
            if (owned) {
                val value = try {
                    load()
                } catch (error: Throwable) {
                    lock.withLock { if (entries[key] === entry) entries.remove(key) }
                    entry.result.completeExceptionally(error)
                    throw error
                }
                lock.withLock { entry.doneAtMs = now() }
                entry.result.complete(value)
                return value
            }
            try {
                return entry.result.await()
            } catch (cancelled: CancellationException) {
                // The loader was cancelled, not us: take the load over.
                currentCoroutineContext().ensureActive()
            }
        }
    }

    /** Drops every kept result (a running load still finishes for its own callers). */
    suspend fun forget() {
        lock.withLock { entries.clear() }
    }
}
