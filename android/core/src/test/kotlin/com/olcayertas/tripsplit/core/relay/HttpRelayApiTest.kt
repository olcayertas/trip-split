package com.olcayertas.tripsplit.core.relay

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Timeout(60)
class HttpRelayApiTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var status = 200
    private var answer = ""
    private var lastAuth: String? = null
    private var lastRequest = ""
    private var lastBody = ByteArray(0)
    private var lastType: String? = null

    init {
        server.createContext("/") { ex ->
            lastAuth = ex.requestHeaders.getFirst("Authorization")
            lastType = ex.requestHeaders.getFirst("Content-Type")
            lastRequest = ex.requestMethod + " " + ex.requestURI
            lastBody = ex.requestBody.readBytes()
            val bytes = answer.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        server.start()
    }

    private val api get() = HttpRelayApi("http://127.0.0.1:${server.address.port}/", timeoutMs = 3_000)

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun pushSendsTheBlobWithTheBearerTokenAndReadsTheCursor() = runBlocking {
        status = 201; answer = """{"cursor": 7}"""
        assertEquals(7L, api.push("m".repeat(32), "tok", byteArrayOf(1, 2, 3)))
        assertEquals("POST /v1/m/${"m".repeat(32)}/batches", lastRequest)
        assertEquals("Bearer tok", lastAuth)
        assertEquals("application/octet-stream", lastType)
        assertContentEquals(byteArrayOf(1, 2, 3), lastBody)
    }

    @Test
    fun pullReadsBatchesAndMore() = runBlocking {
        val blob = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(9, 8, 7))
        status = 200; answer = """{"batches":[{"cursor":4,"blob":"$blob"}],"more":true}"""
        val page = api.pull("m".repeat(32), "tok", 3, 50)
        assertEquals("GET /v1/m/${"m".repeat(32)}/batches?after=3&limit=50", lastRequest)
        assertEquals(4L, page.batches.single().cursor)
        assertContentEquals(byteArrayOf(9, 8, 7), page.batches.single().blob)
        assertTrue(page.more)
    }

    private fun failureFor(code: Int): RelayFailure = runBlocking {
        status = code; answer = ""
        assertFailsWith<RelayException> { api.pull("m".repeat(32), "t", 0, 50) }.failure
    }

    @Test
    fun statusCodesMapToFailures() {
        assertEquals(RelayFailure.FORBIDDEN, failureFor(403))
        assertEquals(RelayFailure.TOO_LARGE, failureFor(413))
        assertEquals(RelayFailure.FULL, failureFor(507))
        assertEquals(RelayFailure.RETRY, failureFor(429))
        assertEquals(RelayFailure.RETRY, failureFor(503))
        assertEquals(RelayFailure.RETRY, failureFor(500))
        assertEquals(RelayFailure.BAD, failureFor(400))
    }

    @Test
    fun aBodyThatBreaksTheSpecIsARetryFailure() = runBlocking {
        status = 200; answer = "not json"
        assertEquals(RelayFailure.RETRY, assertFailsWith<RelayException> { api.pull("m".repeat(32), "t", 0, 50) }.failure)
    }

    @Test
    fun aClosedPortIsARetryFailure() = runBlocking {
        val port = server.address.port
        server.stop(0)
        val e = assertFailsWith<RelayException> { HttpRelayApi("http://127.0.0.1:$port", 1_000).pull("m".repeat(32), "t", 0, 50) }
        assertEquals(RelayFailure.RETRY, e.failure)
    }
}
