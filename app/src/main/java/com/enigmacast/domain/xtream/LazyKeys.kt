package com.enigmacast.domain.xtream

/** A list entry with its lazy-list key; the key travels with the entry, never looked up by index. */
data class Keyed<T>(val key: String, val value: T)

object LazyKeys {
    /**
     * Pairs every entry with a unique key: "$prefix:$id", and for a repeated or blank id
     * (playlists do repeat them) the position is appended so the lazy list never sees a key twice.
     */
    fun <T> keyed(list: List<T>, prefix: String, id: (T) -> String): List<Keyed<T>> {
        val keyer = Keyer(prefix, list.size)
        return list.mapIndexed { index, value -> Keyed(keyer.key(id(value), index), value) }
    }

    /**
     * The same keys handed out piece by piece, for a list that grows by appending: keys of the
     * first part never change when the rest arrives. [index] is the position in the whole list.
     */
    class Keyer(private val prefix: String, expected: Int = 16) {
        private val seen = HashSet<String>(expected * 2)

        fun key(raw: String, index: Int): String {
            val base = "$prefix:$raw"
            return if (raw.isNotBlank() && seen.add(base)) base else "$base#$index".also { seen.add(it) }
        }
    }
}
