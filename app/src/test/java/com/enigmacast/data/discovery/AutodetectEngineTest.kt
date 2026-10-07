package com.enigmacast.data.discovery

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * End to end over real sockets: a simulated /24 on loopback (127.0.0.0/24; Linux routes all of
 * 127/8 to `lo`) with an SF8008 at .209, an unrelated web server at .50 and a receiver that
 * wants a login at .77. The real scanner, HTTP probe and classifier must list exactly the two
 * receivers.
 */
class AutodetectEngineTest {
    private val servers = mutableListOf<HttpServer>()
    private var port = 0
    private val log = mutableListOf<String>()

    @Before
    fun startFakeLan() {
        port = ServerSocket(0).use { it.localPort }
        assumeTrue("127.0.0.x aliases are not available on this OS", canBind("127.0.0.209"))
        serve("127.0.0.209") { exchange ->
            val body = if (exchange.requestURI.path == "/api/about") SF8008_ABOUT else "<html>OpenWebif</html>"
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        serve("127.0.0.50") { exchange ->
            val body = "<html><body>Printer</body></html>"
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        serve("127.0.0.77") { exchange ->
            exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"OpenWebif\"")
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
    }

    @After
    fun stop() {
        servers.forEach { it.stop(0) }
    }

    @Test
    fun theSf8008AndTheLoginReceiverAreFoundAndThePrinterIsNot() = runBlocking {
        val engine = AutodetectEngine(
            scanner = TcpPortScanner(parallel = 40, connectTimeoutMs = 400),
            probe = AboutProbe(log = { synchronized(log) { log += it } }),
            ports = listOf(port),
            log = { synchronized(log) { log += it } },
        )
        val result = engine.run(listOf(LocalNetwork("127.0.0.10", 24, "lo", "test")))
        assertEquals(listOf("127.0.0.77", "127.0.0.209"), result.receivers.map { it.host })
        val sf8008 = result.receivers.last()
        assertEquals("Octagon SF8008 4K (127.0.0.209:$port)", sf8008.label)
        assertEquals("openATV 7.4", sf8008.image)
        assertTrue(result.receivers.first().loginRequired)
        assertEquals(listOf("127.0.0.0/24"), result.subnets)
        assertEquals(253, result.scannedAddresses)
        assertTrue("took ${result.elapsedMs} ms", result.elapsedMs < 8_000)
        assertTrue(log.joinToString("\n"), log.any { it.startsWith("open 127.0.0.209:$port") })
        assertTrue(log.joinToString("\n"), log.any { it.contains("127.0.0.50:$port -> HTTP 200, not Enigma2") })
    }

    @Test
    fun aHostOnlyKnownFromTheFormIsCheckedToo() = runBlocking {
        val engine = AutodetectEngine(ports = listOf(port), probe = AboutProbe())
        val result = engine.run(emptyList(), extraHosts = listOf(DiscoveredHost("127.0.0.209", port)))
        assertEquals(listOf("127.0.0.209"), result.receivers.map { it.host })
        assertEquals(0, result.scannedAddresses)
    }

    private fun serve(address: String, handler: (com.sun.net.httpserver.HttpExchange) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(address, port), 16)
        server.createContext("/") { exchange -> handler(exchange) }
        server.start()
        servers += server
    }

    private fun canBind(address: String): Boolean = runCatching {
        ServerSocket().use { it.bind(InetSocketAddress(address, 0)) }
        true
    }.getOrDefault(false)
}
