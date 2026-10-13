package com.enigmacast.domain.settingsimport

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferErrorsTest {
    private fun kind(e: Throwable, stage: TransferStage) = TransferErrors.map(e, stage).failure

    @Test fun networkErrors() {
        assertEquals(TransferFailure.CONNECTION_REFUSED, kind(ConnectException("Connection refused"), TransferStage.CONNECT))
        assertEquals(TransferFailure.TIMEOUT, kind(ConnectException("connect timed out"), TransferStage.CONNECT))
        assertEquals(TransferFailure.TIMEOUT, kind(SocketTimeoutException("Read timed out"), TransferStage.UPLOAD))
        assertEquals(TransferFailure.UNREACHABLE, kind(UnknownHostException("box"), TransferStage.CONNECT))
        assertEquals(TransferFailure.UNREACHABLE, kind(NoRouteToHostException("no route"), TransferStage.CONNECT))
        assertEquals(TransferFailure.CONNECTION_REFUSED, kind(IOException("wrapped", ConnectException("refused")), TransferStage.RELOAD))
    }

    @Test fun stageDecidesGenericErrors() {
        assertEquals(TransferFailure.WRITE_FAILED, kind(IOException("broken pipe"), TransferStage.UPLOAD))
        assertEquals(TransferFailure.BACKUP_FAILED, kind(IOException("x"), TransferStage.BACKUP))
        assertEquals(TransferFailure.RELOAD_FAILED, kind(IOException("x"), TransferStage.RELOAD))
        assertEquals(TransferFailure.LOGIN_FAILED, kind(IOException("x"), TransferStage.LOGIN))
        assertEquals(TransferFailure.DOWNLOAD_FAILED, kind(IOException("x"), TransferStage.DOWNLOAD))
    }

    @Test fun keepsExistingTransferException() {
        val e = TransferException(TransferFailure.LOGIN_FAILED, "530")
        assertEquals(e, TransferErrors.map(e, TransferStage.UPLOAD))
    }

    @Test fun ftpReplyCodes() {
        assertEquals(TransferFailure.LOGIN_FAILED, TransferErrors.fromFtpReply(530, "Login incorrect.", TransferStage.LOGIN).failure)
        assertEquals(TransferFailure.WRITE_FAILED, TransferErrors.fromFtpReply(553, "Could not create file.", TransferStage.UPLOAD).failure)
        assertEquals(TransferFailure.WRITE_FAILED, TransferErrors.fromFtpReply(452, "No space", TransferStage.UPLOAD).failure)
        assertEquals(TransferFailure.BACKUP_FAILED, TransferErrors.fromFtpReply(550, "Permission denied", TransferStage.BACKUP).failure)
        assertEquals(TransferFailure.CONNECTION_REFUSED, TransferErrors.fromFtpReply(421, "Too many users", TransferStage.CONNECT).failure)
        assertEquals("530 Login incorrect.", TransferErrors.fromFtpReply(530, "Login incorrect.\r\n", TransferStage.LOGIN).detail)
    }

    @Test fun reloadReplies() {
        assertTrue(TransferErrors.reloadSucceeded("<e2message><e2result>True</e2result><e2resulttext>reloaded lamedb and user bouquets</e2resulttext></e2message>"))
        assertFalse(TransferErrors.reloadSucceeded("<e2simplexmlresult><e2result>False</e2result></e2simplexmlresult>"))
        assertTrue(TransferErrors.reloadSucceeded("{\"result\": true, \"message\": \"reloaded lamedb and user bouquets\"}"))
        assertFalse(TransferErrors.reloadSucceeded("<html>404</html>"))
    }

    @Test fun messageNeverHoldsPasswords() {
        val e = TransferException(TransferFailure.LOGIN_FAILED, "530 Login incorrect.")
        assertFalse(e.message!!.contains("password", ignoreCase = true))
    }
}
