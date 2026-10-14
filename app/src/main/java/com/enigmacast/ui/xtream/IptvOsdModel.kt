package com.enigmacast.ui.xtream

import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ChannelGuide
import com.enigmacast.domain.model.EpgEvent
import com.enigmacast.domain.model.OsdInfoFields
import com.enigmacast.domain.osdinfo.CryptInfo
import com.enigmacast.domain.osdinfo.CryptState
import com.enigmacast.domain.osdinfo.Delivery
import com.enigmacast.domain.osdinfo.OsdInfo
import com.enigmacast.domain.osdinfo.SignalInfo
import com.enigmacast.domain.osdinfo.Unavailable
import com.enigmacast.domain.xtream.XtreamEpg
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the skin OSD gets for an IPTV stream: no satellite data, the playlist's short EPG as guide. */
object IptvOsdModel {
    /** Satellite position, signal/SNR, tuner, CA/FTA and transponder slots stay empty. */
    val FIELDS = OsdInfoFields(signal = false, satellite = false, tuner = false, encryption = false, technical = false)

    val INFO = OsdInfo(
        delivery = Delivery.IPTV,
        signal = SignalInfo.Missing(Unavailable.NOT_APPLICABLE),
        crypt = CryptInfo(CryptState.NOT_APPLICABLE),
    )

    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    fun guide(entry: BouquetEntry, epg: List<XtreamEpg>, nowSec: Long = System.currentTimeMillis() / 1000, zone: ZoneId = ZoneId.systemDefault()): ChannelGuide? {
        if (epg.isEmpty()) return null
        fun label(sec: Long) = clock.format(Instant.ofEpochSecond(sec).atZone(zone))
        val events = epg.sortedBy { it.startSec }.map {
            EpgEvent(
                title = it.title,
                shortDescription = "",
                plot = "",
                beginLabel = label(it.startSec),
                endLabel = label(it.endSec),
                beginEpochSec = it.startSec,
                durationSec = (it.endSec - it.startSec).coerceAtLeast(0),
                current = it.startSec <= nowSec && nowSec < it.endSec,
            )
        }
        return ChannelGuide(entry.name, entry.reference, events)
    }
}
