package com.enigmacast.playback

import android.content.Context
import android.content.SharedPreferences
import com.enigmacast.domain.player.SubtitleStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The subtitle look (size, colour, background) for every player, saved on this device.
 * Read and written off the main thread; every video view follows [style] and only redraws.
 */
object SubtitleStyleStore {
    private const val PREFS = "subtitle_style"
    private const val KEY = "style"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(SubtitleStyle.DEFAULT)
    val style: StateFlow<SubtitleStyle> = state

    @Volatile private var prefs: SharedPreferences? = null
    /** Chosen before the saved value was read: that choice wins and is written then. */
    @Volatile private var chosen: SubtitleStyle? = null

    fun init(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = SubtitleStyle.decode(p.getString(KEY, null))
            prefs = p
            val pending = chosen
            if (pending != null) p.edit().putString(KEY, pending.encode()).apply() else state.value = saved
        }
    }

    fun set(value: SubtitleStyle) {
        if (value == state.value) return
        chosen = value
        state.value = value
        scope.launch { prefs?.edit()?.putString(KEY, value.encode())?.apply() }
    }
}
