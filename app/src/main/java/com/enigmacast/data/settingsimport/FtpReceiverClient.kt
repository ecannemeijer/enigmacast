package com.enigmacast.data.settingsimport

import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.settingsimport.TransferErrors
import com.enigmacast.domain.settingsimport.TransferException
import com.enigmacast.domain.settingsimport.TransferFailure
import com.enigmacast.domain.settingsimport.TransferStage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.time.Duration
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply

/**
 * FTP session with an Enigma2 box (openATV, OpenPLi, Open Vision all run an FTP server on
 * port 21, user root). OpenWebif cannot upload into /etc/enigma2, so this is how lists get there.
 * Blocking: call from Dispatchers.IO.
 */
class FtpReceiverClient private constructor(private val ftp: FTPClient) : Closeable {

    fun list(dir: String): List<String> = try {
        val names = ftp.listNames(dir) ?: throw TransferErrors.fromFtpReply(ftp.replyCode, ftp.replyString.orEmpty(), TransferStage.BACKUP)
        names.map { it.substringAfterLast('/') }.filter { it.isNotBlank() && it != "." && it != ".." }
    } catch (e: TransferException) {
        throw e
    } catch (e: Exception) {
        throw TransferErrors.map(e, TransferStage.BACKUP)
    }

    /** The file, or null when it does not exist. */
    fun download(path: String): ByteArray? = try {
        val out = ByteArrayOutputStream()
        if (ftp.retrieveFile(path, out)) out.toByteArray() else if (ftp.replyCode == 550) null
        else throw TransferErrors.fromFtpReply(ftp.replyCode, ftp.replyString.orEmpty(), TransferStage.BACKUP)
    } catch (e: TransferException) {
        throw e
    } catch (e: Exception) {
        throw TransferErrors.map(e, TransferStage.BACKUP)
    }

    /** Writes next to the target and renames, so a broken connection never leaves half a lamedb. */
    fun upload(path: String, bytes: ByteArray) {
        try {
            val temp = "$path.enigmacast-tmp"
            if (ftp.storeFile(temp, ByteArrayInputStream(bytes))) {
                if (ftp.rename(temp, path)) return
                ftp.deleteFile(temp)
            }
            // Some servers refuse rename over an existing file: write it directly.
            if (!ftp.storeFile(path, ByteArrayInputStream(bytes))) {
                throw TransferErrors.fromFtpReply(ftp.replyCode, ftp.replyString.orEmpty(), TransferStage.UPLOAD)
            }
        } catch (e: TransferException) {
            throw TransferException(e.failure, "${path.substringAfterLast('/')}: ${e.detail}", e)
        } catch (e: Exception) {
            val mapped = TransferErrors.map(e, TransferStage.UPLOAD)
            throw TransferException(mapped.failure, "${path.substringAfterLast('/')}: ${mapped.detail}", e)
        }
    }

    fun delete(path: String): Boolean = runCatching { ftp.deleteFile(path) }.getOrDefault(false)

    override fun close() {
        runCatching { if (ftp.isConnected) ftp.logout() }
        runCatching { if (ftp.isConnected) ftp.disconnect() }
    }

    companion object {
        /** Connects to the receiver's home address and logs in; throws [TransferException]. */
        fun open(config: ReceiverConfig): FtpReceiverClient {
            val timeout = Duration.ofSeconds(config.timeoutSeconds.coerceIn(5, 30).toLong())
            val ftp = FTPClient().apply {
                setConnectTimeout(timeout.toMillis().toInt())
                setDefaultTimeout(timeout.toMillis().toInt())
                setDataTimeout(timeout.multipliedBy(2))
                controlEncoding = "UTF-8"
            }
            try {
                ftp.connect(config.host, config.ftpPort.takeIf { it in 1..65535 } ?: 21)
            } catch (e: Exception) {
                runCatching { ftp.disconnect() }
                throw TransferErrors.map(e, TransferStage.CONNECT)
            }
            try {
                if (!FTPReply.isPositiveCompletion(ftp.replyCode)) {
                    throw TransferErrors.fromFtpReply(ftp.replyCode, ftp.replyString.orEmpty(), TransferStage.CONNECT).let {
                        if (it.failure == TransferFailure.LOGIN_FAILED) TransferException(TransferFailure.CONNECTION_REFUSED, it.detail) else it
                    }
                }
                ftp.soTimeout = timeout.toMillis().toInt()
                val user = config.ftpUsername.ifBlank { "root" }
                // The root password is often the one OpenWebif uses too: try that when the FTP one is refused.
                val fallback = config.password.takeIf { it.isNotEmpty() && it != config.ftpPassword }
                if (!ftp.login(user, config.ftpPassword) && (fallback == null || !ftp.login(user, fallback))) {
                    throw TransferException(TransferFailure.LOGIN_FAILED, "${ftp.replyCode} ${ftp.replyString.orEmpty().trim()}".take(160))
                }
                ftp.enterLocalPassiveMode()
                ftp.setFileType(FTP.BINARY_FILE_TYPE)
                return FtpReceiverClient(ftp)
            } catch (e: Exception) {
                runCatching { ftp.disconnect() }
                throw TransferErrors.map(e, TransferStage.LOGIN)
            }
        }
    }
}
