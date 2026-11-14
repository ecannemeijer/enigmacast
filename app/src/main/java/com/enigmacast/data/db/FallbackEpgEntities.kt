package com.enigmacast.data.db

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "fallback_epg_meta", primaryKeys = ["country"])
data class FallbackEpgMetaRow(
    val country: String,
    val fetchedAtMs: Long,
    val programmeCount: Int,
)

@Entity(
    tableName = "fallback_epg_channel",
    primaryKeys = ["xmltvId"],
    indices = [Index("country"), Index("nameKey")],
)
data class FallbackEpgChannelRow(
    val xmltvId: String,
    val country: String,
    val displayName: String,
    val nameKey: String,
)

@Entity(
    tableName = "fallback_epg_programme",
    primaryKeys = ["xmltvId", "beginSec"],
    indices = [Index(value = ["xmltvId", "endSec"])],
)
data class FallbackEpgProgrammeRow(
    val xmltvId: String,
    val beginSec: Long,
    val endSec: Long,
    val title: String,
    val description: String,
)
