package com.enigmacast.domain.sync

import java.io.EOFException
import java.io.Reader

/** What a pull page said besides its changes. Null fields were missing from the response. */
data class SyncPullPage(
    val cursor: Int?,
    val limit: Int?,
    val more: Boolean?,
    val received: Int,
    /** `rev` of the last change handed to the caller; the cursor to save when the stream breaks. */
    val lastRev: Int?,
) {
    /** Servers before 0.2.6 ignore `limit` and do not echo it: every page is the whole history. */
    val serverPages: Boolean get() = limit != null
}

/**
 * Reads `{"cursor":…,"changes":[{…},…],"limit":…,"more":…}` from a stream and hands every complete
 * change to [onChange] as soon as its closing brace arrives. When the connection drops halfway, the
 * changes already handed over stay applied and [TruncatedPull.lastRev] says where to resume, instead
 * of throwing the whole page away (a 0.2.4 receiver sends megabytes per page and often breaks off).
 * Pure Kotlin so it runs in JVM unit tests.
 */
object SyncPullStream {
    class TruncatedPull(val lastRev: Int?, val received: Int, cause: Throwable) :
        java.io.IOException("pull response ended after $received changes", cause)

    suspend fun read(input: Reader, onChange: suspend (String) -> Unit): SyncPullPage {
        val scanner = Scanner(input)
        var cursor: Int? = null
        var limit: Int? = null
        var more: Boolean? = null
        var received = 0
        var lastRev: Int? = null
        try {
            scanner.expect('{')
            if (scanner.peekSkipping() == '}') {
                scanner.next()
            } else {
                while (true) {
                    val key = scanner.string()
                    scanner.expect(':')
                    if (key == "changes" && scanner.peekSkipping() == '[') {
                        scanner.next()
                        if (scanner.peekSkipping() == ']') {
                            scanner.next()
                        } else {
                            while (true) {
                                val raw = scanner.rawValue()
                                onChange(raw)
                                received++
                                revOf(raw)?.let { lastRev = it }
                                when (scanner.nextSkipping()) {
                                    ',' -> continue
                                    ']' -> break
                                    else -> throw java.io.IOException("bad changes array")
                                }
                            }
                        }
                    } else {
                        val raw = scanner.rawValue()
                        when (key) {
                            "cursor" -> cursor = raw.toIntOrNull()
                            "limit" -> limit = raw.toIntOrNull()
                            "more" -> more = raw.toBooleanStrictOrNull()
                        }
                    }
                    when (scanner.nextSkipping()) {
                        ',' -> continue
                        '}' -> break
                        else -> throw java.io.IOException("bad pull response")
                    }
                }
            }
        } catch (error: java.io.IOException) {
            throw TruncatedPull(lastRev, received, error)
        }
        return SyncPullPage(cursor, limit, more, received, lastRev)
    }

    private val REV = Regex(""""rev"\s*:\s*(-?\d+)""")

    /** `rev` is a top-level number in every change the server sends (all versions). */
    internal fun revOf(raw: String): Int? = REV.find(raw)?.groupValues?.get(1)?.toIntOrNull()

    private class Scanner(private val input: Reader) {
        private var peeked = -2

        private fun read(): Int {
            if (peeked != -2) {
                val c = peeked
                peeked = -2
                return c
            }
            return input.read()
        }

        fun next(): Char {
            val c = read()
            if (c < 0) throw EOFException("truncated")
            return c.toChar()
        }

        fun peekSkipping(): Char {
            while (true) {
                val c = read()
                if (c < 0) throw EOFException("truncated")
                if (!c.toChar().isWhitespace()) {
                    peeked = c
                    return c.toChar()
                }
            }
        }

        fun nextSkipping(): Char {
            peekSkipping()
            return next()
        }

        fun expect(ch: Char) {
            val c = nextSkipping()
            if (c != ch) throw java.io.IOException("expected $ch")
        }

        fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                when (val c = next()) {
                    '"' -> return out.toString()
                    '\\' -> {
                        val e = next()
                        when (e) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'u' -> out.append(String(CharArray(4) { next() }).toInt(16).toChar())
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        /** The exact text of the next JSON value (object, array, string or literal). */
        fun rawValue(): String {
            val out = StringBuilder()
            val first = peekSkipping()
            if (first == '{' || first == '[') {
                var depth = 0
                var inString = false
                while (true) {
                    val c = next()
                    out.append(c)
                    if (inString) {
                        if (c == '\\') out.append(next()) else if (c == '"') inString = false
                        continue
                    }
                    when (c) {
                        '"' -> inString = true
                        '{', '[' -> depth++
                        '}', ']' -> {
                            depth--
                            if (depth == 0) return out.toString()
                        }
                    }
                }
            }
            if (first == '"') {
                out.append(next())
                while (true) {
                    val c = next()
                    out.append(c)
                    if (c == '\\') out.append(next()) else if (c == '"') return out.toString()
                }
            }
            while (true) {
                val c = read()
                if (c < 0) throw EOFException("truncated")
                val ch = c.toChar()
                if (ch == ',' || ch == '}' || ch == ']' || ch.isWhitespace()) {
                    peeked = c
                    return out.toString()
                }
                out.append(ch)
            }
        }
    }
}
