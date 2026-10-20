package com.enigmacast.data.profile

import com.enigmacast.domain.profile.photo.UploadToken
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhotoUploadServerTest {
    private val token = UploadToken.create(System.currentTimeMillis())
    private val received = mutableListOf<Triple<Int, Double?, Double?>>()
    private val done = CountDownLatch(1)
    private var reject: String? = null
    private val server = PhotoUploadServer(
        InetAddress.getLoopbackAddress(), token, "Anna",
        onPhoto = { bytes, x, _, side -> reject?.let { throw AvatarPhotos.Rejected(it) }; received += Triple(bytes.size, x, side) },
        onSuccess = { done.countDown() },
    ).also { it.start() }

    @After fun tearDown() = server.stop()

    private fun call(request: String, body: ByteArray = ByteArray(0)): String = Socket(InetAddress.getLoopbackAddress(), server.port).use { s ->
        s.soTimeout = 5_000
        s.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1) + body)
        s.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    private fun post(t: String, body: ByteArray) =
        call("POST /upload?token=$t&x=0.1&y=0.2&s=0.5 HTTP/1.1\r\nContent-Type: image/jpeg\r\nContent-Length: ${body.size}\r\n\r\n", body)

    @Test fun servesThePageOnlyWithTheToken() {
        assertTrue(server.port in 8765..8775 || server.port > 0)
        val ok = call("GET /upload?token=${token.value} HTTP/1.1\r\nAccept-Language: de-DE\r\n\r\n")
        assertTrue(ok.startsWith("HTTP/1.1 200"))
        assertTrue(ok.contains("Profilfoto festlegen"))
        assertTrue(call("GET /upload?token=wrong HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"))
        assertTrue(call("GET /upload HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"))
        assertTrue(call("GET /other?token=${token.value} HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 404"))
    }

    @Test fun wrongTokenUploadIsRefusedWithoutReadingIt() {
        assertTrue(post("nope", ByteArray(100)).startsWith("HTTP/1.1 403"))
        assertEquals(0, received.size)
    }

    @Test fun oneUploadSucceedsThenTheServerStops() {
        val response = post(token.value, ByteArray(1234) { 7 })
        assertTrue(response, response.startsWith("HTTP/1.1 200"))
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(Triple(1234, 0.1, 0.5)), received)
        assertTrue(token.consumed)
        Thread.sleep(100)
        val again = runCatching { post(token.value, ByteArray(10)) }.getOrDefault("refused")
        assertTrue(again, again == "refused" || again.isEmpty() || again.startsWith("HTTP/1.1 403"))
    }

    @Test fun rejectedImagesKeepTheTokenUsable() {
        reject = "image"
        val bad = post(token.value, "not an image".toByteArray())
        assertTrue(bad.startsWith("HTTP/1.1 415"))
        assertTrue(bad.contains("\"error\":\"image\""))
        reject = null
        assertTrue(post(token.value, ByteArray(10)).startsWith("HTTP/1.1 200"))
    }

    @Test fun tooLargeIsRefused() {
        val r = call("POST /upload?token=${token.value} HTTP/1.1\r\nContent-Length: 20000000\r\n\r\n")
        assertTrue(r, r.startsWith("HTTP/1.1 413"))
        assertEquals(0, received.size)
    }
}
