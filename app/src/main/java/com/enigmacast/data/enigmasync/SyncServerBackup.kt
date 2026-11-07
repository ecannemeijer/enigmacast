package com.enigmacast.data.enigmasync

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** One backup archive kept on this TV. */
data class TvSyncBackup(val file: File, val sizeBytes: Long, val createdMs: Long)

/**
 * Copies the sync server's data folder to this TV and back, over SSH (same login as the installer).
 *
 * The archive is a plain tar.gz of sync.db, master.key, cert.pem, key.pem and web-auth.json: with
 * those a newly installed server has the same profiles, history, encrypted IPTV logins, devices and
 * certificate, so every paired TV and the dashboard password keep working. Works on every server
 * version (no API needed). Archives live in the app's private storage; [export] copies one to the
 * app folder on shared storage. The password is used for one call and never stored.
 */
@Singleton
class SyncServerBackup @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir: File get() = File(context.filesDir, "sync-backups").apply { mkdirs() }

    fun list(): List<TvSyncBackup> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".tar.gz") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { TvSyncBackup(it, it.length(), it.lastModified()) }

    /** Stops the services for a consistent copy, packs the data files, starts them again, downloads it. */
    fun backup(host: String, user: String, password: String): TvSyncBackup = session(host, user, password) { session ->
        val dataDir = dataDir(session)
        val bytes = execBytes(session, """
            set -e
            cd '$dataDir'
            /etc/init.d/enigmacast-web stop >/dev/null 2>&1 || true
            /etc/init.d/enigmacast-sync stop >/dev/null 2>&1 || true
            files=''
            for f in $FILES; do [ -f "${'$'}f" ] && files="${'$'}files ${'$'}f"; done
            tar czf - ${'$'}files || status=${'$'}?
            /etc/init.d/enigmacast-sync start >/dev/null 2>&1 || true
            /etc/init.d/enigmacast-web start >/dev/null 2>&1 || true
            exit ${'$'}{status:-0}
        """.trimIndent())
        if (bytes.size < 64 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) error("no archive received from the receiver")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "enigmacast-sync-$stamp.tar.gz")
        file.writeBytes(bytes)
        TvSyncBackup(file, file.length(), file.lastModified())
    }

    /**
     * Uploads [backup] and replaces the data files of the server installed on [host] (install it
     * first on a new box). Only the known file names are accepted and sync.db must be present.
     * The current files go to <data>/backups/before-tv-restore-*.tar.gz first. Returns the data folder.
     */
    fun restore(host: String, user: String, password: String, backup: File): String = session(host, user, password) { session ->
        val dataDir = dataDir(session)
        val upload = "/tmp/enigmacast-sync-restore.tar.gz"
        exec(session, "umask 077; cat > '$upload'", backup.readBytes())
        val result = exec(session, """
            set -e
            names=${'$'}(tar tzf '$upload')
            for n in ${'$'}names; do
              case "${'$'}n" in ${FILES.replace(' ', '|')}|./sync.db|./master.key|./cert.pem|./key.pem|./web-auth.json) ;; *) echo "bad-member ${'$'}n"; rm -f '$upload'; exit 3;; esac
            done
            echo "${'$'}names" | grep -q 'sync.db' || { echo no-db; rm -f '$upload'; exit 4; }
            /etc/init.d/enigmacast-web stop >/dev/null 2>&1 || true
            /etc/init.d/enigmacast-sync stop >/dev/null 2>&1 || true
            mkdir -p '$dataDir/backups'
            cd '$dataDir'
            old=''
            for f in $FILES; do [ -f "${'$'}f" ] && old="${'$'}old ${'$'}f"; done
            [ -n "${'$'}old" ] && tar czf "backups/before-tv-restore-${'$'}(date +%Y%m%d-%H%M%S).tar.gz" ${'$'}old
            rm -f sync.db-wal sync.db-shm
            tar xzf '$upload' -C '$dataDir'
            rm -f '$upload'
            chmod 600 master.key key.pem web-auth.json 2>/dev/null || true
            if id enigmacast >/dev/null 2>&1; then chown enigmacast $FILES 2>/dev/null || true; fi
            /etc/init.d/enigmacast-sync start >/dev/null 2>&1 || true
            /etc/init.d/enigmacast-web start >/dev/null 2>&1 || true
            echo restored
        """.trimIndent())
        if (!result.contains("restored")) error(result.trim().ifBlank { "restore failed" })
        dataDir
    }

    /** Copies [backup] to the app folder on shared storage (reachable with a file manager or USB). */
    fun export(backup: File): File {
        val target = File(context.getExternalFilesDir(null) ?: error("no shared storage"), "sync-backups").apply { mkdirs() }
        return File(target, backup.name).also { backup.copyTo(it, overwrite = true) }
    }

    fun delete(backup: File) {
        if (backup.parentFile?.canonicalPath == dir.canonicalPath) backup.delete()
    }

    private fun <T> session(host: String, user: String, password: String, work: (Session) -> T): T {
        val session = JSch().getSession(user, host, 22)
        session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "password")
        try {
            session.connect(20_000)
            return work(session)
        } finally {
            session.disconnect()
        }
    }

    /** The folder of the running server: the one with cert.pem (a disk first, then /etc). */
    private fun dataDir(session: Session): String {
        val found = exec(session, """
            for d in /media/hdd /media/usb /media/mmc /media/cf; do
              if awk -v p="${'$'}d" '${'$'}2 == p { f=1 } END { exit f ? 0 : 1 }' /proc/mounts && [ -f "${'$'}d/enigmacast-sync/cert.pem" ]; then echo "${'$'}d/enigmacast-sync"; exit 0; fi
            done
            [ -f /etc/enigmacast-sync/cert.pem ] && echo /etc/enigmacast-sync || true
        """.trimIndent()).trim().lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
        if (found.isBlank()) error("no sync server data folder on the receiver; install the sync server first")
        return found
    }

    private fun exec(session: Session, command: String, input: ByteArray? = null): String =
        String(execBytes(session, command, input), Charsets.UTF_8)

    private fun execBytes(session: Session, command: String, input: ByteArray? = null): ByteArray {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        channel.outputStream = stdout
        channel.setErrStream(stderr)
        if (input != null) channel.inputStream = ByteArrayInputStream(input)
        channel.connect(20_000)
        val started = System.currentTimeMillis()
        while (!channel.isClosed) {
            if (System.currentTimeMillis() - started > 180_000) break
            Thread.sleep(30)
        }
        channel.disconnect()
        val status = channel.exitStatus
        if (status != 0) {
            val text = String(stdout.toByteArray(), Charsets.UTF_8).takeIf { input != null || stdout.size() < 4096 }.orEmpty()
            error((text + String(stderr.toByteArray(), Charsets.UTF_8)).trim().ifBlank { "SSH command failed ($status)" })
        }
        return stdout.toByteArray()
    }

    private companion object {
        const val FILES = "sync.db master.key cert.pem key.pem web-auth.json"
    }
}
