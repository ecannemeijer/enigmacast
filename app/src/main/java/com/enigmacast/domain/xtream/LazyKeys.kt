package com.enigmacast.domain.xtream

/** A list entry with its lazy-list key; the key travels with the entry, never looked up by index. */
data class Keyed<T>(val key: String, val value: T)

object LazyKeys {
    /**
     * Pairs every entry with a unique key: "$prefix:$id", and for a repeated or blank id
     * (playlists do repeat them) the position is appended so the lazy list never sees a key twice.
     */
    fun <T> keyed(list: List<T>, prefix: String, id: (T) -> String): List<Keyed<T>> {
        val seen = HashSet<String>(list.size * 2)
        return list.mapIndexed { index, value ->
            val raw = id(value)
            val base = "$prefix:$raw"
            val key = if (raw.isNotBlank() && seen.add(base)) base else "$base#$index".also { seen.add(it) }
            Keyed(key, value)
        }
    }
}
