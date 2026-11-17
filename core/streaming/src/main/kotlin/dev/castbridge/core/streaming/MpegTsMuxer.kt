package dev.castbridge.core.streaming

import java.io.ByteArrayOutputStream

/**
 * Minimal MPEG-2 transport stream muxer for one H.264 video stream and an optional AAC audio track.
 *
 * Each segment starts with a PAT and PMT so a player can join at any segment. Every keyframe is
 * preceded by the SPS/PPS and marks the first transport packet as a random-access point with a PCR,
 * which is what HLS players need to start decoding without the previous segment.
 *
 * Audio exists because the Cast Default Media Receiver refuses a video-only transport stream; the
 * frames are silent, so Roku and Cast devices see a normal multiplexed HLS stream.
 */
class MpegTsMuxer(
    private val videoPid: Int = DEFAULT_VIDEO_PID,
    private val audioPid: Int? = DEFAULT_AUDIO_PID,
    private val pmtPid: Int = DEFAULT_PMT_PID,
    private val programNumber: Int = 1,
) {
    private val output = ByteArrayOutputStream(SEGMENT_INITIAL_CAPACITY)
    private val continuityCounters = IntArray(PID_LIMIT)
    private var codecConfig: ByteArray? = null

    val segmentSize: Int get() = output.size()

    fun setCodecConfig(format: VideoStreamFormat) {
        val sps = format.sps?.let(AnnexB::normalize)
        val pps = format.pps?.let(AnnexB::normalize)
        if (sps != null || pps != null) {
            val combined = ByteArrayOutputStream()
            sps?.let(combined::write)
            pps?.let(combined::write)
            codecConfig = combined.toByteArray()
        }
    }

    fun setCodecConfig(data: ByteArray) {
        if (data.isNotEmpty()) codecConfig = AnnexB.normalize(data)
    }

    /** Clears the buffer and writes the tables that must lead every segment. */
    fun startSegment() {
        output.reset()
        writePat()
        writePmt()
    }

    fun writeSample(sample: EncodedVideoSample) {
        val accessUnit = AnnexB.normalize(sample.data)
        if (accessUnit.isEmpty()) return

        val payload = if (sample.isKeyFrame) {
            codecConfig?.let { it + accessUnit } ?: accessUnit
        } else {
            accessUnit
        }

        val pts90k = sample.presentationTimeUs * TICKS_PER_SECOND / MICROS_PER_SECOND
        val pcr90k = (pts90k - PCR_DELAY_90K).coerceAtLeast(0L)
        writePes(
            streamId = PES_STREAM_ID_VIDEO,
            payload = payload,
            pts90k = pts90k,
            pcr90k = pcr90k,
            randomAccess = sample.isKeyFrame,
            boundedLength = false,
        )
    }

    /** Writes one ADTS-framed AAC access unit. Ignored when the muxer has no audio track. */
    fun writeAudioSample(sample: EncodedAudioSample) {
        if (audioPid == null) return
        if (sample.data.isEmpty()) return

        val pts90k = sample.presentationTimeUs * TICKS_PER_SECOND / MICROS_PER_SECOND
        writePes(
            streamId = PES_STREAM_ID_AUDIO,
            payload = sample.data,
            pts90k = pts90k,
            pcr90k = null,
            randomAccess = false,
            boundedLength = true,
        )
    }

    fun finishSegment(): ByteArray = output.toByteArray()

    private fun writePat() {
        val section = ByteArray(PAT_SECTION_SIZE)
        var index = 0
        section[index++] = 0x00
        section[index++] = 0xB0.toByte()
        section[index++] = PAT_SECTION_LENGTH.toByte()
        section[index++] = 0x00
        section[index++] = 0x01
        section[index++] = 0xC1.toByte()
        section[index++] = 0x00
        section[index++] = 0x00
        section[index++] = ((programNumber shr 8) and 0xFF).toByte()
        section[index++] = (programNumber and 0xFF).toByte()
        section[index++] = (0xE0 or ((pmtPid shr 8) and 0x1F)).toByte()
        section[index++] = (pmtPid and 0xFF).toByte()
        index = appendCrc(section, index)

        writePsiSection(PID_PAT, section, index)
    }

    private fun writePmt() {
        val streamCount = if (audioPid != null) 2 else 1
        val sectionLength = PMT_SECTION_BASE_LENGTH + PMT_STREAM_ENTRY_SIZE * streamCount
        val section = ByteArray(PSI_HEADER_SIZE + sectionLength)
        var index = 0
        section[index++] = 0x02
        section[index++] = 0xB0.toByte()
        section[index++] = (sectionLength and 0xFF).toByte()
        section[index++] = ((programNumber shr 8) and 0xFF).toByte()
        section[index++] = (programNumber and 0xFF).toByte()
        section[index++] = 0xC1.toByte()
        section[index++] = 0x00
        section[index++] = 0x00
        section[index++] = (0xE0 or ((videoPid shr 8) and 0x1F)).toByte()
        section[index++] = (videoPid and 0xFF).toByte()
        section[index++] = 0xF0.toByte()
        section[index++] = 0x00

        section[index++] = STREAM_TYPE_H264.toByte()
        section[index++] = (0xE0 or ((videoPid shr 8) and 0x1F)).toByte()
        section[index++] = (videoPid and 0xFF).toByte()
        section[index++] = 0xF0.toByte()
        section[index++] = 0x00

        audioPid?.let { pid ->
            section[index++] = STREAM_TYPE_AAC_ADTS.toByte()
            section[index++] = (0xE0 or ((pid shr 8) and 0x1F)).toByte()
            section[index++] = (pid and 0xFF).toByte()
            section[index++] = 0xF0.toByte()
            section[index++] = 0x00
        }

        index = appendCrc(section, index)

        writePsiSection(pmtPid, section, index)
    }

    private fun writePsiSection(pid: Int, section: ByteArray, size: Int) {
        val payload = ByteArray(TS_PAYLOAD_SIZE)
        payload[0] = 0x00
        System.arraycopy(section, 0, payload, 1, size)
        for (index in 1 + size until TS_PAYLOAD_SIZE) {
            payload[index] = 0xFF.toByte()
        }
        writeTsPacket(pid = pid, payloadUnitStart = true, adaptation = null, payload = payload)
    }

    private fun writePes(
        streamId: Int,
        payload: ByteArray,
        pts90k: Long,
        pcr90k: Long?,
        randomAccess: Boolean,
        boundedLength: Boolean,
    ) {
        val pesHeader = buildPesHeader(
            streamId = streamId,
            pts90k = pts90k,
            payloadSize = payload.size,
            boundedLength = boundedLength,
        )
        val pes = ByteArray(pesHeader.size + payload.size)
        System.arraycopy(pesHeader, 0, pes, 0, pesHeader.size)
        System.arraycopy(payload, 0, pes, pesHeader.size, payload.size)

        var offset = 0
        var firstPacket = true

        while (offset < pes.size) {
            val remaining = pes.size - offset
            val adaptationSize = when {
                firstPacket && pcr90k != null ->
                    maxOf(PCR_ADAPTATION_SIZE, TS_PAYLOAD_SIZE - remaining)
                remaining < TS_PAYLOAD_SIZE -> TS_PAYLOAD_SIZE - remaining
                else -> 0
            }
            val payloadSize = TS_PAYLOAD_SIZE - adaptationSize
            val adaptation = if (adaptationSize > 0) {
                buildAdaptationField(
                    size = adaptationSize,
                    pcr90k = if (firstPacket) pcr90k else null,
                    randomAccess = firstPacket && randomAccess,
                )
            } else {
                null
            }
            val chunk = ByteArray(payloadSize)
            System.arraycopy(pes, offset, chunk, 0, payloadSize)

            writeTsPacket(
                pid = if (streamId == PES_STREAM_ID_AUDIO) {
                    audioPid ?: error("Audio PES without an audio PID.")
                } else {
                    videoPid
                },
                payloadUnitStart = firstPacket,
                adaptation = adaptation,
                payload = chunk,
            )

            offset += payloadSize
            firstPacket = false
        }
    }

    private fun buildPesHeader(
        streamId: Int,
        pts90k: Long,
        payloadSize: Int,
        boundedLength: Boolean,
    ): ByteArray {
        val header = ByteArray(PES_HEADER_SIZE)
        header[0] = 0x00
        header[1] = 0x00
        header[2] = 0x01
        header[3] = streamId.toByte()
        // Video may use the unbounded form; audio needs a real length so players can parse frames.
        val length = if (boundedLength) PES_OPTIONAL_HEADER_SIZE + payloadSize else 0
        header[4] = ((length shr 8) and 0xFF).toByte()
        header[5] = (length and 0xFF).toByte()
        header[6] = 0x80.toByte()
        header[7] = 0x80.toByte()
        header[8] = 0x05
        writePts(header, 9, pts90k)
        return header
    }

    private fun writePts(target: ByteArray, offset: Int, pts90k: Long) {
        val pts = pts90k and PTS_MASK
        target[offset] = (0x21L or (((pts shr 30) and 0x07L) shl 1)).toByte()
        target[offset + 1] = ((pts shr 22) and 0xFFL).toByte()
        target[offset + 2] = ((((pts shr 15) and 0x7FL) shl 1) or 1L).toByte()
        target[offset + 3] = ((pts shr 7) and 0xFFL).toByte()
        target[offset + 4] = (((pts and 0x7FL) shl 1) or 1L).toByte()
    }

    private fun buildAdaptationField(size: Int, pcr90k: Long?, randomAccess: Boolean): ByteArray {
        val adaptation = ByteArray(size)
        adaptation[0] = (size - 1).toByte()
        if (size == 1) return adaptation

        var flags = 0
        if (randomAccess) flags = flags or 0x40
        if (pcr90k != null) flags = flags or 0x10
        adaptation[1] = flags.toByte()

        var index = 2
        if (pcr90k != null) {
            writePcr(adaptation, index, pcr90k)
            index += PCR_SIZE
        }
        while (index < size) {
            adaptation[index++] = 0xFF.toByte()
        }
        return adaptation
    }

    private fun writePcr(target: ByteArray, offset: Int, pcr90k: Long) {
        val base = pcr90k and PTS_MASK
        target[offset] = ((base shr 25) and 0xFFL).toByte()
        target[offset + 1] = ((base shr 17) and 0xFFL).toByte()
        target[offset + 2] = ((base shr 9) and 0xFFL).toByte()
        target[offset + 3] = ((base shr 1) and 0xFFL).toByte()
        target[offset + 4] = (((base and 0x01L) shl 7) or 0x7EL).toByte()
        target[offset + 5] = 0x00
    }

    private fun writeTsPacket(
        pid: Int,
        payloadUnitStart: Boolean,
        adaptation: ByteArray?,
        payload: ByteArray,
    ) {
        require(payload.size + (adaptation?.size ?: 0) == TS_PAYLOAD_SIZE) {
            "Transport packet payload must be exactly $TS_PAYLOAD_SIZE bytes."
        }

        val header = ByteArray(TS_HEADER_SIZE)
        header[0] = SYNC_BYTE
        header[1] = (
            (if (payloadUnitStart) 0x40 else 0x00) or
                ((pid shr 8) and 0x1F)
            ).toByte()
        header[2] = (pid and 0xFF).toByte()
        val adaptationFieldControl = if (adaptation == null) 0x10 else 0x30
        header[3] = (adaptationFieldControl or continuityCounters[pid]).toByte()

        output.write(header)
        adaptation?.let(output::write)
        output.write(payload)

        continuityCounters[pid] = (continuityCounters[pid] + 1) and 0x0F
    }

    private fun appendCrc(section: ByteArray, size: Int): Int {
        val crc = mpegCrc32(section, size)
        section[size] = (crc shr 24).toByte()
        section[size + 1] = (crc shr 16).toByte()
        section[size + 2] = (crc shr 8).toByte()
        section[size + 3] = crc.toByte()
        return size + 4
    }

    private fun mpegCrc32(data: ByteArray, size: Int): Int {
        var crc = -1
        for (index in 0 until size) {
            crc = crc xor ((data[index].toInt() and 0xFF) shl 24)
            repeat(8) {
                crc = if (crc and Int.MIN_VALUE != 0) {
                    (crc shl 1) xor CRC_POLYNOMIAL
                } else {
                    crc shl 1
                }
            }
        }
        return crc
    }

    companion object {
        const val DEFAULT_VIDEO_PID = 0x0100
        const val DEFAULT_AUDIO_PID = 0x0101
        const val DEFAULT_PMT_PID = 0x1000

        private const val PID_PAT = 0x0000
        private const val PID_LIMIT = 0x2000
        private const val TS_HEADER_SIZE = 4
        private const val TS_PAYLOAD_SIZE = 184
        private const val SYNC_BYTE = 0x47.toByte()
        private const val STREAM_TYPE_H264 = 0x1B
        private const val STREAM_TYPE_AAC_ADTS = 0x0F
        private const val PES_STREAM_ID_VIDEO = 0xE0
        private const val PES_STREAM_ID_AUDIO = 0xC0
        private const val PES_HEADER_SIZE = 14
        private const val PES_OPTIONAL_HEADER_SIZE = 8
        private const val PCR_SIZE = 6
        private const val PCR_ADAPTATION_SIZE = 8
        private const val PSI_HEADER_SIZE = 3
        private const val PAT_SECTION_SIZE = 16
        private const val PAT_SECTION_LENGTH = 13
        private const val PMT_SECTION_BASE_LENGTH = 13
        private const val PMT_STREAM_ENTRY_SIZE = 5
        private const val PTS_MASK = 0x1FFFFFFFFL
        private const val TICKS_PER_SECOND = 90_000L
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val PCR_DELAY_90K = 9_000L
        private const val CRC_POLYNOMIAL = 0x04C11DB7
        private const val SEGMENT_INITIAL_CAPACITY = 512 * 1024
    }
}
