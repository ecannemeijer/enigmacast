package com.enigmacast.hotel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.enigmacast.MainActivity
import com.enigmacast.data.hotel.HotelStore
import com.enigmacast.domain.hotel.HotelConfig
import kotlinx.serialization.json.Json

/**
 * Hotel mode: after the TV starts, open EnigmaCast in live TV. Android 10 and later may refuse an
 * activity start from the background; on those TVs the HOME alias (EnigmaCast as launcher) is
 * what reliably brings the app up, this receiver is the best effort for TVs that allow it.
 */
class HotelBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext
        Thread({
            try {
                if (enabled(app)) {
                    runCatching {
                        app.startActivity(
                            Intent(app, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                        )
                    }
                }
            } finally {
                pending.finish()
            }
        }, "hotel-boot").start()
    }

    private fun enabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(HotelStore.FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString("config", null) ?: return false
        val json = Json { ignoreUnknownKeys = true }
        return runCatching { json.decodeFromString(HotelConfig.serializer(), raw).enabled }.getOrDefault(false) &&
            prefs.getBoolean("pin_set", false)
    }
}
