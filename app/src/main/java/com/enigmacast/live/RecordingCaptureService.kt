package com.enigmacast.live

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
import com.enigmacast.R
import com.enigmacast.data.network.OkHttpClientFactory
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Writes one programme's stream to a file on this TV. A separate HTTP client is used so the
 * capture does not close the stream the viewer is watching, and the viewer does not close it.
 */
@AndroidEntryPoint
class RecordingCaptureService : Service() {
    @Inject lateinit var book: RecordingBook
    @Inject lateinit var clients: OkHttpClientFactory

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, Call>()
    private val stopping = ConcurrentHashMap.newKeySet<String>()
    private val jobsGate = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private var startCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(jobsGate) { startCount = startId }
        val id = intent?.getStringExtra(EXTRA_ID).orEmpty()
        if (id.isBlank()) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        startInForeground()
        if (intent?.action == ACTION_STOP) {
            stopping.add(id)
            calls.remove(id)?.cancel()
            if (!jobs.containsKey(id)) {
                scope.launch {
                    val file = book.get(id)?.let(book::fileFor)
                    book.markStopped(id, file?.length() ?: 0L)
                    stopIfIdle()
                }
            }
            return START_NOT_STICKY
        }
        synchronized(jobsGate) {
            if (jobs.containsKey(id)) return START_NOT_STICKY
            jobs[id] = scope.launch { capture(id) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        jobs.keys.forEach { stopping.add(it) }
        calls.values.forEach { runCatching { it.cancel() } }
        scope.cancel()
        wakeLock?.release()
        super.onDestroy()
    }

    private suspend fun capture(id: String) {
        var bytes = 0L
        var url = ""
        try {
            val item = book.get(id)
            if (item == null || item.endEpochSec <= nowSec()) {
                book.markFailed(id, getString(R.string.record_missed))
                return
            }
            val target = book.streamFor(item)
            if (target == null) {
                book.markFailed(id, getString(R.string.record_failed))
                return
            }
            book.markRecording(id)
            val file = book.fileFor(item)
            val auth = target.second
            url = target.first
            val response = try {
                open(id, url, auth)
            } catch (error: java.io.IOException) {
                val fallback = target.third
                if (fallback.isNullOrBlank() || fallback == url) throw error
                EnigmaLog.service("recording port refused, trying the stream port")
                url = fallback
                open(id, url, auth)
            }
            response.use { opened ->
                if (!opened.isSuccessful) {
                    book.markFailed(id, getString(if (opened.code == 401) R.string.record_auth else R.string.record_no_tuner))
                    return
                }
                val body = opened.body
                val started = System.currentTimeMillis()
                file.outputStream().use { out ->
                    val input = body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (stopping.contains(id) || nowSec() >= item.endEpochSec) {
                            out.flush()
                            break
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        bytes += read
                        if (bytes < RecordingBook.MIN_PLAY_BYTES && System.currentTimeMillis() - started > NO_DATA_MS) {
                            book.markFailed(id, getString(R.string.record_no_tuner))
                            return
                        }
                    }
                }
            }
            if (stopping.remove(id)) {
                book.markStopped(id, savedBytes(id, bytes))
            } else if (bytes < RecordingBook.MIN_PLAY_BYTES) {
                book.markFailed(id, getString(R.string.record_no_tuner))
            } else {
                book.markReady(id)
                EnigmaLog.service("recording saved ${item.title} ($bytes bytes)")
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) { book.markStopped(id, savedBytes(id, bytes)) }
            throw cancelled
        } catch (error: Exception) {
            if (stopping.remove(id)) {
                EnigmaLog.service("recording stopped id=$id bytes=$bytes")
                withContext(NonCancellable) { book.markStopped(id, savedBytes(id, bytes)) }
            } else {
                EnigmaLog.service("recording failed: ${error.javaClass.simpleName}")
                if (bytes >= RecordingBook.MIN_PLAY_BYTES) book.markReady(id)
                else {
                    val encoder = error is java.net.ConnectException && url.contains("bitrate=")
                    book.markFailed(id, getString(if (encoder) R.string.record_encoder else R.string.record_failed))
                }
            }
        } finally {
            calls.remove(id)
            val idle = synchronized(jobsGate) {
                jobs.remove(id)
                jobs.isEmpty()
            }
            if (idle) stopIfIdle()
        }
    }

    /** Bytes read, or what is already in the file when the socket closed early. */
    private suspend fun savedBytes(id: String, read: Long): Long {
        val onDisk = book.get(id)?.let(book::fileFor)?.takeIf { it.exists() }?.length() ?: 0L
        return maxOf(read, onDisk)
    }

    private fun open(id: String, url: String, authorization: String?): okhttp3.Response {
        val request = Request.Builder().url(url).apply {
            authorization?.let { header("Authorization", it) }
        }.build()
        // streamClient() is not the player's one-stream gate, so both can stay open.
        val call = clients.streamClient().newCall(request)
        calls[id] = call
        return call.execute()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.record_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.record_notification))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (wakeLock == null) {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EnigmaCast:record").apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_MS)
            }
        }
    }

    private fun stopIfIdle() {
        val token = synchronized(jobsGate) {
            if (jobs.isNotEmpty()) return
            startCount
        }
        wakeLock?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        // An older stop must not destroy a capture that started after it.
        stopSelf(token)
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    companion object {
        const val EXTRA_ID = "recording_id"
        const val ACTION_STOP = "com.enigmacast.record.STOP"
        private const val CHANNEL = "recordings"
        private const val NOTIFICATION_ID = 42
        private const val NO_DATA_MS = 12_000L
        private const val MAX_WAKE_MS = 6 * 60 * 60 * 1000L

        fun startIntent(context: Context, id: String): Intent =
            Intent(context, RecordingCaptureService::class.java).putExtra(EXTRA_ID, id)

        fun stopIntent(context: Context, id: String): Intent =
            Intent(context, RecordingCaptureService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, id)
    }
}
