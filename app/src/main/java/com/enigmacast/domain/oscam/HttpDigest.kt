package com.enigmacast.domain.oscam

import java.security.MessageDigest
import java.util.Locale

/** HTTP Digest (RFC 2617) for Oscam's web interface. Oscam asks for Digest, sometimes Basic. */
object HttpDigest {
    data class Challenge(
        val realm: String,
        val nonce: String,
        val qop: String?,
        val algorithm: String,
        val opaque: String?,
    )

    /** The `Digest …` challenge, or null when the header is missing or not Digest. */
    fun parse(header: String): Challenge? {
        val trimmed = header.trim()
        if (!trimmed.startsWith("Digest", ignoreCase = true)) return null
        val params = parameters(trimmed.substring("Digest".length))
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val qop = params["qop"]
            ?.split(',')
            ?.map { it.trim() }
            ?.firstOrNull { it.equals("auth", ignoreCase = true) }
        return Challenge(
            realm = realm,
            nonce = nonce,
            qop = qop,
            algorithm = params["algorithm"] ?: "MD5",
            opaque = params["opaque"],
        )
    }

    fun authorization(
        username: String,
        password: String,
        method: String,
        uri: String,
        challenge: Challenge,
        nc: Int,
        cnonce: String,
    ): String {
        val ha1 = ha1(username, password, challenge, cnonce)
        val ha2 = md5("$method:$uri")
        val ncHex = "%08x".format(Locale.US, nc)
        val response = if (challenge.qop.isNullOrEmpty()) {
            md5("$ha1:${challenge.nonce}:$ha2")
        } else {
            md5("$ha1:${challenge.nonce}:$ncHex:$cnonce:${challenge.qop}:$ha2")
        }
        val parts = mutableListOf(
            "username=${quote(username)}",
            "realm=${quote(challenge.realm)}",
            "nonce=${quote(challenge.nonce)}",
            "uri=${quote(uri)}",
            "algorithm=${challenge.algorithm}",
            "response=${quote(response)}",
        )
        if (!challenge.qop.isNullOrEmpty()) {
            parts += "qop=${challenge.qop}"
            parts += "nc=$ncHex"
            parts += "cnonce=${quote(cnonce)}"
        }
        challenge.opaque?.let { parts += "opaque=${quote(it)}" }
        return "Digest " + parts.joinToString(", ")
    }

    private fun ha1(username: String, password: String, challenge: Challenge, cnonce: String): String {
        val base = md5("$username:${challenge.realm}:$password")
        return if (challenge.algorithm.equals("MD5-sess", ignoreCase = true)) {
            md5("$base:${challenge.nonce}:$cnonce")
        } else {
            base
        }
    }

    private fun parameters(text: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        val pattern = Regex("""([A-Za-z][A-Za-z0-9_-]*)\s*=\s*(?:"([^"\\]*(?:\\.[^"\\]*)*)"|([^,\s]+))""")
        for (match in pattern.findAll(text)) {
            val raw = match.groupValues[2].ifEmpty { match.groupValues[3] }
            values[match.groupValues[1].lowercase(Locale.US)] = raw.replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return values
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun md5(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.ISO_8859_1))
        return digest.joinToString("") { "%02x".format(Locale.US, it) }
    }
}
