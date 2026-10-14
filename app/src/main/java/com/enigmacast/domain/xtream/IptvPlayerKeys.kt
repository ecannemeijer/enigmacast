package com.enigmacast.domain.xtream

import android.view.KeyEvent
import com.enigmacast.domain.remote.Bindings
import com.enigmacast.domain.remote.KeyBindings
import com.enigmacast.domain.remote.PlayerAction

/** What a key does in the IPTV full player. */
sealed interface IptvKeyAction {
    data class Zap(val step: Int) : IptvKeyAction
    data object Banner : IptvKeyAction
    data object Details : IptvKeyAction
    data object Options : IptvKeyAction
    data object ChannelList : IptvKeyAction
    data object Pause : IptvKeyAction
    data object None : IptvKeyAction
}

/**
 * The key state machine of the Enigma2 own-tuner player (RemoteDisplayViewModel.handleOwnKey),
 * for IPTV: the same Settings › Remote control keys (Up = next channel, Down = previous),
 * OK without a banner shows it, OK on the banner pauses (films) or opens the list (live),
 * and Down opens the options only on a banner that OK, Info or Right brought up; after a
 * zap Down keeps zapping.
 */
object IptvPlayerKeys {
    fun action(keyCode: Int, bindings: Bindings, banner: Boolean, bannerFromZap: Boolean, vod: Boolean): IptvKeyAction {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && banner && !bannerFromZap) return IptvKeyAction.Options
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            return when {
                !banner -> IptvKeyAction.Banner
                vod -> IptvKeyAction.Pause
                else -> IptvKeyAction.ChannelList
            }
        }
        return when (KeyBindings.lookup(bindings, keyCode, long = false)) {
            PlayerAction.CHANNEL_UP -> IptvKeyAction.Zap(1)
            PlayerAction.CHANNEL_DOWN -> IptvKeyAction.Zap(-1)
            PlayerAction.CHANNEL_LIST -> IptvKeyAction.ChannelList
            PlayerAction.INFO -> IptvKeyAction.Details
            PlayerAction.BANNER -> IptvKeyAction.Banner
            PlayerAction.OPTIONS, PlayerAction.AUDIO_TRACK, PlayerAction.SUBTITLES, PlayerAction.PICTURE_MODE -> IptvKeyAction.Options
            PlayerAction.PAUSE -> if (vod) IptvKeyAction.Pause else IptvKeyAction.None
            else -> IptvKeyAction.None
        }
    }
}

/** Channel names of a playlist and of the receiver, made comparable: "NL: NPO 1 HD" → "npo1". */
object IptvNames {
    private val PREFIX = Regex("^\\s*[A-Za-z]{2,3}\\s*[:|]\\s*")
    private val SUFFIX = Regex("\\b(fhd|uhd|hd|sd|4k|hevc|h265|backup|\\+1)\\b")
    private val NON = Regex("[^a-z0-9]")

    fun normalize(name: String): String {
        var s = name.lowercase().replace(PREFIX, "").replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
        s = s.replace(SUFFIX, " ")
        return s.replace(NON, "")
    }
}
