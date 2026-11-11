package com.enigmacast.domain.recording

import com.enigmacast.domain.epg.GuideEvent

/** Confirm step for an IPTV recording (live now, or a future programme in the guide). */
data class IptvRecordAsk(
    val event: GuideEvent,
    val channelName: String,
    val providerId: String,
    val streamId: String,
    val connectionWarning: Boolean = false,
    val maxConnections: Int? = null,
    val usedConnections: Int? = null,
)
