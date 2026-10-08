package com.enigmacast.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Starts a due TV recording, and arms the saved ones again after a reboot. */
@AndroidEntryPoint
class RecordingAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var book: RecordingBook

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val id = if (intent.action == Intent.ACTION_BOOT_COMPLETED) null else intent.getStringExtra(RecordingCaptureService.EXTRA_ID)
        book.enqueue(id) { pending.finish() }
    }

    companion object {
        const val ACTION_START = "com.enigmacast.record.START"
    }
}
