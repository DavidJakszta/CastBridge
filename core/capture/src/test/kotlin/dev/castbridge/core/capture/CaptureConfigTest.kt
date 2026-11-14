package dev.castbridge.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureConfigTest {

    private fun config(
        width: Int = 1920,
        height: Int = 1080,
        frameRate: Int = 30,
        keyFrameInterval: Int = 2,
    ) = CaptureConfig(
        width = width,
        height = height,
        densityDpi = 420,
        frameRate = frameRate,
        bitrate = 6_200_000,
        keyFrameIntervalSeconds = keyFrameInterval,
    )

    @Test
    fun `valid config exposes its pixel count`() {
        assertEquals(2_073_600L, config().pixels)
    }

    @Test
    fun `zero dimensions are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { config(width = 0) }
        assertThrows(IllegalArgumentException::class.java) { config(height = -1) }
    }

    @Test
    fun `frame rate must stay in the supported range`() {
        assertThrows(IllegalArgumentException::class.java) { config(frameRate = 5) }
        assertThrows(IllegalArgumentException::class.java) { config(frameRate = 120) }
    }

    @Test
    fun `key frame interval must be sane`() {
        assertThrows(IllegalArgumentException::class.java) { config(keyFrameInterval = 0) }
        assertThrows(IllegalArgumentException::class.java) { config(keyFrameInterval = 60) }
    }
}
