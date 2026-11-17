package dev.castbridge.core.capture

import android.media.MediaCodecInfo

/**
 * Picks the lowest H.264 level that can carry the requested size and frame rate.
 *
 * Encoders otherwise advertise the highest level they support: Samsung reports level 5.2 for a
 * portrait 886x1920 stream. Cast receivers only accept up to level 4.2, so advertising the level the
 * content actually needs keeps the SPS inside the range TVs expect.
 *
 * Under-declaring is worse than over-declaring: 886x1920 at 59 fps needs 403k macroblocks per
 * second, so a stream that claims level 4.0 (245k) while carrying that many is invalid and strict
 * receivers refuse to play it. Callers must therefore pass the highest frame rate the encoder can
 * really emit, not the rate they asked for.
 */
internal object H264Levels {
    /**
     * The highest frame rate a mirrored display can hand the encoder.
     *
     * Surface input is paced by the display, not by [android.media.MediaFormat.KEY_FRAME_RATE], so
     * a mirrored 60 Hz screen can carry this many frames per second even when 30 are requested.
     * Whenever the platform cannot cap the encoder, the declared level has to cover this rate.
     */
    const val SURFACE_INPUT_CEILING_FPS = 60

    private class Level(
        val code: Int,
        val maxFrameSize: Int,
        val maxMacroblocksPerSecond: Int,
    )

    private val LEVELS = listOf(
        Level(MediaCodecInfo.CodecProfileLevel.AVCLevel31, 3_600, 108_000),
        Level(MediaCodecInfo.CodecProfileLevel.AVCLevel4, 8_192, 245_760),
        Level(MediaCodecInfo.CodecProfileLevel.AVCLevel41, 8_192, 245_760),
        Level(MediaCodecInfo.CodecProfileLevel.AVCLevel42, 8_704, 522_240),
        Level(MediaCodecInfo.CodecProfileLevel.AVCLevel5, 22_080, 589_824),
        Level(MediaCodecInfo.CodecProfileLevel.AVCLevel51, 36_864, 983_040),
        Level(MediaCodecInfo.CodecProfileLevel.AVCLevel52, 36_864, 2_073_600),
    )

    fun forFrame(width: Int, height: Int, frameRate: Int): Int {
        val macroblocks = ((width + 15) / 16).toLong() * ((height + 15) / 16)
        val macroblocksPerSecond = macroblocks * frameRate.coerceAtLeast(1)

        return LEVELS.firstOrNull {
            macroblocks <= it.maxFrameSize && macroblocksPerSecond <= it.maxMacroblocksPerSecond
        }?.code ?: MediaCodecInfo.CodecProfileLevel.AVCLevel52
    }
}
