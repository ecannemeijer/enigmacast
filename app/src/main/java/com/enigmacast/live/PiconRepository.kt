package com.enigmacast.live

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.source.TunerStatusSource
import com.enigmacast.domain.tuner.ServiceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Channel logos from the receiver's `/picon/` folder, kept in memory. */
@Singleton
class PiconRepository @Inject constructor(
    private val tunerSource: TunerStatusSource,
) {
    private val cache = LruCache<String, ImageBitmap>(96)
    private val missing = mutableSetOf<String>()

    fun cached(entry: BouquetEntry): ImageBitmap? = cache.get(pathFor(entry) ?: return null)

    suspend fun load(receiver: ReceiverConfig, entry: BouquetEntry): ImageBitmap? {
        val path = pathFor(entry) ?: return null
        cache.get(path)?.let { return it }
        if (path in missing) return null
        val bytes = runCatching { tunerSource.picon(receiver, path) }.getOrNull()
        val bitmap = bytes?.let {
            withContext(Dispatchers.Default) {
                runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull()
            }
        }
        if (bitmap == null) missing += path else cache.put(path, bitmap)
        return bitmap
    }

    private fun pathFor(entry: BouquetEntry): String? {
        return entry.picon.ifBlank { null } ?: ServiceRef.piconPath(entry.reference)
    }
}
