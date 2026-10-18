package com.enigmacast.torrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import com.enigmacast.R

/**
 * Keeps the app alive in the foreground while a torrent streams (the engine itself is a
 * singleton), with the CPU and Wi-Fi kept at full speed: Wi-Fi power save on TV sticks adds
 * latency to every peer round trip, which a swarm download feels much more than a single HTTP
 * stream.
 */
class TorrentStreamingService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        wakeLock = null
        super.onDestroy()
    }

    private fun holdLocks() {
        if (wifiLock == null) {
            wifiLock = runCatching {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
                applicationContext.getSystemService(WifiManager::class.java)
                    ?.createWifiLock(mode, "EnigmaCast:torrent")?.apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
        if (wakeLock == null) {
            wakeLock = runCatching {
                getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EnigmaCast:torrent")?.apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.torrent_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.torrent_notification))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        holdLocks()
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "torrent"
        private const val NOTIFICATION_ID = 4711

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, TorrentStreamingService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TorrentStreamingService::class.java)) }
        }
    }
}
