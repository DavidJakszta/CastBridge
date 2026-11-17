package dev.castbridge.core.streaming

import java.net.InetAddress

class StreamingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Owns one live HLS session: segmenter, media store, and HTTP server.
 *
 * Samples arrive from the encoder thread. The session publishes [StreamingState.Live] only after the
 * first complete segment exists, so a TV never receives a playlist that it would treat as empty.
 *
 * A screen that does not change produces no encoder output at all, which would leave the first
 * segment open indefinitely. An idle watchdog therefore closes a stalled segment so the stream still
 * becomes playable; playback simply freezes on the last frame until the screen changes again.
 */
class HlsStreamingSession(
    private val metadata: StreamingMetadata,
    private val targetSegmentDurationUs: Long = HlsSegmenter.DEFAULT_TARGET_DURATION_US,
    private val playlistWindow: Int = HlsMediaStore.DEFAULT_PLAYLIST_WINDOW,
    private val clock: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = {},
) : MediaSampleSink, AutoCloseable {
    private val token = TokenGenerator.newToken()
    private val store = HlsMediaStore(playlistWindow = playlistWindow)
    private val segmenter = HlsSegmenter(targetSegmentDurationUs) { segment ->
        store.add(segment)
        publishLive()
    }

    private var server: HlsHttpServer? = null
    private var url: String? = null
    private var width = metadata.width
    private var height = metadata.height
    private var startedAtNanos = clock()
    private var lastStatsAtNanos = startedAtNanos

    /** Written by the encoder thread, read by the idle watchdog. */
    @Volatile
    private var lastSampleAtNanos = startedAtNanos

    private var ended = false

    @Volatile
    private var closed = false

    private var watchdog: Thread? = null

    /** Binds the HTTP server and returns the playlist URL. */
    fun start(bindAddress: InetAddress? = LocalNetwork.wifiIpv4()): String {
        check(server == null) { "The stream is already running." }

        val address = bindAddress ?: throw StreamingException(
            "No Wi-Fi address is available. Connect the phone to the same Wi-Fi as the TV.",
        )

        val httpServer = HlsHttpServer(store = store, token = token, log = log)
        val port = try {
            httpServer.start(address, port = 0)
        } catch (error: Exception) {
            throw StreamingException("Could not start the local stream server.", error)
        }

        server = httpServer
        startedAtNanos = clock()
        lastStatsAtNanos = startedAtNanos
        lastSampleAtNanos = startedAtNanos

        val playlistUrl =
            "http://${address.hostAddress}:$port/s/$token/${HlsMediaStore.PLAYLIST_NAME}"
        url = playlistUrl
        StreamingStore.update(StreamingState.Preparing)
        log("Stream URL: $playlistUrl")
        startWatchdog()
        return playlistUrl
    }

    override fun onFormat(format: VideoStreamFormat) {
        width = format.width
        height = format.height
        segmenter.onFormat(format)
    }

    override fun onCodecConfig(data: ByteArray) {
        segmenter.onCodecConfig(data)
    }

    override fun onSample(sample: EncodedVideoSample) {
        lastSampleAtNanos = clock()
        segmenter.onSample(sample)

        val now = clock()
        if (now - lastStatsAtNanos >= STATS_INTERVAL_NANOS) {
            lastStatsAtNanos = now
            publishLive()
        }
    }

    /**
     * Audio never counts as activity: a static screen still produces silent frames, and letting them
     * reset the idle watchdog would leave the first segment open forever.
     */
    override fun onAudioSample(sample: EncodedAudioSample) {
        segmenter.onAudioSample(sample)
    }

    override fun onEnd() {
        if (ended) return
        ended = true
        closed = true
        watchdog?.interrupt()
        watchdog = null

        runCatching { segmenter.finish() }
        store.markEnded()
        StreamingStore.update(StreamingState.Stopped(summary()))
        stopServer()
    }

    override fun close() {
        if (ended) {
            stopServer()
            return
        }
        ended = true
        closed = true
        watchdog?.interrupt()
        watchdog = null

        runCatching { segmenter.finish() }
        store.markEnded()
        stopServer()

        when (StreamingStore.state.value) {
            is StreamingState.Preparing,
            is StreamingState.Live,
            is StreamingState.Stopping,
            -> StreamingStore.update(StreamingState.Stopped(summary()))

            else -> Unit
        }
    }

    private fun startWatchdog() {
        val thread = Thread({ watchForIdleScreen() }, "HlsIdleWatchdog").apply {
            isDaemon = true
            start()
        }
        watchdog = thread
    }

    private fun watchForIdleScreen() {
        while (!closed) {
            try {
                Thread.sleep(WATCHDOG_INTERVAL_MILLIS)
            } catch (_: InterruptedException) {
                return
            }
            if (closed) return

            val idleNanos = clock() - lastSampleAtNanos
            if (idleNanos < IDLE_FLUSH_NANOS) continue

            val flushed = runCatching { segmenter.flushPending() }.getOrNull() ?: continue
            log("Screen was idle, closed segment ${flushed.sequence} to keep the stream alive")
            lastSampleAtNanos = clock()
        }
    }

    private fun publishLive() {
        val playlistUrl = url ?: return
        if (store.segmentCount == 0) return

        StreamingStore.update(
            StreamingState.Live(
                url = playlistUrl,
                stats = StreamingStats(
                    elapsedMillis = elapsedMillis(),
                    segments = store.segmentCount,
                    bytes = store.bytes,
                    width = width,
                    height = height,
                    frameRate = metadata.frameRate,
                    bitrate = metadata.bitrate,
                ),
            ),
        )
    }

    private fun summary(): StreamingSummary = StreamingSummary(
        durationMillis = elapsedMillis(),
        segments = store.segmentCount,
        bytes = store.bytes,
    )

    private fun elapsedMillis(): Long =
        ((clock() - startedAtNanos) / NANOS_PER_MILLISECOND).coerceAtLeast(0)

    private fun stopServer() {
        server?.stop()
        server = null
    }

    private companion object {
        const val STATS_INTERVAL_NANOS = 500_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val WATCHDOG_INTERVAL_MILLIS = 500L

        /** Long enough that ordinary frame gaps are not cut, short enough to feel responsive. */
        const val IDLE_FLUSH_NANOS = 2_500_000_000L
    }
}
