package com.enigmacast.data.enigmasync

import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton

data class InstallReport(val model: String, val python: String, val dataDir: String, val pairingCode: String, val pin: String)

/**
 * Installs the sync service over SSH by downloading the public GitHub repository on the receiver.
 * The password is used for this call and not stored. Only the EnigmaCast Sync directory and init script are written.
 */
@Singleton
class SyncServerInstaller @Inject constructor() {
    fun install(host: String, user: String, password: String): InstallReport {
        val session = JSch().getSession(user, host, 22)
        session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "password")
        try {
            session.connect(20_000)
            val model = exec(session, "cat /proc/stb/info/model 2>/dev/null || echo unknown").trim()
            val python = exec(session, "python3 --version").trim()
            val dataDir = exec(
                session,
                "for d in /media/hdd /media/usb /media/mmc /media/cf; do [ -d \"\$d\" ] && [ -w \"\$d\" ] && echo \"\$d/enigmacast-sync\" && exit 0; done; echo NO_EXTERNAL; exit 1",
            ).trim()
            if (dataDir == "NO_EXTERNAL" || dataDir.isBlank()) error("No writable external storage on the receiver")
            exec(session, FETCH)
            val remote = "/usr/lib/enigma2/python/Plugins/Extensions/EnigmaCastSync"
            exec(session, "rm -rf '$remote' && mkdir -p '$remote' && cp -a /tmp/enigmacast-sync-src/enigmacast_sync '$remote/' && cp /tmp/enigmacast-sync-src/packaging/enigmacast-sync /etc/init.d/enigmacast-sync && chmod 755 /etc/init.d/enigmacast-sync")
            val init = exec(session, "PYTHONPATH='$remote' python3 -m enigmacast_sync.cli --data-dir '$dataDir' init || true")
            exec(session, "/etc/init.d/enigmacast-sync restart; sleep 1; wget -qO- --no-check-certificate https://127.0.0.1:8740/api/v1/health || true")
            val cert = exec(session, "cat '$dataDir/cert.pem'")
            val pin = pinOf(cert)
            val code = Regex(""""pairingCode"\s*:\s*"(\d+)"""").find(init)?.groupValues?.get(1).orEmpty()
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
        session.setConfig("PreferredAuthentications", "password")
        try {
            session.connect(20_000)
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
        val text = String(stdout.toByteArray(), Charsets.UTF_8)
        if (channel.exitStatus != 0 && text.isBlank()) error(String(stderr.toByteArray(), Charsets.UTF_8).ifBlank { "SSH command failed" })
        return text
    }

    private fun pinOf(pem: String): String {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private companion object {
        const val REPO = "https://github.com/ecannemeijer/enigmacast-enigma2-sync/archive/refs/heads/main.tar.gz"
        val FETCH = """
            rm -rf /tmp/enigmacast-sync-src /tmp/enigmacast-sync.tar.gz
            if ! wget -O /tmp/enigmacast-sync.tar.gz '$REPO'; then
              python3 -c 'import urllib.request; urllib.request.urlretrieve("$REPO", "/tmp/enigmacast-sync.tar.gz")'
            fi
            mkdir -p /tmp/enigmacast-sync-src
            tar -xzf /tmp/enigmacast-sync.tar.gz -C /tmp/enigmacast-sync-src --strip-components=1
            test -d /tmp/enigmacast-sync-src/enigmacast_sync
        """.trimIndent()
    }
}
