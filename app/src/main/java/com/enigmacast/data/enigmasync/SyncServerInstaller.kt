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
            val dataDir = exec(session, pickDataDir(requireCert = false)).trim().lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
            if (dataDir.isBlank()) error("No sync data directory on the receiver")
            exec(session, FETCH)
            val remote = "/usr/lib/enigma2/python/Plugins/Extensions/EnigmaCastSync"
            val init = exec(session, "PYTHONPATH='$remote' python3 -m enigmacast_sync.cli --data-dir '$dataDir' init 2>&1 || true")
            exec(session, "/etc/init.d/enigmacast-sync restart || true; /etc/init.d/enigmacast-web restart || true")
            exec(session, setAdminPassword(dataDir))
            exec(session, "/etc/init.d/enigmacast-web restart || true")
            // The service creates the certificate after startup; on a slow receiver this can take
            // a moment. Also keep the error readable when python3-sqlite3 is still missing.
            val cert = exec(session, """
                i=0
                while [ ! -f '$dataDir/cert.pem' ] && [ ${'$'}i -lt 20 ]; do sleep 1; i=${'$'}((i+1)); done
                cat '$dataDir/cert.pem' 2>/dev/null || true
            """.trimIndent()).trim()
            if (!cert.contains("BEGIN CERTIFICATE")) {
                val details = exec(session, """
                    echo 'sync:'; tail -20 '$dataDir/logs/sync.log' 2>/dev/null || true
                    echo 'web:'; tail -20 '$dataDir/logs/web.log' 2>/dev/null || true
                    echo 'python:'; python3 -c 'import sqlite3; print(sqlite3.sqlite_version)' 2>&1 || true
                """.trimIndent()).trim()
                error("Sync-server startte niet. Controleer python3-sqlite3 op de receiver.\n$details")
            }
            val pin = pinOf(cert)
            val scheme = "https"
            val code = Regex(""""pairingCode"\s*:\s*"(\d+)"""").find(init)?.groupValues?.get(1).orEmpty()
            return InstallReport(model, python, dataDir, code, pin, "$scheme://$host:8766/", "admin")
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
            val dataDir = exec(session, pickDataDir(requireCert = true)).trim().lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
            if (dataDir.isBlank()) {
                error("Sync-server is niet actief: cert.pem ontbreekt. Controleer python3-sqlite3 en de sync-service.")
            }
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

    private fun setAdminPassword(dataDir: String): String = """
        python3 - '$dataDir' <<'PY'
        import base64, hashlib, json, secrets, sys
        from pathlib import Path
        data = Path(sys.argv[1])
        password = b"admin"
        salt = secrets.token_bytes(16)
        digest = hashlib.pbkdf2_hmac("sha256", password, salt, 200_000)
        (data / "web-auth.json").write_text(json.dumps({
            "salt": base64.b64encode(salt).decode("ascii"),
            "hash": base64.b64encode(digest).decode("ascii"),
            "iterations": 200_000,
        }), encoding="utf-8")
        once = data / "web-password-once"
        if once.is_file():
            once.unlink()
        PY
    """.trimIndent()

    private fun pinOf(pem: String): String {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    /**
     * A disk counts only when it is really mounted. An empty /media/hdd folder on the
     * internal flash is ignored. Without a disk the database lives in /etc/enigmacast-sync.
     */
    private fun pickDataDir(requireCert: Boolean): String {
        val cert = if (requireCert) """[ -f "${'$'}d/enigmacast-sync/cert.pem" ]""" else """[ -w "${'$'}d" ]"""
        val fallback = if (requireCert) {
            """if [ -f /etc/enigmacast-sync/cert.pem ]; then echo /etc/enigmacast-sync; exit 0; fi
            exit 1"""
        } else {
            "echo /etc/enigmacast-sync"
        }
        return """
            is_mount() {
              d=${'$'}1
              if command -v findmnt >/dev/null 2>&1; then
                findmnt -n "${'$'}d" >/dev/null 2>&1
              else
                awk -v p="${'$'}d" '${'$'}2 == p { found=1 } END { exit found ? 0 : 1 }' /proc/mounts
              fi
            }
            for d in /media/hdd /media/usb /media/mmc /media/cf; do
              if is_mount "${'$'}d" && $cert; then
                echo "${'$'}d/enigmacast-sync"
                exit 0
              fi
            done
            $fallback
        """.trimIndent()
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
            URL=${'$'}(cat /tmp/enigmacast-sync-release/url)
            if ! wget -O /tmp/enigmacast-sync.ipk "${'$'}URL"; then
              python3 -c 'import urllib.request; urllib.request.urlretrieve(open("/tmp/enigmacast-sync-release/url", encoding="utf-8").read().strip(), "/tmp/enigmacast-sync.ipk")'
            fi
            if ! python3 -c 'import sqlite3, ssl' >/dev/null 2>&1; then
              opkg update >/dev/null 2>&1 || true
              opkg install python3-sqlite3 >/dev/null 2>&1 || {
                echo "python3-sqlite3 ontbreekt en kon niet worden geïnstalleerd" >&2
                exit 1
              }
            fi
            opkg install --force-reinstall --force-overwrite /tmp/enigmacast-sync.ipk
            test -d /usr/lib/enigma2/python/Plugins/Extensions/EnigmaCastSync/enigmacast_sync/web
        """.trimIndent()
    }
}
