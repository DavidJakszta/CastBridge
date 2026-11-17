package dev.castbridge.core.streaming

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HlsHttpServerTest {
    private lateinit var store: HlsMediaStore
    private lateinit var server: HlsHttpServer
    private var port = 0

    private val token = "test-token"
    private val segmentBytes = ByteArray(500) { index -> index.toByte() }

    @Before
    fun setUp() {
        store = HlsMediaStore()
        store.add(HlsSegment(0, "segment0.ts", 2_000_000, segmentBytes))
        server = HlsHttpServer(store = store, token = token)
        port = server.start(InetAddress.getByName("127.0.0.1"), port = 0)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `playlist requires the session token`() {
        val denied = request(url("wrong-token", HlsMediaStore.PLAYLIST_NAME))
        assertEquals(404, denied.status)

        val allowed = request(url(token, HlsMediaStore.PLAYLIST_NAME))
        assertEquals(200, allowed.status)
        assertEquals("application/vnd.apple.mpegurl", allowed.headers["content-type"])
        assertTrue(allowed.body.toString(Charsets.UTF_8).contains("segment0.ts"))
        assertEquals("no-cache, no-store, must-revalidate", allowed.headers["cache-control"])
    }

    @Test
    fun `serves the complete segment`() {
        val response = request(url(token, "segment0.ts"))

        assertEquals(200, response.status)
        assertEquals("video/mp2t", response.headers["content-type"])
        assertArrayEquals(segmentBytes, response.body)
    }

    @Test
    fun `supports byte ranges`() {
        val response = request(url(token, "segment0.ts"), range = "bytes=10-19")

        assertEquals(206, response.status)
        assertEquals("bytes 10-19/500", response.headers["content-range"])
        assertArrayEquals(segmentBytes.copyOfRange(10, 20), response.body)
    }

    @Test
    fun `supports suffix ranges`() {
        val response = request(url(token, "segment0.ts"), range = "bytes=-5")

        assertEquals(206, response.status)
        assertEquals("bytes 495-499/500", response.headers["content-range"])
        assertArrayEquals(segmentBytes.copyOfRange(495, 500), response.body)
    }

    @Test
    fun `head returns headers without a body`() {
        val response = request(url(token, "segment0.ts"), method = "HEAD")

        assertEquals(200, response.status)
        assertEquals("500", response.headers["content-length"])
        assertEquals(0, response.body.size)
    }

    @Test
    fun `unknown resources and methods are rejected`() {
        assertEquals(404, request(url(token, "missing.ts")).status)
        assertEquals(405, request(url(token, HlsMediaStore.PLAYLIST_NAME), method = "POST").status)
    }

    private fun url(token: String, resource: String): String =
        "http://127.0.0.1:$port/s/$token/$resource"

    private fun request(
        url: String,
        method: String = "GET",
        range: String? = null,
    ): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        range?.let { connection.setRequestProperty("Range", it) }

        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.use { it.readBytes() } ?: ByteArray(0)
        val headers = connection.headerFields
            .filterKeys { it != null }
            .mapKeys { it.key.lowercase() }
            .mapValues { it.value.firstOrNull().orEmpty() }
        connection.disconnect()

        return Response(status, body, headers)
    }

    private data class Response(
        val status: Int,
        val body: ByteArray,
        val headers: Map<String, String>,
    )
}
