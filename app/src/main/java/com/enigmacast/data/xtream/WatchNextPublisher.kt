package com.enigmacast.data.xtream

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.enigmacast.data.db.IptvResumeRow
import com.enigmacast.domain.xtream.XtreamEpisode
import com.enigmacast.domain.xtream.XtreamItem
import com.enigmacast.domain.xtream.XtreamSection
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Puts an in-progress IPTV film or episode on the Android TV home Watch Next row. */
@Singleton
class WatchNextPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun publish(row: IptvResumeRow) {
        scope.launch { runCatching { upsert(row) } }
    }

    fun remove(source: String, kind: String, id: String) {
        scope.launch { runCatching { delete(providerId(source, kind, id)) } }
    }

    private fun upsert(row: IptvResumeRow) {
        val poster = row.poster?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return
        val id = providerId(row.source, row.kind, row.id)
        val type = if (row.kind == "e") TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE
        else TvContractCompat.WatchNextPrograms.TYPE_MOVIE
        val builder = WatchNextProgram.Builder()
            .setType(type)
            // A series card on its next, unstarted episode is "next", not "continue".
            .setWatchNextType(
                if (com.enigmacast.domain.resume.SeriesContinue.isUpNext(row.kind, row.positionMs)) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
                else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE,
            )
            .setTitle(if (row.kind == "e") row.seriesTitle?.ifBlank { row.title } ?: row.title else row.title)
            .setPosterArtUri(Uri.parse(poster))
            .setIntentUri(watchUri(row.source, row.kind, row.id))
            .setInternalProviderId(id)
            .setContentId(id)
            .setLastPlaybackPositionMillis(row.positionMs.coerceAtLeast(0).toInt())
            .setDurationMillis(row.durationMs.coerceAtLeast(0).toInt())
        if (row.kind == "e") {
            builder.setEpisodeTitle(row.title)
            if (row.season > 0) builder.setSeasonNumber(row.season)
            if (row.episode > 0) builder.setEpisodeNumber(row.episode)
        }
        val values = builder.build().toContentValues()
        val existing = find(id)
        val resolver = context.contentResolver
        if (existing == null) resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
        else resolver.update(ContentUris.withAppendedId(TvContractCompat.WatchNextPrograms.CONTENT_URI, existing), values, null, null)
    }

    private fun delete(id: String) {
        val existing = find(id) ?: return
        context.contentResolver.delete(ContentUris.withAppendedId(TvContractCompat.WatchNextPrograms.CONTENT_URI, existing), null, null)
    }

    private fun find(id: String): Long? {
        val uri = TvContractCompat.WatchNextPrograms.CONTENT_URI
        context.contentResolver.query(
            uri,
            arrayOf(TvContractCompat.BaseTvColumns._ID, TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID),
            null,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(TvContractCompat.BaseTvColumns._ID)
            val keyCol = cursor.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            if (idCol < 0 || keyCol < 0) return null
            while (cursor.moveToNext()) {
                if (cursor.getString(keyCol) == id) return cursor.getLong(idCol)
            }
        }
        return null
    }

    companion object {
        fun providerId(source: String, kind: String, id: String) = "$source/$kind/$id"

        fun watchUri(source: String, kind: String, id: String): Uri = Uri.parse(
            "enigmacast://watchnext?source=${Uri.encode(source)}&kind=${Uri.encode(kind)}&id=${Uri.encode(id)}",
        )

        /** The library request that resumes this Watch Next card from its saved position. */
        fun request(row: IptvResumeRow): IptvOpenRequest? {
            val episode = if (row.kind == "e") {
                if (row.seriesId.isNullOrBlank()) return null
                XtreamEpisode(row.id, row.season, row.episode, row.title, row.extension, row.poster.orEmpty())
            } else null
            val item = if (episode != null) {
                XtreamItem(row.seriesId!!, row.seriesTitle ?: row.title, XtreamSection.SERIES, row.categoryId, row.poster.orEmpty())
            } else {
                XtreamItem(row.id, row.title, XtreamSection.MOVIES, row.categoryId, row.poster.orEmpty(), row.extension, url = row.url)
            }
            return IptvOpenRequest(row.source, item, episode = episode, resume = true)
        }
    }
}
