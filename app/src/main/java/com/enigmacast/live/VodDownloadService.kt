package com.enigmacast.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.enigmacast.R
import com.enigmacast.data.download.VodDownloadResult
import com.enigmacast.data.download.VodDownloadStore
import com.enigmacast.data.download.VodDownloader
import com.enigmacast.domain.download.VodDownload
import com.enigmacast.domain.download.VodDownloadState
import com.enigmacast.domain.xtream.IptvHeaders
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Downloads one IPTV film or episode at a time, with notification progress, pause and Range resume. */
@AndroidEntryPoint
class VodDownloadService : Service() {
    @Inject lateinit var store: VodDownloadStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloader = VodDownloader()
    private val paused = AtomicBoolean(false)
    private val canceled = AtomicBoolean(false)
    private val currentId = AtomicReference<String?>(null)
    private var worker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        when (intent?.action) {
            ACTION_PAUSE -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                paused.set(true)
                scope.launch { store.pause(id) }
            }
            ACTION_RESUME -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                paused.set(false)
                scope.launch { store.resume(id); pump() }
            }
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                canceled.set(true)
                scope.launch { store.cancel(id) }
            }
            else -> pump()
        }
        return START_STICKY
    }

    private fun pump() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val next = store.nextQueued() ?: break
                if (next.state == VodDownloadState.PAUSED) break
                runOne(next)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun runOne(item: VodDownload) {
        if (!store.hasRoom(item)) {
            store.setState(item.id, VodDownloadState.FAILED, "no_space")
            notify(item.copy(state = VodDownloadState.FAILED, error = "no_space"))
            return
        }
        paused.set(false)
        canceled.set(false)
        currentId.set(item.id)
        store.setState(item.id, VodDownloadState.RUNNING)
        notify(item.copy(state = VodDownloadState.RUNNING))
        val dest = store.fileFor(item)
        val headers = IptvHeaders.resolve(
            defaultUserAgent = item.userAgent,
            providerUserAgent = item.userAgent,
            providerHeaders = IptvHeaders.parseLines(item.httpHeaders),
            streamUserAgent = "",
            streamHeaders = emptyMap(),
        )
        var lastProgressAt = 0L
        val result: VodDownloadResult = downloader.download(
            url = item.url,
            destination = dest,
            headers = headers,
            cancel = { canceled.get() && currentId.get() == item.id },
            paused = { paused.get() && currentId.get() == item.id },
            onProgress = { downloaded, total ->
                val now = System.currentTimeMillis()
                if (now - lastProgressAt < 500L) return@download
                lastProgressAt = now
                scope.launch { store.setState(item.id, VodDownloadState.RUNNING, downloadedBytes = downloaded, totalBytes = total) }
                notify(item.copy(state = VodDownloadState.RUNNING, downloadedBytes = downloaded, totalBytes = total))
            },
        )
        when {
            canceled.get() && currentId.get() == item.id -> store.setState(item.id, VodDownloadState.CANCELED, downloadedBytes = result.downloadedBytes, totalBytes = result.totalBytes)
            result.ok -> store.setState(item.id, VodDownloadState.DONE, downloadedBytes = result.downloadedBytes, totalBytes = result.totalBytes)
            result.noSpace -> store.setState(item.id, VodDownloadState.FAILED, "no_space", result.downloadedBytes, result.totalBytes)
            result.error == "paused" -> store.setState(item.id, VodDownloadState.PAUSED, downloadedBytes = result.downloadedBytes, totalBytes = result.totalBytes)
            result.retryable -> {
                store.setState(item.id, VodDownloadState.QUEUED, result.error, result.downloadedBytes, result.totalBytes)
                delay(15_000)
            }
            else -> store.setState(item.id, VodDownloadState.FAILED, result.error, result.downloadedBytes, result.totalBytes)
        }
        currentId.set(null)
    }

    private fun ensureForeground(title: String = getString(R.string.iptv_downloads), text: String = getString(R.string.iptv_download_queued)) {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.iptv_download_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: Throwable) {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(item: VodDownload) {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.iptv_download_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val progress = if (item.totalBytes > 0) ((item.downloadedBytes * 100) / item.totalBytes).toInt().coerceIn(0, 100) else 0
        val indeterminate = item.totalBytes <= 0L && item.state == VodDownloadState.RUNNING
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(item.title)
            .setContentText(statusText(item))
            .setOnlyAlertOnce(true)
            .setOngoing(item.state == VodDownloadState.RUNNING || item.state == VodDownloadState.QUEUED)
            .setProgress(100, progress, indeterminate)
            .addAction(0, getString(R.string.iptv_download_pause), action(ACTION_PAUSE, item.id))
            .addAction(0, getString(R.string.iptv_download_resume), action(ACTION_RESUME, item.id))
            .addAction(0, getString(R.string.iptv_download_cancel), action(ACTION_CANCEL, item.id))
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: Throwable) {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun statusText(item: VodDownload): String = when (item.state) {
        VodDownloadState.QUEUED -> getString(R.string.iptv_download_queued)
        VodDownloadState.RUNNING -> getString(R.string.iptv_download_running)
        VodDownloadState.PAUSED -> getString(R.string.iptv_download_paused)
        VodDownloadState.FAILED -> getString(R.string.iptv_download_failed)
        VodDownloadState.DONE -> getString(R.string.iptv_download_done)
        VodDownloadState.CANCELED -> getString(R.string.iptv_download_cancel)
    }

    private fun action(action: String, id: String): PendingIntent {
        val intent = Intent(this, VodDownloadService::class.java).setAction(action).putExtra(EXTRA_ID, id)
        val request = action.hashCode() + id.hashCode()
        return PendingIntent.getService(this, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val ACTION_PAUSE = "com.enigmacast.download.PAUSE"
        const val ACTION_RESUME = "com.enigmacast.download.RESUME"
        const val ACTION_CANCEL = "com.enigmacast.download.CANCEL"
        const val EXTRA_ID = "id"
        private const val CHANNEL = "vod_downloads"
        private const val NOTIFICATION_ID = 74

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, VodDownloadService::class.java)) }
        }
    }
}
