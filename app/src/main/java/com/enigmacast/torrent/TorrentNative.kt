package com.enigmacast.torrent

import android.content.Context
import android.os.Build
import com.enigmacast.util.EnigmaLog
import java.io.IOException
import java.util.zip.ZipFile

/** The device cannot run the torrent engine: no jlibtorrent binary for its ABI, or it would not load. */
class TorrentUnavailableException(val abi: String, detail: String) : IOException("torrent engine unavailable ($abi): $detail")

/**
 * Loads jlibtorrent's native library ourselves, before any jlibtorrent class is touched.
 *
 * libtorrent_jni loads the library in its static initialiser and throws when that fails; a
 * class whose initialiser failed stays broken for the rest of the process ("Rejecting re-init
 * on previously-failed class", then NoClassDefFoundError). By loading first and only touching
 * jlibtorrent classes once that worked, a missing or broken binary becomes a clear error and
 * a later attempt can still try again. Once loaded, the library's own loadLibrary is a no-op.
 */
object TorrentNative {
    const val VERSION = "2.0.12.9"
    /** The FrostWire Android jars ship lib/<abi>/libjlibtorrent-<version>.so. */
    val LIBRARY_NAMES = listOf("jlibtorrent-$VERSION", "jlibtorrent")

    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val abi = processAbi(context)
        val packaged = runCatching { packagedAbis(context.applicationInfo.sourceDir) }.getOrDefault(emptyList())
        EnigmaLog.player(
            "torrent native: SUPPORTED_ABIS=${Build.SUPPORTED_ABIS.joinToString(",")} process=$abi " +
                "libDir=${context.applicationInfo.nativeLibraryDir} apk=${packaged.joinToString(",").ifEmpty { "-" }}",
        )
        var last: Throwable? = null
        for (name in LIBRARY_NAMES) {
            EnigmaLog.player("torrent native: loadLibrary(lib$name.so)")
            try {
                System.loadLibrary(name)
                loaded = true
                EnigmaLog.player("torrent native: loaded lib$name.so ($abi)")
                return
            } catch (e: Throwable) {
                // UnsatisfiedLinkError (missing or not loadable) or SecurityException.
                last = e
                EnigmaLog.player("torrent native: lib$name.so failed: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
            }
        }
        throw TorrentUnavailableException(abi, last?.javaClass?.simpleName ?: "unknown")
    }

    /**
     * The ABI this process runs as. The app directory's lib folder names it ("arm", "arm64",
     * "x86", "x86_64"); otherwise the first ABI of the process bitness.
     */
    fun processAbi(context: Context): String {
        val dir = context.applicationInfo.nativeLibraryDir?.substringAfterLast('/').orEmpty()
        return abiOfLibDir(dir) ?: run {
            val abis = if (android.os.Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
            abis.firstOrNull() ?: Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        }
    }

    fun abiOfLibDir(dir: String): String? = when (dir) {
        "arm64" -> "arm64-v8a"
        "arm" -> "armeabi-v7a"
        "x86" -> "x86"
        "x86_64" -> "x86_64"
        else -> null
    }

    /** ABIs for which the APK carries the jlibtorrent binary. */
    private fun packagedAbis(apk: String): List<String> = ZipFile(apk).use { zip ->
        zip.entries().asSequence().map { it.name }.mapNotNull { abiOfEntry(it) }.distinct().sorted().toList()
    }

    /** "lib/arm64-v8a/libjlibtorrent-2.0.12.9.so" → "arm64-v8a". */
    fun abiOfEntry(name: String): String? {
        val parts = name.split('/')
        if (parts.size != 3 || parts[0] != "lib" || !parts[2].startsWith("libjlibtorrent") || !parts[2].endsWith(".so")) return null
        return parts[1]
    }
}
