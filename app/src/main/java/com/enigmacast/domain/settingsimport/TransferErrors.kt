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
    UNKNOWN,
}

/** A failure with its kind and a short technical detail (server reply, file name). Never carries a password. */
class TransferException(val failure: TransferFailure, val detail: String = "", cause: Throwable? = null) :
    Exception("${failure.name}${if (detail.isBlank()) "" else ": $detail"}", cause)

/** At which step something went wrong; decides what a generic I/O error means. */
enum class TransferStage { CONNECT, LOGIN, BACKUP, UPLOAD, RELOAD, DOWNLOAD }

object TransferErrors {
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
