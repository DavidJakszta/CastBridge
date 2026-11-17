package dev.castbridge.core.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpegTsMuxerTest {
    private val sps = bytes(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E)
    private val pps = bytes(0, 0, 0, 1, 0x68, 0xCE, 0x38, 0x80)
    private val idr = bytes(0, 0, 0, 1, 0x65, 0x88, 0x84, 0x21)
    private val pFrame = bytes(0, 0, 0, 1, 0x41, 0x9A, 0x02, 0x33)
    private val adts = bytes(0xFF, 0xF1, 0x50, 0x80, 0x00, 0x1F, 0xFC, 0x11, 0x22)

    @Test
    fun `writes PAT PMT and video packets in order`() {
        val muxer = MpegTsMuxer()
        muxer.setCodecConfig(VideoStreamFormat(1280, 720, sps, pps))
        muxer.startSegment()
        muxer.writeSample(EncodedVideoSample(idr, 1_000_000, isKeyFrame = true))
        muxer.writeSample(EncodedVideoSample(pFrame, 1_033_333, isKeyFrame = false))

        val segment = muxer.finishSegment()

        assertEquals(0, segment.size % TS_PACKET_SIZE)
        assertSyncBytes(segment)

        assertEquals(PID_PAT, pidAt(segment, 0))
        assertEquals(PID_PMT, pidAt(segment, 1))
        assertEquals(PID_VIDEO, pidAt(segment, 2))

        // PAT pointer field and table id.
        assertEquals(0x00, segment[4].toInt() and 0xFF)
        assertEquals(0x00, segment[5].toInt() and 0xFF)
        // PMT table id.
        assertEquals(0x02, segment[TS_PACKET_SIZE + 5].toInt() and 0xFF)
    }

    @Test
    fun `first video packet carries a PCR and random access indicator`() {
        val muxer = MpegTsMuxer()
        muxer.setCodecConfig(VideoStreamFormat(1280, 720, sps, pps))
        muxer.startSegment()
        muxer.writeSample(EncodedVideoSample(idr, 1_000_000, isKeyFrame = true))

        val segment = muxer.finishSegment()
        val packetStart = TS_PACKET_SIZE * 2

        assertEquals(0x47, segment[packetStart].toInt() and 0xFF)
        val adaptationLength = segment[packetStart + 4].toInt() and 0xFF
        assertTrue(adaptationLength >= 7)
        val flags = segment[packetStart + 5].toInt() and 0xFF
        assertEquals(0x50, flags and 0x50)
        assertEquals(0x50, flags)
    }

    @Test
    fun `keyframe PES contains SPS PPS and IDR and a valid PTS`() {
        val muxer = MpegTsMuxer()
        muxer.setCodecConfig(VideoStreamFormat(1280, 720, sps, pps))
        muxer.startSegment()
        muxer.writeSample(EncodedVideoSample(idr, 1_000_000, isKeyFrame = true))

        val segment = muxer.finishSegment()
        val packetStart = TS_PACKET_SIZE * 2
        val adaptationSize = (segment[packetStart + 4].toInt() and 0xFF) + 1
        val pesStart = packetStart + 4 + adaptationSize

        assertEquals(0x00, segment[pesStart].toInt() and 0xFF)
        assertEquals(0x00, segment[pesStart + 1].toInt() and 0xFF)
        assertEquals(0x01, segment[pesStart + 2].toInt() and 0xFF)
        assertEquals(0xE0, segment[pesStart + 3].toInt() and 0xFF)

        val pts = readPts(segment, pesStart + 9)
        assertEquals(90_000L, pts)

        assertNotNull(indexOf(segment, bytes(0, 0, 0, 1, 0x67)))
        assertNotNull(indexOf(segment, bytes(0, 0, 0, 1, 0x68)))
        assertNotNull(indexOf(segment, bytes(0, 0, 0, 1, 0x65)))
    }

    @Test
    fun `continuity counters advance per PID`() {
        val muxer = MpegTsMuxer()
        muxer.setCodecConfig(VideoStreamFormat(1280, 720, sps, pps))
        muxer.startSegment()
        muxer.writeSample(EncodedVideoSample(idr, 0, isKeyFrame = true))
        muxer.writeSample(EncodedVideoSample(pFrame, 33_333, isKeyFrame = false))

        val segment = muxer.finishSegment()
        val videoPackets = packets(segment).filter { pidAt(it) == PID_VIDEO }
        assertTrue(videoPackets.size >= 2)

        val counters = videoPackets.map { it[3].toInt() and 0x0F }
        for (index in 1 until counters.size) {
            assertEquals((counters[index - 1] + 1) and 0x0F, counters[index])
        }
    }

    @Test
    fun `PMT advertises a silent AAC track`() {
        val muxer = MpegTsMuxer()
        muxer.startSegment()
        muxer.writeSample(EncodedVideoSample(idr, 0, isKeyFrame = true))
        val pmt = packets(muxer.finishSegment())[1]

        val sectionLength = ((pmt[6].toInt() and 0x0F) shl 8) or (pmt[7].toInt() and 0xFF)
        assertEquals(23, sectionLength)
        assertEquals(0x1B, pmt[17].toInt() and 0xFF)
        assertEquals(PID_VIDEO, ((pmt[18].toInt() and 0x1F) shl 8) or (pmt[19].toInt() and 0xFF))
        assertEquals(0x0F, pmt[22].toInt() and 0xFF)
        assertEquals(PID_AUDIO, ((pmt[23].toInt() and 0x1F) shl 8) or (pmt[24].toInt() and 0xFF))
    }

    @Test
    fun `writes ADTS audio as a bounded PES on the audio PID`() {
        val muxer = MpegTsMuxer()
        muxer.startSegment()
        muxer.writeSample(EncodedVideoSample(idr, 0, isKeyFrame = true))
        muxer.writeAudioSample(EncodedAudioSample(adts, 33_333))

        val audio = packets(muxer.finishSegment()).first { pidAt(it) == PID_AUDIO }
        val adaptationSize = (audio[4].toInt() and 0xFF) + 1
        val pes = 4 + adaptationSize

        assertEquals(0xC0, audio[pes + 3].toInt() and 0xFF)
        val declaredLength = ((audio[pes + 4].toInt() and 0xFF) shl 8) or
            (audio[pes + 5].toInt() and 0xFF)
        assertEquals(8 + adts.size, declaredLength)
        assertEquals(2_999L, readPts(audio, pes + 9))
        assertNotNull(indexOf(audio, adts))
    }

    @Test
    fun `omits the audio track when disabled`() {
        val muxer = MpegTsMuxer(audioPid = null)
        muxer.startSegment()
        muxer.writeSample(EncodedVideoSample(idr, 0, isKeyFrame = true))
        muxer.writeAudioSample(EncodedAudioSample(adts, 0))
        val segment = muxer.finishSegment()

        assertTrue(packets(segment).none { pidAt(it) == PID_AUDIO })
        val pmt = packets(segment)[1]
        val sectionLength = ((pmt[6].toInt() and 0x0F) shl 8) or (pmt[7].toInt() and 0xFF)
        assertEquals(18, sectionLength)
    }

    @Test
    fun `normalises length prefixed AVCC data`() {
        val avcc = bytes(0, 0, 0, 2, 0x65, 0x88)
        val annexB = AnnexB.normalize(avcc)

        assertEquals(0x00, annexB[0].toInt() and 0xFF)
        assertEquals(0x00, annexB[1].toInt() and 0xFF)
        assertEquals(0x00, annexB[2].toInt() and 0xFF)
        assertEquals(0x01, annexB[3].toInt() and 0xFF)
        assertEquals(0x65, annexB[4].toInt() and 0xFF)
    }

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { values[it].toByte() }

    private fun assertSyncBytes(segment: ByteArray) {
        for (offset in segment.indices step TS_PACKET_SIZE) {
            assertEquals(0x47, segment[offset].toInt() and 0xFF)
        }
    }

    private fun packets(segment: ByteArray): List<ByteArray> =
        segment.toList()
            .chunked(TS_PACKET_SIZE)
            .map { it.toByteArray() }

    private fun pidAt(segment: ByteArray, packetIndex: Int): Int {
        val offset = packetIndex * TS_PACKET_SIZE
        return ((segment[offset + 1].toInt() and 0x1F) shl 8) or
            (segment[offset + 2].toInt() and 0xFF)
    }

    private fun pidAt(packet: ByteArray): Int =
        ((packet[1].toInt() and 0x1F) shl 8) or (packet[2].toInt() and 0xFF)

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
        const val PID_PAT = 0x0000
        const val PID_PMT = 0x1000
        const val PID_VIDEO = 0x0100
        const val PID_AUDIO = 0x0101
    }
}
