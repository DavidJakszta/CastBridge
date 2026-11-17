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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(12.5, media.currentTime, 0.001)

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

        device.push(CastMessage.NS_CONNECTION, """{"type":"CLOSE"}""", sourceId = "receiver-0")

        awaitTrue("the peer to close the connection") { active.isClosedByPeer() }
    }

    @Test
    fun `treats a closed app connection as a takeover, not a dead device`() {
        val device = FakeCastDevice().also { server = it }
        val active = CastV2Client("127.0.0.1", device.port, plainSocketFactory).also { client = it }

        active.connect()
        val app = active.launchReceiver()
        active.load(app.transportId, STREAM_URL)

        // Another sender replaced the receiver app: its transport connection closes, but the TCP
        // connection to the device stays open.
        device.push(CastMessage.NS_CONNECTION, """{"type":"CLOSE"}""", sourceId = app.transportId)

        awaitTrue("the receiver app to be forgotten") { active.receiverStatus() == null }
        assertFalse(active.isClosedByPeer())
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

    @Test
    fun `restarts the receiver when it keeps rejecting the stream`() {
        val device = FakeCastDevice(failLoads = 1).also { server = it }
        val active = CastV2Client("127.0.0.1", device.port, plainSocketFactory).also { client = it }

        active.connect()
        val app = active.launchReceiver()

        val loaded = active.loadWithRecovery(app, STREAM_URL)

        assertEquals("PLAYING", loaded.media.playerState)
        // The stuck app was replaced by a fresh one before the stream was accepted.
        assertTrue(loaded.app.sessionId != app.sessionId)
        // A restarted receiver reuses media session ids; the retry's status must not be discarded.
        assertEquals(REJECTED_SESSION, loaded.media.mediaSessionId)
        assertEquals(2, device.loadCount)
        assertEquals(2, device.launchCount)
    }

    @Test
    fun `gives up after restarting the receiver twice`() {
        val device = FakeCastDevice(failLoads = 9).also { server = it }
        val active = CastV2Client("127.0.0.1", device.port, plainSocketFactory).also { client = it }

        active.connect()
        val app = active.launchReceiver()

        val error = runCatching { active.loadWithRecovery(app, STREAM_URL) }.exceptionOrNull()

        assertTrue(error is CastProtocolException)
        assertEquals(3, device.loadCount)
        assertEquals(3, device.launchCount)
    }

    @Test
    fun `waits through buffering until the receiver is actually playing`() {
        val device = FakeCastDevice(bufferBeforePlay = true).also { server = it }
        val active = CastV2Client("127.0.0.1", device.port, plainSocketFactory).also { client = it }

        active.connect()
        val app = active.launchReceiver()

        val media = active.load(app.transportId, STREAM_URL, timeoutMillis = 3_000)

        // BUFFERING is only a step on the way; returning on it hid an endless TV spinner.
        assertEquals("PLAYING", media.playerState)
    }

    @Test
    fun `treats a receiver stuck on buffering as a failed load`() {
        val device = FakeCastDevice(stallOnBuffer = true).also { server = it }
        val active = CastV2Client("127.0.0.1", device.port, plainSocketFactory).also { client = it }

        active.connect()
        val app = active.launchReceiver()

        val error = runCatching {
            active.load(app.transportId, STREAM_URL, timeoutMillis = 400)
        }.exceptionOrNull()

        assertTrue(error is CastProtocolException)
    }

    @Test
    fun `force restart replaces a receiver that is already playing`() {
        val device = FakeCastDevice().also { server = it }
        val active = CastV2Client("127.0.0.1", device.port, plainSocketFactory).also { client = it }

        active.connect()
        val app = active.launchReceiver()

        val loaded = active.loadWithRecovery(app, STREAM_URL, forceRestart = true)

        assertEquals("PLAYING", loaded.media.playerState)
        assertTrue(loaded.app.sessionId != app.sessionId)
        assertEquals(1, device.loadCount)
        assertEquals(2, device.launchCount)
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

private fun mediaStatus(
    sessionId: Long = 7,
    state: String = "PLAYING",
    currentTime: Double = 12.5,
): String =
    """{"type":"MEDIA_STATUS","requestId":0,"status":[{"mediaSessionId":$sessionId,""" +
        """"playerState":"$state","currentTime":$currentTime,"idleReason":null,""" +
        """"media":{"contentId":"$STREAM_URL",""" +
        """"streamType":"LIVE","contentType":"application/x-mpegURL"}}]}"""

/**
 * The media session id the fake receiver reports. A restarted Default Media Receiver reuses the
 * same numbering, so a rejected load and the load that follows a restart both use [REJECTED_SESSION].
 */
private const val REJECTED_SESSION = 7L

private class FakeCastDevice(
    private val loadResponse: String? = null,
    private val failLoads: Int = 0,
    private val bufferBeforePlay: Boolean = false,
    private val stallOnBuffer: Boolean = false,
) {
    private val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val output = AtomicReference<OutputStream?>()
    private val loads = AtomicInteger(0)
    private val launches = AtomicInteger(0)

    val messages = CopyOnWriteArrayList<CastMessage>()
    val port: Int get() = serverSocket.localPort
    val loadCount: Int get() = loads.get()
    val launchCount: Int get() = launches.get()

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
            payload.contains("\"LAUNCH\"") -> {
                val session = launches.incrementAndGet()
                push(
                    CastMessage.NS_RECEIVER,
                    receiverStatus("session-$session", "transport-$session"),
                )
            }

            message.namespace == CastMessage.NS_RECEIVER && payload.contains("\"STOP\"") -> push(
                CastMessage.NS_RECEIVER,
                """{"type":"RECEIVER_STATUS","requestId":0,"status":{"applications":[]}}""",
            )

            payload.contains("\"LOAD\"") -> {
                val attempt = loads.getAndIncrement()
                if (attempt < failLoads) {
                    // Mirror a real receiver: announce the rejected session, reject the load, then
                    // keep broadcasting that session's error state for a moment.
                    push(CastMessage.NS_MEDIA, mediaStatus(sessionId = REJECTED_SESSION, state = "IDLE"))
                    push(
                        CastMessage.NS_MEDIA,
                        """{"type":"LOAD_FAILED","requestId":1,"reason":"LOAD_FAILED"}""",
                    )
                    push(
                        CastMessage.NS_MEDIA,
                        """{"type":"MEDIA_STATUS","status":[{"mediaSessionId":$REJECTED_SESSION,""" +
                            """"playerState":"IDLE","idleReason":"ERROR"}]}""",
                    )
                } else if (stallOnBuffer) {
                    // A stuck Default Media Receiver: the session exists and buffers forever.
                    push(CastMessage.NS_MEDIA, mediaStatus(state = "BUFFERING", currentTime = 0.0))
                } else {
                    if (bufferBeforePlay) {
                        push(
                            CastMessage.NS_MEDIA,
                            mediaStatus(state = "BUFFERING", currentTime = 0.0),
                        )
                        Thread.sleep(50)
                    }
                    push(CastMessage.NS_MEDIA, loadResponse ?: mediaStatus())
                }
            }

            payload.contains("\"PING\"") -> push(
                CastMessage.NS_HEARTBEAT,
                """{"type":"PONG"}""",
            )
        }
    }

    fun push(namespace: String, payload: String, sourceId: String = "receiver-0") {
        val stream = output.get() ?: return
        val encoded = CastMessageCodec.encode(
            CastMessage(
                sourceId = sourceId,
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
