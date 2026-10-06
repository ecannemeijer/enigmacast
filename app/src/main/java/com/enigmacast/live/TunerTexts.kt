package com.enigmacast.live

import com.enigmacast.R
import com.enigmacast.domain.tuner.TunerUser
import com.enigmacast.domain.tuner.TunerUserKind
import com.enigmacast.util.UiText

/** "Receiver: NPO 1", "Recording: RTL 4", "Stream to 192.168.1.21: NPO 2". */
fun TunerUser.text(): UiText = when (kind) {
    TunerUserKind.LIVE -> UiText.res(R.string.tuner_user_live, name)
    TunerUserKind.RECORDING -> UiText.res(R.string.tuner_user_recording, name)
    TunerUserKind.STREAM -> UiText.res(R.string.tuner_user_stream, address, name)
}
