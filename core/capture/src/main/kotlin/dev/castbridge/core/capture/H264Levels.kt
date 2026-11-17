package dev.castbridge.core.capture

import android.media.MediaCodecInfo

/**
 * Picks the lowest H.264 level that can carry the requested size and frame rate.
 *
 * Encoders otherwise advertise the highest level they support: Samsung reports level 5.2 for a
 * portrait 886x1920 stream. Cast receivers only accept up to level 4.2, so advertising the level the
 * content actually needs keeps the SPS inside the range TVs expect.
 */
internal object H264Levels {
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
