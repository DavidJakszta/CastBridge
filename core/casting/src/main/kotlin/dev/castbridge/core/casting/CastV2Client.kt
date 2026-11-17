package dev.castbridge.core.casting

import dev.castbridge.core.model.ChromecastDevice
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A Cast application announced by the receiver. */
internal data class ReceiverApplication(
    val appId: String,
    val sessionId: String,
    val transportId: String,
    val displayName: String?,
    val statusText: String?,
)

/** The last receiver status broadcast, or null while the device has not answered yet. */
internal data class ReceiverStatus(
    val applications: List<ReceiverApplication>,
)

/** The last media status for our loaded stream. */
internal data class MediaStatus(
    val mediaSessionId: Long,
    val playerState: String?,
    val idleReason: String?,
)

/**
 * The receiver app and media that ended up playing. The app can differ from the one that was
 * launched originally when a rejected load had to be recovered by restarting the receiver.
 */
internal data class LoadedMedia(
    val app: ReceiverApplication,
    val media: MediaStatus,
)

internal class CastProtocolException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * A minimal Google Cast v2 sender.
 *
 * It speaks the documented local protocol directly over TLS: protobuf `CastMessage` envelopes with
 * JSON payloads. No Play Services, Cast SDK, Google account, or internet connection is involved,
 * which keeps casting usable on an offline LAN.
 *
 * All methods are blocking; call them from an IO dispatcher.
 */
internal class CastV2Client(
    private val host: String,
    private val port: Int = ChromecastDevice.DEFAULT_CAST_PORT,
    private val socketFactory: CastSocketFactory = TlsCastSocketFactory,
    private val log: (String) -> Unit = {},
) : Closeable {
    private val inbox = LinkedBlockingQueue<CastMessage>(QUEUE_CAPACITY)
    private val requestIds = AtomicInteger(1)
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()

    @Volatile
    private var receiverStatus: ReceiverStatus? = null

    @Volatile
    private var mediaStatus: MediaStatus? = null

    @Volatile
    private var closedByPeer = false

    @Volatile
    private var lastError: String? = null

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var reader: Thread? = null
    private var heartbeat: Thread? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun connect(timeoutMillis: Long = CONNECT_TIMEOUT_MILLIS) {
        check(socket == null) { "The Cast client is already connected." }

        log("Connecting to $host:$port")
        val opened = socketFactory.open(host, port, timeoutMillis.toInt())
        socket = opened
        output = BufferedOutputStream(opened.getOutputStream())

        reader = Thread({ readLoop(opened) }, "CastReader").apply {
            isDaemon = true
            start()
        }
        heartbeat = Thread({ heartbeatLoop() }, "CastHeartbeat").apply {
            isDaemon = true
            start()
        }

        send(
            CastMessage(
                sourceId = SENDER_ID,
                destinationId = RECEIVER_ID,
                namespace = CastMessage.NS_CONNECTION,
                payloadUtf8 = """{"type":"CONNECT","origin":{}}""",
            ),
        )
    }

    /** Launches the Default Media Receiver and waits until it reports a transport id. */
    fun launchReceiver(
        appId: String = DEFAULT_MEDIA_RECEIVER_ID,
        timeoutMillis: Long = LAUNCH_TIMEOUT_MILLIS,
    ): ReceiverApplication {
        // Receiver status from before this LAUNCH is not an answer to it.
        inbox.clear()
        val requestId = requestIds.getAndIncrement()
        sendJson(
            destinationId = RECEIVER_ID,
            namespace = CastMessage.NS_RECEIVER,
            payload = """{"type":"LAUNCH","appId":"$appId","requestId":$requestId}""",
        )

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            val message = inbox.poll(POLL_SLICE_MILLIS, TimeUnit.MILLISECONDS) ?: continue
            if (message.namespace != CastMessage.NS_RECEIVER) continue

            val status = parseReceiverStatus(message.payloadUtf8) ?: continue
            status.applications.firstOrNull { it.appId == appId }?.let { app ->
                send(
                    CastMessage(
                        sourceId = SENDER_ID,
                        destinationId = app.transportId,
                        namespace = CastMessage.NS_CONNECTION,
                        payloadUtf8 = """{"type":"CONNECT","origin":{}}""",
                    ),
                )
                return app
            }
        }

        throw CastProtocolException(
            lastError ?: "The Chromecast did not start the receiver in time.",
        )
    }

    /**
     * Loads an HLS playlist and waits until the receiver reports playback.
     *
     * This is a single attempt; [loadWithRecovery] decides what to do when the receiver rejects
     * the stream. Connection failures are reported by [connect] and are not retried here.
     */
    fun load(
        transportId: String,
        url: String,
        title: String = "CastBridge",
        timeoutMillis: Long = LOAD_TIMEOUT_MILLIS,
    ): MediaStatus {
        val requestId = requestIds.getAndIncrement()
        val escapedUrl = jsonString(url)
        val escapedTitle = jsonString(title)
        // Status from before this LOAD belongs to the session being replaced.
        inbox.clear()
        log("LOAD requestId=$requestId url=$url")
        sendJson(
            destinationId = transportId,
            namespace = CastMessage.NS_MEDIA,
            payload = buildString {
                append("""{"type":"LOAD","requestId":$requestId,"autoplay":true,"currentTime":0,"media":{""")
                append(""""contentId":$escapedUrl,"streamType":"LIVE","contentType":"application/x-mpegURL",""")
                append(""""metadata":{"metadataType":0,"title":$escapedTitle},"customData":{}}}""")
            },
        )

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            val message = inbox.poll(POLL_SLICE_MILLIS, TimeUnit.MILLISECONDS) ?: continue
            if (message.namespace != CastMessage.NS_MEDIA) continue

            val payload = parseObject(message.payloadUtf8) ?: continue
            when (payload.string("type")) {
                "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST" -> {
                    val reason = payload.string("reason") ?: "The Chromecast rejected the stream."
                    throw CastProtocolException(reason)
                }

                "MEDIA_STATUS" -> {
                    val status = parseMediaStatus(payload)
                    if (status != null) {
                        mediaStatus = status
                        if (status.playerState == "PLAYING" || status.playerState == "BUFFERING") {
                            return status
                        }
                        // INTERRUPTED describes the item this LOAD replaced, not our load.
                        if (status.playerState == "IDLE" &&
                            status.idleReason != null &&
                            status.idleReason != "INTERRUPTED"
                        ) {
                            throw CastProtocolException(
                                "The Chromecast stopped playback (${status.idleReason}).",
                            )
                        }
                    }
                }
            }
        }

        throw CastProtocolException(
            lastError ?: "The Chromecast did not start playing the stream in time.",
        )
    }

    /**
     * Loads [url] on [app], restarting the receiver if it rejects the stream.
     *
     * A Default Media Receiver that lost its previous stream can stay stuck: it keeps answering
     * LOAD with LOAD_FAILED until the app itself is restarted, and reloading on the same session
     * does not help. So a rejected load ends the receiver session, launches a fresh one, and tries
     * again. The returned app is the one that is actually playing, which can be a new session.
     */
    fun loadWithRecovery(
        app: ReceiverApplication,
        url: String,
        title: String = "CastBridge",
    ): LoadedMedia {
        var current = app
        var lastError: CastProtocolException? = null

        for (attempt in 1..LOAD_ATTEMPTS) {
            try {
                return LoadedMedia(current, load(current.transportId, url, title))
            } catch (error: CastProtocolException) {
                lastError = error
                if (attempt >= LOAD_ATTEMPTS) break
                log("LOAD attempt $attempt failed (${error.message}); restarting the receiver")
                current = restartReceiver(current)
            }
        }

        throw lastError ?: CastProtocolException("The Chromecast rejected the stream.")
    }

    /**
     * Ends the receiver app and launches a fresh one, which clears a stuck media player.
     *
     * A LAUNCH that reaches the receiver before the stop has taken effect reports the old session,
     * so the launch is repeated until the receiver answers with a different session id.
     */
    private fun restartReceiver(previous: ReceiverApplication): ReceiverApplication {
        runCatching { stopReceiver(previous.sessionId) }
        var lastError: Exception? = null

        repeat(RESTART_ATTEMPTS) { index ->
            if (index > 0) runCatching { Thread.sleep(RESTART_PAUSE_MILLIS) }
            try {
                val app = launchReceiver(timeoutMillis = RESTART_LAUNCH_TIMEOUT_MILLIS)
                if (app.sessionId != previous.sessionId) return app
                lastError = CastProtocolException("The Chromecast did not restart the receiver.")
            } catch (error: Exception) {
                lastError = error
            }
        }

        throw CastProtocolException("The Chromecast did not restart the receiver.", lastError)
    }

    fun stopMedia(transportId: String, mediaSessionId: Long?) {
        val requestId = requestIds.getAndIncrement()
        val session = mediaSessionId?.let { ""","mediaSessionId":$it""" } ?: ""
        sendJson(
            destinationId = transportId,
            namespace = CastMessage.NS_MEDIA,
            payload = """{"type":"STOP","requestId":$requestId$session}""",
        )
    }

    fun stopReceiver(sessionId: String) {
        val requestId = requestIds.getAndIncrement()
        sendJson(
            destinationId = RECEIVER_ID,
            namespace = CastMessage.NS_RECEIVER,
            payload = """{"type":"STOP","sessionId":"$sessionId","requestId":$requestId}""",
        )
    }

    fun receiverStatus(): ReceiverStatus? = receiverStatus

    fun mediaStatus(): MediaStatus? = mediaStatus

    fun isClosedByPeer(): Boolean = closedByPeer

    fun lastError(): String? = lastError

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket?.close() }
        socket = null
        output = null
        heartbeat?.interrupt()
        reader?.interrupt()
        heartbeat = null
        reader = null
        inbox.clear()
    }

    private fun readLoop(socket: Socket) {
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            while (!socket.isClosed) {
                val length = input.readInt()
                if (length <= 0 || length > MAX_FRAME_BYTES) {
                    lastError = "The Chromecast sent an invalid message."
                    break
                }
                val bytes = ByteArray(length)
                input.readFully(bytes)
                val message = CastMessageCodec.decode(bytes) ?: continue
                dispatch(message)
            }
        } catch (_: IOException) {
            // Socket closed by us or by the device.
        } finally {
            closedByPeer = true
        }
    }

    private fun dispatch(message: CastMessage) {
        if (message.namespace != CastMessage.NS_HEARTBEAT) {
            log("recv ${message.namespace}: ${message.payloadUtf8}")
        }
        when (message.namespace) {
            CastMessage.NS_HEARTBEAT -> {
                if (message.payloadUtf8?.contains("\"PING\"") == true) {
                    runCatching {
                        send(
                            CastMessage(
                                sourceId = SENDER_ID,
                                destinationId = message.sourceId,
                                namespace = CastMessage.NS_HEARTBEAT,
                                payloadUtf8 = """{"type":"PONG"}""",
                            ),
                        )
                    }
                }
                return
            }

            CastMessage.NS_CONNECTION -> {
                if (message.payloadUtf8?.contains("\"CLOSE\"") == true) {
                    if (message.sourceId == RECEIVER_ID) {
                        // The device closed the sender's connection to the receiver itself.
                        closedByPeer = true
                    } else {
                        // The receiver app on a transport connection closed, which is what happens
                        // when another sender replaces it. That is a takeover, not a dead device, so
                        // the cached app is dropped and the monitor reports it on its next poll.
                        receiverStatus = null
                    }
                }
            }

            CastMessage.NS_RECEIVER -> {
                parseReceiverStatus(message.payloadUtf8)?.let { receiverStatus = it }
                parseObject(message.payloadUtf8)?.string("type")?.let { type ->
                    if (type.endsWith("_FAILED") || type == "INVALID_REQUEST") {
                        lastError = parseObject(message.payloadUtf8)?.string("reason")
                    }
                }
            }

            CastMessage.NS_MEDIA -> {
                val payload = parseObject(message.payloadUtf8)
                if (payload?.string("type") == "MEDIA_STATUS") {
                    parseMediaStatus(payload)?.let { mediaStatus = it }
                }
                if (payload?.string("type")?.endsWith("_FAILED") == true) {
                    lastError = payload.string("reason")
                }
            }
        }
        inbox.offer(message)
    }

    private fun heartbeatLoop() {
        while (!closed.get()) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL_MILLIS)
            } catch (_: InterruptedException) {
                return
            }
            if (closed.get()) return
            runCatching {
                send(
                    CastMessage(
                        sourceId = SENDER_ID,
                        destinationId = RECEIVER_ID,
                        namespace = CastMessage.NS_HEARTBEAT,
                        payloadUtf8 = """{"type":"PING"}""",
                    ),
                )
            }
        }
    }

    private fun sendJson(destinationId: String, namespace: String, payload: String) {
        send(
            CastMessage(
                sourceId = SENDER_ID,
                destinationId = destinationId,
                namespace = namespace,
                payloadUtf8 = payload,
            ),
        )
    }

    private fun send(message: CastMessage) {
        if (message.namespace != CastMessage.NS_HEARTBEAT) {
            log("send ${message.namespace}: ${message.payloadUtf8}")
        }
        val stream = output ?: throw CastProtocolException("The Cast connection is closed.")
        val payload = CastMessageCodec.encode(message)
        synchronized(writeLock) {
            stream.write(
                byteArrayOf(
                    (payload.size ushr 24).toByte(),
                    (payload.size ushr 16).toByte(),
                    (payload.size ushr 8).toByte(),
                    payload.size.toByte(),
                ),
            )
            stream.write(payload)
            stream.flush()
        }
    }

    private fun parseReceiverStatus(payload: String?): ReceiverStatus? {
        val root = parseObject(payload) ?: return null
        if (root.string("type") != "RECEIVER_STATUS") return null
        val applications = runCatching {
            root["status"]?.jsonObject?.get("applications")?.jsonArray?.mapNotNull { element ->
                val app = element.jsonObject
                val appId = app.string("appId") ?: return@mapNotNull null
                val sessionId = app.string("sessionId") ?: return@mapNotNull null
                val transportId = app.string("transportId") ?: return@mapNotNull null
                ReceiverApplication(
                    appId = appId,
                    sessionId = sessionId,
                    transportId = transportId,
                    displayName = app.string("displayName"),
                    statusText = app.string("statusText"),
                )
            }.orEmpty()
        }.getOrElse { return null }
        return ReceiverStatus(applications)
    }

    private fun parseMediaStatus(payload: JsonObject): MediaStatus? {
        val status = runCatching { payload["status"]?.jsonArray }.getOrNull() ?: return null
        val first = status.firstOrNull()?.jsonObject ?: return null
        return MediaStatus(
            mediaSessionId = first.long("mediaSessionId") ?: 0L,
            playerState = first.string("playerState"),
            idleReason = first.string("idleReason"),
        )
    }

    private fun parseObject(payload: String?): JsonObject? {
        if (payload.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.long(key: String): Long? =
        runCatching { this[key]?.jsonPrimitive?.longOrNull }.getOrNull()

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character < ' ') {
                        append("\\u").append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
            append('"')
        }

    companion object {
        const val DEFAULT_MEDIA_RECEIVER_ID = "CC1AD845"

        private const val SENDER_ID = "sender-0"
        private const val RECEIVER_ID = "receiver-0"
        private const val QUEUE_CAPACITY = 256
        private const val MAX_FRAME_BYTES = 1 shl 20
        private const val POLL_SLICE_MILLIS = 100L
        private const val CONNECT_TIMEOUT_MILLIS = 5_000L
        private const val LAUNCH_TIMEOUT_MILLIS = 15_000L
        private const val LOAD_TIMEOUT_MILLIS = 20_000L
        private const val LOAD_ATTEMPTS = 3
        private const val RESTART_ATTEMPTS = 3
        private const val RESTART_PAUSE_MILLIS = 1_500L
        private const val RESTART_LAUNCH_TIMEOUT_MILLIS = 10_000L
        private const val HEARTBEAT_INTERVAL_MILLIS = 5_000L
    }
}
