package com.enigmacast.ui.remote

import com.enigmacast.R
import com.enigmacast.domain.tuner.RestartBudget
import com.enigmacast.domain.tuner.StreamHealth
import com.enigmacast.util.UiText

/** The message for an internet / IPTV stream that gave up, or null for any other state. */
internal fun internetProblem(health: StreamHealth): UiText? = when (health) {
    StreamHealth.INTERNET_UNSUPPORTED -> UiText.res(R.string.internet_stream_unsupported)
    StreamHealth.INTERNET_FAILED -> UiText.res(R.string.internet_stream_failed, RestartBudget.MAX_ATTEMPTS)
    else -> null
}
