package dev.castbridge.core.capture

import android.media.MediaCodecInfo.CodecProfileLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class H264LevelsTest {

    @Test
    fun `720p at 30 fps fits level 3_1`() {
        assertEquals(CodecProfileLevel.AVCLevel31, H264Levels.forFrame(1280, 720, 30))
    }

    @Test
    fun `720p at the display frame rate needs level 4`() {
        assertEquals(CodecProfileLevel.AVCLevel4, H264Levels.forFrame(1280, 720, 60))
    }

    @Test
    fun `portrait 1080p at 30 fps fits level 4`() {
        assertEquals(CodecProfileLevel.AVCLevel4, H264Levels.forFrame(886, 1920, 30))
    }

    /**
     * 886x1920 is 6720 macroblocks, so 60 fps needs 403k macroblocks per second. Level 4.0 only
     * allows 245k, and a stream that claims 4.0 while carrying this much data is invalid.
     */
    @Test
    fun `portrait 1080p at the display frame rate needs level 4_2`() {
        assertEquals(CodecProfileLevel.AVCLevel42, H264Levels.forFrame(886, 1920, 60))
    }

    @Test
    fun `requests beyond every level fall back to the highest one`() {
        assertEquals(CodecProfileLevel.AVCLevel52, H264Levels.forFrame(4096, 2160, 120))
    }

    @Test
    fun `the surface input ceiling covers a 60 Hz mirrored screen`() {
        assertEquals(60, H264Levels.SURFACE_INPUT_CEILING_FPS)
        assertEquals(
            H264Levels.forFrame(886, 1920, H264Levels.SURFACE_INPUT_CEILING_FPS),
            H264Levels.forFrame(886, 1920, 60),
        )
    }
}
