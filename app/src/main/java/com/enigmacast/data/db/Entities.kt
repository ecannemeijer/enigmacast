package com.enigmacast.data.db

import androidx.room.Entity
import androidx.room.Index

/** All rows carry [receiverKey] (the receiver id, or host:port when it has none), so boxes never mix. */
@Entity(tableName = "bouquets", primaryKeys = ["receiverKey", "reference"])
data class BouquetRow(
    val receiverKey: String,
    val reference: String,
    val name: String,
    val position: Int,
)

/** One bouquet name, so a service lookup does not materialise the channel table. */
data class BouquetNameRow(val name: String, val reference: String)

/** A channel row that was stored before its service key was filled in. */
data class UnkeyedChannelRow(val reference: String, val bouquetReference: String)

@Entity(tableName = "channels", primaryKeys = ["receiverKey", "bouquetReference", "reference"])
data class ChannelRow(
    val receiverKey: String,
    val bouquetReference: String,
    val reference: String,
    val name: String,
    val picon: String,
    val number: Int,
    val position: Int,
    /** NowEvents.key of [reference], to join with the guide. */
    val serviceKey: String = "",
)

@Entity(
    tableName = "epg_events",
    primaryKeys = ["receiverKey", "serviceKey", "beginSec"],
    indices = [Index("receiverKey", "endSec")],
)
data class EpgRow(
    val receiverKey: String,
    /** NowEvents.key of the reference, so SD/HD reference spellings match. */
    val serviceKey: String,
    val reference: String,
    val beginSec: Long,
    val endSec: Long,
    val eventId: Int,
    val title: String,
    val shortDescription: String,
    val longDescription: String,
    val genreId: Int,
    val genre: String,
)

/** Search result cache: normalised title|year|language → TMDB id (null = no film found). */
@Entity(tableName = "tmdb_matches", primaryKeys = ["matchKey"])
data class TmdbMatchRow(
    val matchKey: String,
    val tmdbId: Int?,
    val confidence: Double,
    val cachedUntilMs: Long,
)

@Entity(tableName = "tmdb_movies", primaryKeys = ["tmdbId", "language"])
data class TmdbMovieRow(
    val tmdbId: Int,
    val language: String,
    val title: String,
    val posterPath: String?,
    val backdropPath: String?,
    val logoPath: String?,
    val runtimeMin: Int?,
    val certification: String,
    val tagline: String,
    /** The whole TmdbMovie (genres, cast, directors…) as JSON. */
    val payload: String,
    val cachedUntilMs: Long,
)

/** One matched film in the guide. The home rows are computed from these against the clock. */
@Entity(
    tableName = "home_films",
    primaryKeys = ["receiverKey", "serviceKey", "beginSec"],
    indices = [Index("receiverKey", "endSec")],
)
data class HomeFilmRow(
    val receiverKey: String,
    val serviceKey: String,
    val reference: String,
    val beginSec: Long,
    val endSec: Long,
    /** Local date (yyyy-MM-dd) the film starts on. */
    val day: String,
    val eventId: Int,
    val epgTitle: String,
    val shortDescription: String,
    val longDescription: String,
    val channelName: String,
    val channelPicon: String,
    val searchTitle: String,
    val year: Int?,
    val tmdbId: Int,
    val language: String,
    /** "film" or "series" (then [tmdbId] is the negative TMDB TV id, see SeriesIds). */
    @androidx.room.ColumnInfo(defaultValue = "film") val kind: String = HomeKinds.FILM,
)

object HomeKinds {
    const val FILM = "film"
    const val SERIES = "series"
}

@Entity(tableName = "sync_state", primaryKeys = ["receiverKey", "kind"])
data class SyncStateRow(
    val receiverKey: String,
    val kind: String,
    val lastSuccessMs: Long,
    val lastAttemptMs: Long,
    val error: String,
    /** CHANNELS only: ChannelListHash of the lists last stored for this receiver. */
    @androidx.room.ColumnInfo(defaultValue = "") val contentHash: String = "",
)

/** IPTV playlist categories (Settings › IPTV › Playlist). [source] "playlist", or "playlist.new" while loading. */
@androidx.room.Entity(tableName = "iptv_category", primaryKeys = ["source", "section", "id"])
data class IptvCategoryRow(
    val source: String,
    val section: String,
    val id: String,
    val name: String,
    val sort: Int,
)

/** IPTV playlist channels, films and series. Lean rows: no descriptions, so pages stay small. */
@androidx.room.Entity(
    tableName = "iptv_item",
    primaryKeys = ["source", "section", "categoryId", "id"],
    indices = [androidx.room.Index(value = ["source", "section", "categoryId", "sort"])],
)
data class IptvItemRow(
    val source: String,
    val section: String,
    val categoryId: String,
    val id: String,
    val name: String,
    val icon: String,
    val extension: String,
    val number: Int,
    val url: String,
    val sort: Int,
    @androidx.room.ColumnInfo(defaultValue = "0") val archiveDays: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "''") val catchupSource: String = "",
)

/**
 * Now/next of an IPTV channel row (Xtream short EPG or listings), per account and stream, so the
 * channel list has them at once after a restart. [events] is [com.enigmacast.domain.xtream.RowGuide.encode].
 */
@androidx.room.Entity(tableName = "iptv_row_epg", primaryKeys = ["account", "streamId"])
data class IptvRowEpgRow(
    val account: String,
    val streamId: String,
    val fetchedAtMs: Long,
    val events: String,
)

/** A receiver channel name and its guide key (not a table). */
data class ChannelNameRow(val name: String, val serviceKey: String)
