package com.enigmacast.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.enigmacast.BuildConfig
import com.enigmacast.R
import com.enigmacast.util.EnigmaLog
import com.enigmacast.util.UiText
import com.enigmacast.util.UiTextException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUpdateClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // GitHub release downloads: github.com -> release-assets.githubusercontent.com (both https).
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun check(): RemoteRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(UPDATE_MANIFEST_URL).header("Cache-Control", "no-cache").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                EnigmaLog.connection("update check HTTP ${response.code}")
                return@withContext null
            }
            val body = response.body.string()
            json.decodeFromString<RemoteRelease>(body)
        }
    }

    /**
     * Downloads the APK into the cache. [onProgress] gets a whole percent only when it changes
     * (-1 now and then when the size is unknown) and 100 at the end. The file is written next to
     * the target and only renamed when complete; a cancelled or failed download leaves nothing.
     */
    suspend fun download(release: RemoteRelease, onProgress: suspend (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val url = release.apkUrl.ifBlank { UPDATE_APK_URL }
        val request = Request.Builder().url(url).build()
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "enigmacast-update.apk")
        val part = File(dir, "enigmacast-update.apk.part")
        val call = client.newCall(request)
        // Cancelling the coroutine also aborts a read that is blocked on the network.
        val onCancel = coroutineContext[Job]?.invokeOnCompletion { if (it is CancellationException) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw UiTextException(UiText.res(R.string.error_download_failed_code, response.code), "Download failed (${response.code})")
                }
                val body = response.body
                val total = body.contentLength()
                part.delete()
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        UpdateDownload.copy(input, output, total, onProgress, checkActive = { ensureActive() })
                    }
                }
            }
            target.delete()
            if (!part.renameTo(target)) throw java.io.IOException("Could not store the update")
            target
        } catch (e: Throwable) {
            part.delete()
            if (e is java.io.IOException && !isActive) throw CancellationException("update download cancelled")
            throw e
        } finally {
            onCancel?.dispose()
        }
    }

    fun canRequestInstall(): Boolean {
        return context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
