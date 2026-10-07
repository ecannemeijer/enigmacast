package com.enigmacast.ui.remote

import com.enigmacast.R
import com.enigmacast.domain.epg.ProgrammePrompt
import com.enigmacast.live.ReminderScheduler
import com.enigmacast.util.UiText

/** The short message after AutoSwitch in the programme dialog. */
internal object AutoSwitchTexts {
    fun result(toggle: ReminderScheduler.Toggle, prompt: ProgrammePrompt): UiText = when (toggle) {
        ReminderScheduler.Toggle.SET -> UiText.res(R.string.autoswitch_set_notice, prompt.event.title, prompt.channelName)
        ReminderScheduler.Toggle.CANCELLED -> UiText.res(R.string.autoswitch_cancelled_notice, prompt.event.title)
        ReminderScheduler.Toggle.ALREADY_BEGUN -> UiText.res(R.string.autoswitch_already_begun)
    }
}
