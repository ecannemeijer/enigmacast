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

@Entity(tableName = "channels", primaryKeys = ["receiverKey", "bouquetReference", "reference"])
data class ChannelRow(
    val receiverKey: String,
    val bouquetReference: String,
    val reference: String,
    val name: String,
    val picon: String,
    val number: Int,
    val position: Int,
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
)

@Entity(tableName = "sync_state", primaryKeys = ["receiverKey", "kind"])
data class SyncStateRow(
    val receiverKey: String,
    val kind: String,
    val lastSuccessMs: Long,
    val lastAttemptMs: Long,
    val error: String,
)
