package dev.castbridge.core.casting

import java.io.IOException
import java.net.InetAddress
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RokuEcpControllerTest {
    private val server = MockWebServer()

    private val port: Int get() = server.port

    @Before
    fun setUp() {
        server.start(InetAddress.getLoopbackAddress(), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `launches the developer channel with a deep link`() {
        server.enqueue(MockResponse().setResponseCode(200))

        RokuEcpController().launchChannel(
            host = "127.0.0.1",
            port = port,
            url = "http://192.168.0.23:40413/s/0123456789abcdef0123456789abcdef/index.m3u8?pairing=123456",
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/launch/dev", request.requestUrl?.encodedPath)
        assertEquals(
            "http://192.168.0.23:40413/s/0123456789abcdef0123456789abcdef/index.m3u8?pairing=123456",
            request.requestUrl?.queryParameter("contentId"),
        )
        assertEquals("movie", request.requestUrl?.queryParameter("mediaType"))
    }

    @Test
    fun `reports a missing channel with an actionable message`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Application Not Found"))

        val error = runCatching {
            RokuEcpController().launchChannel("127.0.0.1", port, "castbridge-stop")
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(error?.message?.contains("not installed") == true)
    }

    @Test
    fun `explains a refused launch`() {
        server.enqueue(MockResponse().setResponseCode(403))

        val error = runCatching {
            RokuEcpController().launchChannel("127.0.0.1", port, "castbridge-stop")
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("mobile app control") == true)
    }

    @Test
    fun `reads the foreground app id`() {
        server.enqueue(
            MockResponse().setBody(
                "<active-app><app>CastBridge</app><version>1.0.0</version><id>dev</id></active-app>",
            ),
        )

        val active = RokuEcpController().activeAppId("127.0.0.1", port)

        assertEquals("dev", active)
        assertEquals("/query/active-app", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun `returns null when the app id cannot be read`() {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(null, RokuEcpController().activeAppId("127.0.0.1", port))
    }
}
