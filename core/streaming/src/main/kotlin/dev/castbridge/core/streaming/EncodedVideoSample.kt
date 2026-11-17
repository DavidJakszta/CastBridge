package dev.castbridge.core.streaming

/**
 * One encoded H.264 access unit.
 *
 * [data] is always Annex-B: NAL units separated by 00 00 01 or 00 00 00 01 start codes. The capture
 * pipeline normalises MediaCodec output before handing it to a sink so the HLS muxer never has to
 * guess between Annex-B and AVCC.
 */
class EncodedVideoSample(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val isKeyFrame: Boolean,
)

/**
 * One encoded AAC access unit, already wrapped in a seven-byte ADTS header.
 *
 * The Cast Default Media Receiver refuses a video-only transport stream, so every live stream
 * carries a silent AAC track alongside the video. Roku plays the same multiplexed stream.
 */
class EncodedAudioSample(
    val data: ByteArray,
    val presentationTimeUs: Long,
)

/** Encoder output format, including the SPS/PPS needed at the start of every segment. */
data class VideoStreamFormat(
    val width: Int,
    val height: Int,
    val sps: ByteArray? = null,
    val pps: ByteArray? = null,
)

/**
 * Receives live encoded samples.
 *
 * Implementations must be safe to call from the encoder thread and must not block for long: the
 * capture pipeline back-pressures the screen if a sink stalls.
 */
interface MediaSampleSink {
    fun onFormat(format: VideoStreamFormat)

    /** Raw codec-configuration NAL units, used when the format omits csd-0/csd-1. */
    fun onCodecConfig(data: ByteArray)

    fun onSample(sample: EncodedVideoSample)

    /** Silent audio frames interleaved with the video timeline. */
    fun onAudioSample(sample: EncodedAudioSample) = Unit

    /** Called exactly once when the encoder stops, including after a failure. */
    fun onEnd()
}
