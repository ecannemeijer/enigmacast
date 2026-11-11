package com.enigmacast.domain.xtream

/**
 * What OK on an IPTV TV-guide cell should do. Pure, so the choices can be tested without Android.
 */
enum class IptvGuideOk { WATCH_LIVE, PLAY_CATCHUP, PROMPT_FUTURE, NONE }

object IptvGuideAction {
    fun decide(item: XtreamItem?, event: XtreamEpg?, nowSec: Long): IptvGuideOk {
        if (item == null || event == null) return IptvGuideOk.NONE
        if (event.startSec <= nowSec && nowSec < event.endSec) return IptvGuideOk.WATCH_LIVE
        if (event.endSec <= nowSec && Catchup.canPlay(item, event, nowSec)) return IptvGuideOk.PLAY_CATCHUP
        if (event.startSec > nowSec) return IptvGuideOk.PROMPT_FUTURE
        return IptvGuideOk.NONE
    }

    fun toEvent(title: String, startSec: Long, endSec: Long, description: String = ""): XtreamEpg =
        XtreamEpg(title, startSec, endSec, description)
}
