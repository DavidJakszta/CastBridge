package dev.castbridge.core.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsSegmenterTest {
    private val sps = bytes(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E)
    private val pps = bytes(0, 0, 0, 1, 0x68, 0xCE, 0x38, 0x80)
    private val idr = bytes(0, 0, 0, 1, 0x65, 0x88, 0x84)
    private val pFrame = bytes(0, 0, 0, 1, 0x41, 0x9A, 0x02)
    private val adts = bytes(0xFF, 0xF1, 0x50, 0x80, 0x00, 0x1F, 0xFC, 0x11, 0x22)

    @Test
    fun `cuts segments only at keyframes`() {
        val segments = mutableListOf<HlsSegment>()
        val segmenter = HlsSegmenter(targetDurationUs = 1_500_000) { segments += it }
        segmenter.onFormat(VideoStreamFormat(1280, 720, sps, pps))

        // Six seconds at 30 fps with a keyframe every two seconds.
        for (frame in 0..180) {
            val keyframe = frame % 60 == 0
            segmenter.onSample(
                EncodedVideoSample(
                    data = if (keyframe) idr else pFrame,
                    presentationTimeUs = frame * 33_333L,
                    isKeyFrame = keyframe,
                ),
            )
        }
        segmenter.finish()

        assertEquals(4, segments.size)
        assertEquals(1_999_980L, segments[0].durationUs)
        assertEquals(1_999_980L, segments[1].durationUs)
        assertEquals(1_999_980L, segments[2].durationUs)
        assertEquals(33_333L, segments[3].durationUs)
    }

    @Test
    fun `every segment starts with tables and codec configuration`() {
        val segments = mutableListOf<HlsSegment>()
        val segmenter = HlsSegmenter(targetDurationUs = 1_500_000) { segments += it }
        segmenter.onFormat(VideoStreamFormat(1280, 720, sps, pps))

        for (frame in 0..120) {
            val keyframe = frame % 60 == 0
            segmenter.onSample(
                EncodedVideoSample(
                    data = if (keyframe) idr else pFrame,
                    presentationTimeUs = frame * 33_333L,
                    isKeyFrame = keyframe,
                ),
            )
        }
        segmenter.finish()

        assertTrue(segments.isNotEmpty())
        segments.forEach { segment ->
            assertEquals(0x47, segment.data[0].toInt() and 0xFF)
            assertNotNull(indexOf(segment.data, bytes(0, 0, 0, 1, 0x67)))
            assertNotNull(indexOf(segment.data, bytes(0, 0, 0, 1, 0x68)))
        }
    }

    @Test
    fun `segment names follow the sequence`() {
        val names = mutableListOf<String>()
        val segmenter = HlsSegmenter(targetDurationUs = 1_000_000) { names += it.name }
        segmenter.onFormat(VideoStreamFormat(1280, 720, sps, pps))

        for (frame in 0..120) {
            val keyframe = frame % 30 == 0
            segmenter.onSample(
                EncodedVideoSample(
                    data = if (keyframe) idr else pFrame,
                    presentationTimeUs = frame * 33_333L,
                    isKeyFrame = keyframe,
                ),
            )
        }
        segmenter.finish()

        assertEquals(listOf("segment0.ts", "segment1.ts", "segment2.ts"), names)
    }

    @Test
    fun `rebases timestamps so the stream starts at zero`() {
        val segments = mutableListOf<HlsSegment>()
        val segmenter = HlsSegmenter(targetDurationUs = 1_500_000) { segments += it }
        segmenter.onFormat(VideoStreamFormat(1280, 720, sps, pps))

        val base = 5_000_000_000L
        segmenter.onSample(EncodedVideoSample(idr, base, isKeyFrame = true))
        segmenter.onSample(EncodedVideoSample(pFrame, base + 33_333, isKeyFrame = false))
        segmenter.finish()

        val data = segments.first().data
        val packetStart = TS_PACKET_SIZE * 2
        val adaptationSize = (data[packetStart + 4].toInt() and 0xFF) + 1
        val pesStart = packetStart + 4 + adaptationSize

        assertEquals(0L, readPts(data, pesStart + 9))
    }

    @Test
    fun `flushes a stalled segment so a static screen still becomes playable`() {
        val segments = mutableListOf<HlsSegment>()
        val segmenter = HlsSegmenter(targetDurationUs = 1_500_000) { segments += it }
        segmenter.onFormat(VideoStreamFormat(1280, 720, sps, pps))

        // One keyframe and one follow-up frame, then the screen stops changing.
        segmenter.onSample(EncodedVideoSample(idr, 0, isKeyFrame = true))
        segmenter.onSample(EncodedVideoSample(pFrame, 33_333, isKeyFrame = false))

        val flushed = segmenter.flushPending()

        assertNotNull(flushed)
        assertEquals(1, segments.size)
        assertEquals("segment0.ts", flushed?.name)

        // A second flush has nothing left to close.
        assertEquals(null, segmenter.flushPending())
    }

    @Test
    fun `drops frames until a keyframe after an idle flush`() {
        val segments = mutableListOf<HlsSegment>()
        val segmenter = HlsSegmenter(targetDurationUs = 1_500_000) { segments += it }
        segmenter.onFormat(VideoStreamFormat(1280, 720, sps, pps))

        segmenter.onSample(EncodedVideoSample(idr, 0, isKeyFrame = true))
        segmenter.flushPending()

        // These would start a segment on a P-frame, which no player could decode alone.
        segmenter.onSample(EncodedVideoSample(pFrame, 33_333, isKeyFrame = false))
        segmenter.onSample(EncodedVideoSample(pFrame, 66_666, isKeyFrame = false))

        assertEquals(1, segments.size)

        // The next keyframe starts a fresh, independently decodable segment.
        segmenter.onSample(EncodedVideoSample(idr, 99_999, isKeyFrame = true))
        segmenter.finish()

        assertEquals(2, segments.size)
        assertEquals("segment1.ts", segments[1].name)
    }

    @Test
    fun `adds silent audio to the open segment`() {
        val segments = mutableListOf<HlsSegment>()
        val segmenter = HlsSegmenter(targetDurationUs = 1_500_000) { segments += it }
        segmenter.onFormat(VideoStreamFormat(1280, 720, sps, pps))

        // Audio before the first keyframe has no segment to join.
        segmenter.onAudioSample(EncodedAudioSample(adts, 0))

        segmenter.onSample(EncodedVideoSample(idr, 1_000_000, isKeyFrame = true))
        segmenter.onAudioSample(EncodedAudioSample(adts, 1_010_000))
        segmenter.onSample(EncodedVideoSample(pFrame, 1_033_333, isKeyFrame = false))
        segmenter.finish()

        assertTrue(hasAudioPacket(segments.first().data))
    }

    @Test
    fun `audio alone does not open a segment`() {
        val segments = mutableListOf<HlsSegment>()
        val segmenter = HlsSegmenter(targetDurationUs = 1_500_000) { segments += it }
        segmenter.onFormat(VideoStreamFormat(1280, 720, sps, pps))

        segmenter.onAudioSample(EncodedAudioSample(adts, 0))

        assertEquals(null, segmenter.finish())
        assertTrue(segments.isEmpty())
    }

    private fun hasAudioPacket(data: ByteArray): Boolean {
        var offset = 0
        while (offset + TS_PACKET_SIZE <= data.size) {
            val pid = ((data[offset + 1].toInt() and 0x1F) shl 8) or
                (data[offset + 2].toInt() and 0xFF)
            if (pid == 0x0101) return true
            offset += TS_PACKET_SIZE
        }
        return false
    }

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { values[it].toByte() }

    private fun readPts(data: ByteArray, offset: Int): Long {
        val b0 = data[offset].toInt() and 0xFF
        val b1 = data[offset + 1].toInt() and 0xFF
        val b2 = data[offset + 2].toInt() and 0xFF
        val b3 = data[offset + 3].toInt() and 0xFF
        val b4 = data[offset + 4].toInt() and 0xFF

        return (((b0 shr 1) and 0x07).toLong() shl 30) or
            (b1.toLong() shl 22) or
            (((b2 shr 1) and 0x7F).toLong() shl 15) or
            (b3.toLong() shl 7) or
            ((b4 shr 1) and 0x7F).toLong()
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int? {
        if (needle.isEmpty() || needle.size > haystack.size) return null
        for (index in 0..haystack.size - needle.size) {
            var matches = true
            for (needleIndex in needle.indices) {
                if (haystack[index + needleIndex] != needle[needleIndex]) {
                    matches = false
                    break
                }
            }
            if (matches) return index
        }
        return null
    }

    private companion object {
        const val TS_PACKET_SIZE = 188
    }
}
