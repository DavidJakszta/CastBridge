package dev.castbridge.core.casting

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val STREAM_URL =
    "http://192.168.0.23:40413/s/0123456789abcdef0123456789abcdef/index.m3u8?pairing=123456"

class CastV2ClientTest {
    private val plainSocketFactory = CastSocketFactory { host, port, timeout ->
        Socket().apply { connect(InetSocketAddress(host, port), timeout) }
    }

    private var server: FakeCastDevice? = null
    private var client: CastV2Client? = null

    @After
    fun tearDown() {
        client?.close()
        server?.close()
    }

    @Test
    fun `connects, launches the default receiver, and loads a live HLS stream`() {
        val device = FakeCastDevice().also { server = it }
        val active = CastV2Client("127.0.0.1", device.port, plainSocketFactory).also { client = it }

        active.connect()
        val app = active.launchReceiver()

        assertEquals("CC1AD845", app.appId)
        assertEquals("transport-1", app.transportId)
        assertEquals("session-1", app.sessionId)

        val media = active.load(app.transportId, STREAM_URL)

        assertEquals("PLAYING", media.playerState)
        assertEquals(7L, media.mediaSessionId)

        val connect = device.messages.firstOrNull {
            it.namespace == CastMessage.NS_CONNECTION && it.destinationId == "receiver-0"
        }
        assertEquals("""{"type":"CONNECT","origin":{}}""", connect?.payloadUtf8)

        val launch = device.messages.firstOrNull {
            it.namespace == CastMessage.NS_RECEIVER && it.payloadUtf8?.contains("\"LAUNCH\"") == true
        }
        assertTrue(launch?.payloadUtf8?.contains("\"appId\":\"CC1AD845\"") == true)

        val mediaConnect = device.messages.firstOrNull {
            it.namespace == CastMessage.NS_CONNECTION && it.destinationId == "transport-1"
        }
        assertTrue(mediaConnect != null)

        val load = device.messages.firstOrNull {
            it.namespace == CastMessage.NS_MEDIA && it.destinationId == "transport-1"
        }
        val payload = load?.payloadUtf8.orEmpty()
        assertTrue(payload.contains("\"type\":\"LOAD\""))
        assertTrue(payload.contains("\"streamType\":\"LIVE\""))
        assertTrue(payload.contains("\"contentType\":\"application/x-mpegURL\""))
        assertTrue(payload.contains(STREAM_URL))
    }

    @Test
    fun `reports a takeover by another sender`() {
        val device = FakeCastDevice().also { server = it }
        val active = CastV2Client("127.0.0.1", device.port, plainSocketFactory).also { client = it }

        active.connect()
        val app = active.launchReceiver()
        active.load(app.transportId, STREAM_URL)

        device.push(
            CastMessage.NS_RECEIVER,
            receiverStatus(sessionId = "other-session", transportId = "transport-2"),
        )

        awaitTrue("receiver status to change") {
            active.receiverStatus()?.applications?.firstOrNull()?.sessionId == "other-session"
        }
    }

    @Test
    fun `detects a connection closed by the device`() {
        val device = FakeCastDevice().also { server = it }
        val active = CastV2Client("127.0.0.1", device.port, plainSocketFactory).also { client = it }

        active.connect()
        active.launchReceiver()

        device.push(CastMessage.NS_CONNECTION, """{"type":"CLOSE"}""")

        awaitTrue("the peer to close the connection") { active.isClosedByPeer() }
    }

    @Test
    fun `surfaces a rejected load`() {
        val device = FakeCastDevice(
            loadResponse = """{"type":"LOAD_FAILED","requestId":2,"reason":"INVALID_MEDIA"}""",
        ).also { server = it }
        val active = CastV2Client("127.0.0.1", device.port, plainSocketFactory).also { client = it }

        active.connect()
        val app = active.launchReceiver()

        val error = runCatching { active.load(app.transportId, STREAM_URL) }.exceptionOrNull()

        assertTrue(error is CastProtocolException)
        assertEquals("INVALID_MEDIA", error?.message)
    }

    private fun awaitTrue(what: String, timeoutMillis: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for $what")
    }
}

private fun receiverStatus(
    sessionId: String,
    transportId: String,
    appId: String = "CC1AD845",
): String = """{"type":"RECEIVER_STATUS","requestId":0,"status":{"applications":[{"appId":"$appId",""" +
    """"displayName":"Default Media Receiver","sessionId":"$sessionId","transportId":"$transportId",""" +
    """"statusText":"Ready To Cast","namespaces":[{"name":"urn:x-cast:com.google.cast.media"}]}]}}"""

private fun mediaStatus(): String =
    """{"type":"MEDIA_STATUS","requestId":0,"status":[{"mediaSessionId":7,""" +
        """"playerState":"PLAYING","idleReason":null,"media":{"contentId":"$STREAM_URL",""" +
        """"streamType":"LIVE","contentType":"application/x-mpegURL"}}]}"""

private class FakeCastDevice(private val loadResponse: String? = null) {
    private val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val output = AtomicReference<OutputStream?>()

    val messages = CopyOnWriteArrayList<CastMessage>()
    val port: Int get() = serverSocket.localPort

    private val worker = Thread({ serve() }, "FakeCastDevice").apply {
        isDaemon = true
        start()
    }

    private fun serve() {
        try {
            serverSocket.accept().use { socket ->
                output.set(socket.getOutputStream())
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                while (true) {
                    val length = input.readInt()
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    val message = CastMessageCodec.decode(bytes) ?: continue
                    messages += message
                    respond(message)
                }
            }
        } catch (_: IOException) {
            // The test closed the connection.
        }
    }

    private fun respond(message: CastMessage) {
        val payload = message.payloadUtf8.orEmpty()
        when {
            payload.contains("\"LAUNCH\"") -> push(
                CastMessage.NS_RECEIVER,
                receiverStatus("session-1", "transport-1"),
            )

            payload.contains("\"LOAD\"") -> push(
                CastMessage.NS_MEDIA,
                loadResponse ?: mediaStatus(),
            )

            payload.contains("\"PING\"") -> push(
                CastMessage.NS_HEARTBEAT,
                """{"type":"PONG"}""",
            )
        }
    }

    fun push(namespace: String, payload: String) {
        val stream = output.get() ?: return
        val encoded = CastMessageCodec.encode(
            CastMessage(
                sourceId = "receiver-0",
                destinationId = "sender-0",
                namespace = namespace,
                payloadUtf8 = payload,
            ),
        )
        synchronized(stream) {
            stream.write(
                byteArrayOf(
                    (encoded.size ushr 24).toByte(),
                    (encoded.size ushr 16).toByte(),
                    (encoded.size ushr 8).toByte(),
                    encoded.size.toByte(),
                ),
            )
            stream.write(encoded)
            stream.flush()
        }
    }

    fun close() {
        runCatching { serverSocket.close() }
        worker.interrupt()
    }
}
