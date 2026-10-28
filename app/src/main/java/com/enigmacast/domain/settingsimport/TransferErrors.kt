package com.enigmacast.domain.settingsimport

import java.io.FileNotFoundException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Why loading a list on one receiver failed; each has its own message for the user. */
enum class TransferFailure {
    CONNECTION_REFUSED,
    UNREACHABLE,
    TIMEOUT,
    LOGIN_FAILED,
    READ_FAILED,
    BACKUP_FAILED,
    WRITE_FAILED,
    RELOAD_FAILED,
    DOWNLOAD_FAILED,
    NO_FILES,
    /** The list's repository, branch or file is gone on GitHub (removed or renamed). */
    NOT_FOUND,
    /** GitHub's hourly limit for requests without login is used up. */
    RATE_LIMITED,
    /** No internet: GitHub (or the playlist host) cannot be reached at all. */
    OFFLINE,
    UNKNOWN,
}

/** A failure with its kind and a short technical detail (server reply, file name). Never carries a password. */
class TransferException(val failure: TransferFailure, val detail: String = "", cause: Throwable? = null) :
    Exception("${failure.name}${if (detail.isBlank()) "" else ": $detail"}", cause)

/** At which step something went wrong; decides what a generic I/O error means. */
enum class TransferStage { CONNECT, LOGIN, BACKUP, UPLOAD, RELOAD, DOWNLOAD }

object TransferErrors {
    /**
     * An unsuccessful GitHub reply. 403/429 with `x-ratelimit-remaining: 0` (or a
     * `retry-after`) is the limit for requests without login, 404 a repository, branch or
     * file that was removed or renamed; anything else is a plain download failure.
     */
    fun fromHttp(code: Int, rateRemaining: String?, retryAfter: String?, what: String): TransferException = when {
        (code == 403 || code == 429) && (rateRemaining?.trim() == "0" || retryAfter != null || code == 429) ->
            TransferException(TransferFailure.RATE_LIMITED, "")
        code == 404 || code == 410 -> TransferException(TransferFailure.NOT_FOUND, what)
        else -> TransferException(TransferFailure.DOWNLOAD_FAILED, "HTTP $code $what".trim())
    }

    /** No route to the internet at all, as opposed to a server that answers badly. */
    fun isOffline(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
        it is UnknownHostException || it is NoRouteToHostException || it is ConnectException
    }

    fun map(error: Throwable, stage: TransferStage): TransferException {
        if (error is TransferException) return error
        val detail = error.message.orEmpty().take(160)
        val root = generateSequence(error) { it.cause }.toList()
        fun has(type: Class<out Throwable>) = root.any { type.isInstance(it) }
        val failure = when {
            has(ConnectException::class.java) && !detail.contains("timed out", true) -> TransferFailure.CONNECTION_REFUSED
            has(SocketTimeoutException::class.java) || detail.contains("timed out", true) -> TransferFailure.TIMEOUT
            has(UnknownHostException::class.java) || has(NoRouteToHostException::class.java) -> TransferFailure.UNREACHABLE
            stage == TransferStage.DOWNLOAD -> TransferFailure.DOWNLOAD_FAILED
            has(FileNotFoundException::class.java) && stage == TransferStage.RELOAD -> TransferFailure.RELOAD_FAILED
            has(InterruptedIOException::class.java) -> TransferFailure.TIMEOUT
            stage == TransferStage.LOGIN -> TransferFailure.LOGIN_FAILED
            stage == TransferStage.BACKUP -> TransferFailure.BACKUP_FAILED
            stage == TransferStage.UPLOAD -> TransferFailure.WRITE_FAILED
            stage == TransferStage.RELOAD -> TransferFailure.RELOAD_FAILED
            stage == TransferStage.CONNECT -> TransferFailure.UNREACHABLE
            else -> TransferFailure.UNKNOWN
        }
        return TransferException(failure, detail, error)
    }

    /** FTP reply codes: 530/331/332 login, 550/553/552/452 cannot write, 421 service closed. */
    fun fromFtpReply(code: Int, reply: String, stage: TransferStage): TransferException {
        val failure = when (code) {
            530, 331, 332 -> TransferFailure.LOGIN_FAILED
            421 -> TransferFailure.CONNECTION_REFUSED
            450, 451, 452, 550, 551, 552, 553 -> if (stage == TransferStage.BACKUP) TransferFailure.BACKUP_FAILED else TransferFailure.WRITE_FAILED
            else -> when (stage) {
                TransferStage.LOGIN, TransferStage.CONNECT -> TransferFailure.LOGIN_FAILED
                TransferStage.BACKUP -> TransferFailure.BACKUP_FAILED
                TransferStage.UPLOAD -> TransferFailure.WRITE_FAILED
                else -> TransferFailure.UNKNOWN
            }
        }
        return TransferException(failure, "$code ${reply.trim()}".trim().take(160))
    }

    /** OpenWebif answers `<e2result>True</e2result>` (XML) or `"result": true` (JSON). */
    fun reloadSucceeded(body: String): Boolean {
        val xml = Regex("<e2result>\\s*(\\w+)\\s*</e2result>", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
        if (xml != null) return xml.equals("true", true)
        val json = Regex("\"result\"\\s*:\\s*(\\w+)", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
        if (json != null) return json.equals("true", true)
        return false
    }
}
