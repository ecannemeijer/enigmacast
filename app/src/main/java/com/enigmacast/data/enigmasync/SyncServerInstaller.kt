package com.enigmacast.data.enigmasync

import android.content.Context
import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton

data class InstallReport(val model: String, val python: String, val dataDir: String, val pairingCode: String, val pin: String)

/**
 * Installs the sync service over SSH. The password is used for this call and not stored.
 * Only the EnigmaCast Sync directory and init script are written.
 */
@Singleton
class SyncServerInstaller @Inject constructor(@ApplicationContext private val context: Context) {
    fun install(host: String, user: String, password: String): InstallReport {
        val session = JSch().getSession(user, host, 22)
        session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "no")
        try {
            session.connect(12_000)
            val model = exec(session, "cat /proc/stb/info/model 2>/dev/null || echo unknown").trim()
            val python = exec(session, "python3 --version").trim()
            val dataDir = exec(
                session,
                "for d in /media/hdd /media/usb /media/mmc /media/cf; do [ -d \"\$d\" ] && [ -w \"\$d\" ] && echo \"\$d/enigmacast-sync\" && exit 0; done; echo NO_EXTERNAL; exit 1",
            ).trim()
            if (dataDir == "NO_EXTERNAL" || dataDir.isBlank()) error("No writable external storage on the receiver")
            val remote = "/usr/lib/enigma2/python/Plugins/Extensions/EnigmaCastSync"
            exec(session, "mkdir -p '$remote/enigmacast_sync' '$dataDir'")
            uploadTree(session, remote)
            exec(session, "cp '$remote/enigmacast-sync' /etc/init.d/enigmacast-sync && chmod 755 /etc/init.d/enigmacast-sync")
            val init = exec(session, "PYTHONPATH='$remote' python3 -m enigmacast_sync.cli --data-dir '$dataDir' init || true")
            exec(session, "/etc/init.d/enigmacast-sync restart")
            val cert = exec(session, "cat '$dataDir/cert.pem'")
            val pin = pinOf(cert)
            val code = Regex(""""pairingCode"\s*:\s*"(\d+)"""").find(init)?.groupValues?.get(1)
                ?: error("Server did not return a pairing code. It may already be initialized.")
            return InstallReport(model, python, dataDir, code, pin)
        } finally {
            session.disconnect()
        }
    }

    /** Reads the existing server certificate. Does not install, restart or change the receiver. */
    fun certificatePin(host: String, user: String, password: String): String {
        val session = JSch().getSession(user, host, 22)
        session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "no")
        try {
            session.connect(12_000)
            val dataDir = exec(
                session,
                "for d in /media/hdd /media/usb /media/mmc /media/cf; do [ -f \"\$d/enigmacast-sync/cert.pem\" ] && echo \"\$d/enigmacast-sync\" && exit 0; done; exit 1",
            ).trim()
            if (dataDir.isBlank()) error("Sync server certificate was not found")
            return pinOf(exec(session, "cat '$dataDir/cert.pem'"))
        } finally {
            session.disconnect()
        }
    }

    private fun uploadTree(session: com.jcraft.jsch.Session, remote: String) {
        val channel = session.openChannel("sftp") as ChannelSftp
        channel.connect(12_000)
        try {
            fun walk(asset: String, dest: String) {
                val children = context.assets.list(asset).orEmpty()
                if (children.isEmpty()) {
                    context.assets.open(asset).use { input -> channel.put(input, dest) }
                } else {
                    runCatching { channel.mkdir(dest) }
                    children.forEach { child -> walk("$asset/$child", "$dest/$child") }
                }
            }
            walk("enigmacast-sync", remote)
        } finally {
            channel.disconnect()
        }
    }

    private fun exec(session: com.jcraft.jsch.Session, command: String): String {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        channel.outputStream = stdout
        channel.setErrStream(stderr)
        channel.connect(20_000)
        while (!channel.isClosed) Thread.sleep(30)
        channel.disconnect()
        if (channel.exitStatus != 0 && stdout.toString().isBlank()) error(stderr.toString().ifBlank { "SSH command failed" })
        return stdout.toString(Charsets.UTF_8)
    }

    private fun pinOf(pem: String): String {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }
}
