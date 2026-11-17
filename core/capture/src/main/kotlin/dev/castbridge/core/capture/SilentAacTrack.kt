package dev.castbridge.core.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import java.nio.ByteBuffer

/**
 * A short loop of silent AAC-LC frames, produced once by the platform encoder.
 *
 * The Cast Default Media Receiver rejects a transport stream that has no audio track, so every live
 * stream carries this silent track. Encoding a second of digital silence and then looping those
 * frames avoids running a second encoder for the whole session: the samples are identical anyway,
 * only their timestamps advance.
 */
internal class SilentAacTrack(
    private val frames: List<ByteArray>,
    val sampleRate: Int,
    val channelCount: Int,
    val samplesPerFrame: Int,
) {
    val frameDurationUs: Long = samplesPerFrame * MICROS_PER_SECOND / sampleRate

    /** ADTS-framed silence for [index], looping over the encoded second. */
    fun frame(index: Long): ByteArray = frames[(index % frames.size).toInt()]

    /** Timestamp of [index] on a grid that starts at zero, in microseconds. */
    fun frameTimeUs(index: Long): Long = index * samplesPerFrame * MICROS_PER_SECOND / sampleRate

    companion object {
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val SAMPLE_RATE = 44_100
        private const val CHANNEL_COUNT = 1
        private const val SAMPLES_PER_FRAME = 1024
        private const val BIT_RATE = 16_000
        private const val INPUT_BUFFER_SIZE = SAMPLES_PER_FRAME * 2
        private const val TARGET_FRAMES = 48
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val ENCODE_TIMEOUT_MS = 5_000L
        private const val ADTS_HEADER_SIZE = 7
        private const val AAC_LC_PROFILE = 1
        private const val MICROS_PER_SECOND = 1_000_000L

        private val SAMPLING_FREQUENCIES = intArrayOf(
            96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050,
            16_000, 12_000, 11_025, 8_000, 7_350,
        )

        /** Returns null when the device has no AAC encoder, which only costs Chromecast support. */
        fun create(): SilentAacTrack? = runCatching { encodeSilence() }.getOrNull()

        private fun encodeSilence(): SilentAacTrack? {
            val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_BUFFER_SIZE)
            }

            val codec = MediaCodec.createEncoderByType(MIME_TYPE)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()

                val info = MediaCodec.BufferInfo()
                val zeros = ByteArray(INPUT_BUFFER_SIZE)
                val frames = ArrayList<ByteArray>(TARGET_FRAMES)
                var frequencyIndex = -1
                var channelConfiguration = CHANNEL_COUNT
                var queued = 0
                var endSignalled = false
                val deadline = SystemClock.elapsedRealtime() + ENCODE_TIMEOUT_MS

                while (SystemClock.elapsedRealtime() < deadline) {
                    if (queued < TARGET_FRAMES) {
                        val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val buffer = codec.getInputBuffer(inputIndex)
                            val size = minOf(INPUT_BUFFER_SIZE, buffer?.capacity() ?: INPUT_BUFFER_SIZE)
                            if (buffer != null) {
                                buffer.clear()
                                buffer.put(zeros, 0, size)
                            }
                            val ptsUs = queued * SAMPLES_PER_FRAME * MICROS_PER_SECOND / SAMPLE_RATE
                            codec.queueInputBuffer(inputIndex, 0, size, ptsUs, 0)
                            queued++
                        }
                    } else if (!endSignalled) {
                        val eosIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (eosIndex >= 0) {
                            val ptsUs = queued * SAMPLES_PER_FRAME * MICROS_PER_SECOND / SAMPLE_RATE
                            codec.queueInputBuffer(
                                eosIndex,
                                0,
                                0,
                                ptsUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            endSignalled = true
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val csd = codec.outputFormat.getByteBuffer("csd-0")?.copyBytes()
                            parseAudioSpecificConfig(csd)?.let { (frequency, channels) ->
                                frequencyIndex = frequency
                                channelConfiguration = channels
                            }
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER,
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED,
                        -> Unit

                        else -> if (outputIndex >= 0) {
                            val buffer = codec.getOutputBuffer(outputIndex)
                            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (buffer != null && info.size > 0 && !isConfig) {
                                val raw = buffer.copyBytes(info.offset, info.size)
                                frames += wrapAdts(raw, frequencyIndex, channelConfiguration)
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                        }
                    }
                }

                if (frames.isEmpty() || frequencyIndex < 0) return null
                return SilentAacTrack(
                    frames = frames,
                    sampleRate = SAMPLE_RATE,
                    channelCount = channelConfiguration,
                    samplesPerFrame = SAMPLES_PER_FRAME,
                )
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        }

        /** Reads sampling-frequency index and channel configuration from an AudioSpecificConfig. */
        private fun parseAudioSpecificConfig(csd: ByteArray?): Pair<Int, Int>? {
            if (csd == null || csd.size < 2) return null
            val objectType = (csd[0].toInt() and 0xFF) shr 3
            if (objectType == 31) return null
            val frequency = ((csd[0].toInt() and 0x07) shl 1) or
                ((csd[1].toInt() and 0x80) shr 7)
            val channels = (csd[1].toInt() and 0x78) shr 3
            if (frequency !in SAMPLING_FREQUENCIES.indices || channels == 0) return null
            return frequency to channels
        }

        private fun wrapAdts(raw: ByteArray, frequencyIndex: Int, channelConfiguration: Int): ByteArray {
            if (isAdts(raw)) return raw

            val frameLength = raw.size + ADTS_HEADER_SIZE
            val header = ByteArray(ADTS_HEADER_SIZE)
            header[0] = 0xFF.toByte()
            header[1] = 0xF1.toByte()
            header[2] = (
                (AAC_LC_PROFILE shl 6) or
                    ((frequencyIndex and 0x0F) shl 2) or
                    ((channelConfiguration shr 2) and 0x01)
                ).toByte()
            header[3] = (
                ((channelConfiguration and 0x03) shl 6) or
                    ((frameLength shr 11) and 0x03)
                ).toByte()
            header[4] = ((frameLength shr 3) and 0xFF).toByte()
            header[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
            header[6] = 0xFC.toByte()
            return header + raw
        }

        private fun isAdts(data: ByteArray): Boolean =
            data.size >= ADTS_HEADER_SIZE &&
                (data[0].toInt() and 0xFF) == 0xFF &&
                (data[1].toInt() and 0xF6) == 0xF0

        private fun ByteBuffer.copyBytes(offset: Int, size: Int): ByteArray {
            val duplicate = duplicate()
            duplicate.position(offset)
            duplicate.limit(offset + size)
            val data = ByteArray(size)
            duplicate.get(data)
            return data
        }

        private fun ByteBuffer.copyBytes(): ByteArray {
            val duplicate = duplicate()
            val data = ByteArray(duplicate.remaining())
            duplicate.get(data)
            return data
        }
    }
}
