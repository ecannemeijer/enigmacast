package com.enigmacast.playback

import androidx.media3.common.ParserException
import com.enigmacast.domain.tuner.StreamFailure
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

class StreamFailuresTest {
    @Test
    fun emptyStreamFromTheReceiverIsAParseFailure() {
        // What Media3 reports when Enigma2 answers "200 OK" and closes without TS data.
        val error = ParserException.createForMalformedContainer("Loading finished before preparation is complete.", null)
        assertEquals(StreamFailure.PARSE, StreamFailures.classify(error))
    }

    @Test
    fun connectionProblemsAreFoundInTheCauseChain() {
        assertEquals(StreamFailure.CONNECT, StreamFailures.classify(IOException(ConnectException("refused"))))
        assertEquals(StreamFailure.CONNECT, StreamFailures.classify(SocketTimeoutException()))
    }

    @Test
    fun endOfStreamAndUnknownErrors() {
        assertEquals(StreamFailure.ENDED, StreamFailures.classify(EOFException()))
        assertEquals(StreamFailure.OTHER, StreamFailures.classify(IOException("other")))
    }
}
