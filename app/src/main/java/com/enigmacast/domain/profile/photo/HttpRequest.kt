package com.enigmacast.domain.profile.photo

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/** One HTTP/1.x request as read by the photo upload server. Header names are lower case. */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

class HttpError(val status: Int, message: String) : Exception(message)

/** A small, strict HTTP/1.1 request reader: bounded header block, Content-Length bodies only. */
object HttpRequestParser {
    const val MAX_HEADER_BYTES = 16 * 1024

    /**
     * Reads one request. The body is only read for [readBody] requests (after the token was
     * checked) and never beyond [maxBody]; a larger Content-Length is refused with 413.
     */
    fun parseHead(input: InputStream): HttpRequest {
        val head = readHead(input)
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpError(400, "bad request line")
        val method = parts[0].uppercase()
        if (method !in setOf("GET", "POST", "HEAD")) throw HttpError(405, "method")
        val target = parts[1]
        if (!target.startsWith("/")) throw HttpError(400, "bad target")
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpError(400, "bad header")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        if (headers["transfer-encoding"] != null) throw HttpError(411, "chunked bodies are not accepted")
        return HttpRequest(method, target.substringBefore('?'), parseQuery(target.substringAfter('?', "")), headers, ByteArray(0))
    }

    /** The body announced by Content-Length, at most [maxBody] bytes. */
    fun readBody(request: HttpRequest, input: InputStream, maxBody: Int): HttpRequest {
        val length = request.header("content-length")?.toLongOrNull() ?: throw HttpError(411, "length required")
        if (length < 0) throw HttpError(400, "bad length")
        if (length > maxBody) throw HttpError(413, "too large")
        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) throw HttpError(400, "body ended early")
            read += n
        }
        return HttpRequest(request.method, request.path, request.query, request.headers, body)
    }

    fun parseQuery(query: String): Map<String, String> = query.split('&').filter { it.isNotEmpty() }.associate { pair ->
        val key = decode(pair.substringBefore('='))
        key to decode(pair.substringAfter('=', ""))
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrElse { throw HttpError(400, "bad escape") }

    private fun readHead(input: InputStream): String {
        val out = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf(13, 10, 13, 10)
        while (matched < 4) {
            val b = input.read()
            if (b < 0) throw HttpError(400, "connection closed")
            out.write(b)
            if (out.size() > MAX_HEADER_BYTES) throw HttpError(431, "headers too large")
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b == 13) 1 else 0
        }
        return out.toString("ISO-8859-1").removeSuffix("\r\n\r\n")
    }
}

/** Writes a complete response and closes the exchange ("Connection: close"). */
object HttpResponse {
    private val reasons = mapOf(
        200 to "OK", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed",
        410 to "Gone", 411 to "Length Required", 413 to "Payload Too Large", 415 to "Unsupported Media Type",
        422 to "Unprocessable Entity", 431 to "Request Header Fields Too Large", 500 to "Internal Server Error",
    )

    fun write(out: OutputStream, status: Int, contentType: String, body: ByteArray, headOnly: Boolean = false) {
        val head = buildString {
            append("HTTP/1.1 $status ${reasons[status] ?: "Error"}\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Content-Security-Policy: default-src 'none'; img-src blob: data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) out.write(body)
        out.flush()
    }
}
