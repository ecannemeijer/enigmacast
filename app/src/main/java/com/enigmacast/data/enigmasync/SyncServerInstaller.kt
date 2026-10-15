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

data class InstallReport(
    val model: String,
    val python: String,
    val dataDir: String,
    val pairingCode: String,
    val pin: String,
    val dashboardUrl: String = "",
    val dashboardPassword: String = "",
)

/**
 * Installs the sync service over SSH from the latest public GitHub release IPK.
 * The password is used for this call and not stored. The package upgrades the API and the web dashboard
 * without removing the data directory.
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
            val init = exec(session, "PYTHONPATH='$remote' python3 -m enigmacast_sync.cli --data-dir '$dataDir' init || true")
            exec(session, "/etc/init.d/enigmacast-sync restart || true; /etc/init.d/enigmacast-web restart || true")
            val cert = exec(session, "cat '$dataDir/cert.pem'")
            val pin = pinOf(cert)
            var webPassword = ""
            repeat(8) {
                if (webPassword.isBlank()) {
                    webPassword = exec(session, "cat '$dataDir/web-password-once' 2>/dev/null || true").trim()
                    if (webPassword.isBlank()) exec(session, "sleep 1")
                }
            }
            if (webPassword.isNotBlank()) exec(session, "rm -f '$dataDir/web-password-once'")
            val scheme = if (cert.contains("BEGIN CERTIFICATE")) "https" else "http"
            val code = Regex(""""pairingCode"\s*:\s*"(\d+)"""").find(init)?.groupValues?.get(1).orEmpty()
            return InstallReport(model, python, dataDir, code, pin, "$scheme://$host:8766/", webPassword)
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
        const val RELEASE = "https://api.github.com/repos/ecannemeijer/enigmacast-enigma2-sync/releases/latest"
        val FETCH = """
            set -eu
            rm -rf /tmp/enigmacast-sync-release /tmp/enigmacast-sync.ipk
            mkdir -p /tmp/enigmacast-sync-release
            if ! wget -qO /tmp/enigmacast-sync-release/release.json '$RELEASE'; then
              python3 -c 'import urllib.request; urllib.request.urlretrieve("$RELEASE", "/tmp/enigmacast-sync-release/release.json")'
            fi
            python3 - <<'PY'
            import json
            data = json.load(open("/tmp/enigmacast-sync-release/release.json", encoding="utf-8"))
            url = ""
            for asset in data.get("assets") or []:
                name = asset.get("name") or ""
                if name.endswith("_all.ipk"):
                    url = asset.get("browser_download_url") or ""
                    break
            if not url:
                raise SystemExit("release has no ipk")
            open("/tmp/enigmacast-sync-release/url", "w", encoding="utf-8").write(url)
            PY
            URL=\$(cat /tmp/enigmacast-sync-release/url)
            if ! wget -O /tmp/enigmacast-sync.ipk "\$URL"; then
              python3 -c 'import urllib.request; urllib.request.urlretrieve(open("/tmp/enigmacast-sync-release/url", encoding="utf-8").read().strip(), "/tmp/enigmacast-sync.ipk")'
            fi
            opkg install --force-reinstall --force-overwrite /tmp/enigmacast-sync.ipk
            test -d /usr/lib/enigma2/python/Plugins/Extensions/EnigmaCastSync/enigmacast_sync/web
        """.trimIndent()
    }
}
