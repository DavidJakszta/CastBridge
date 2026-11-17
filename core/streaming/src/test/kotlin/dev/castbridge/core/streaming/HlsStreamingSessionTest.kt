package dev.castbridge.core.streaming

import java.net.InetAddress
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsStreamingSessionTest {
    private val sps = bytes(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E)
    private val pps = bytes(0, 0, 0, 1, 0x68, 0xCE, 0x38, 0x80)
    private val idr = bytes(0, 0, 0, 1, 0x65, 0x88, 0x84)
    private val pFrame = bytes(0, 0, 0, 1, 0x41, 0x9A, 0x02)

    private var session: HlsStreamingSession? = null

    @After
    fun tearDown() {
        session?.close()
        StreamingStore.reset()
    }

    @Test
    fun `serves a live playlist and segment over loopback HTTP`() {
        StreamingStore.reset()

        val active = HlsStreamingSession(
            metadata = StreamingMetadata(
                width = 640,
                height = 360,
                frameRate = 30,
                bitrate = 2_000_000,
            ),
            targetSegmentDurationUs = 1_000_000,
        )
        session = active

        val url = active.start(InetAddress.getByName("127.0.0.1"))
        active.onFormat(VideoStreamFormat(640, 360, sps, pps))

        // Three keyframes at 0, 1, and 2 seconds produce the first complete segment.
        for (frame in 0..65) {
            val keyframe = frame % 30 == 0
            active.onSample(
                EncodedVideoSample(
                    data = if (keyframe) idr else pFrame,
                    presentationTimeUs = 7_000_000_000L + frame * 33_333L,
                    isKeyFrame = keyframe,
                ),
            )
        }

        val state = StreamingStore.state.value
        assertTrue("Expected a live state but was $state", state is StreamingState.Live)
        state as StreamingState.Live
        assertEquals(url, state.url)
        assertTrue(state.stats.segments >= 1)

        val playlist = URL(url).openStream().bufferedReader().use { it.readText() }
        assertTrue(playlist.startsWith("#EXTM3U"))
        assertTrue(playlist.contains("segment0.ts"))

        val segmentUrl = url.substringBeforeLast('/') + "/segment0.ts"
        val segment = URL(segmentUrl).openStream().use { it.readBytes() }
        assertTrue(segment.size > 0)
        assertEquals(0, segment.size % TS_PACKET_SIZE)
        assertEquals(0x47, segment[0].toInt() and 0xFF)
    }

    @Test
    fun `publishes a live playlist for a screen that stops changing`() {
        StreamingStore.reset()

        val clock = java.util.concurrent.atomic.AtomicLong(0L)
        val active = HlsStreamingSession(
            metadata = StreamingMetadata(
                width = 640,
                height = 360,
                frameRate = 30,
                bitrate = 2_000_000,
            ),
            clock = { clock.get() },
        )
        session = active

        val url = active.start(InetAddress.getByName("127.0.0.1"))
        active.onFormat(VideoStreamFormat(640, 360, sps, pps))

        // One keyframe, then the screen never changes again: no further frames are produced.
        active.onSample(EncodedVideoSample(idr, 5_000_000L, isKeyFrame = true))

        // The idle watchdog should close the stalled segment instead of waiting forever.
        clock.addAndGet(5_000_000_000L)

        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline &&
            StreamingStore.state.value !is StreamingState.Live
        ) {
            Thread.sleep(50)
        }

        val state = StreamingStore.state.value
        assertTrue("Expected a live state but was $state", state is StreamingState.Live)
        state as StreamingState.Live
        assertEquals(url, state.url)
        assertEquals(1, state.stats.segments)
    }

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { values[it].toByte() }

    private companion object {
        const val TS_PACKET_SIZE = 188
    }
}
