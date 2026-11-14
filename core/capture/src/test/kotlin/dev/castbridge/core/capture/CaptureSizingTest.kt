package dev.castbridge.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSizingTest {

    @Test
    fun `caps the long edge and preserves the aspect ratio`() {
        val size = CaptureSizing.scaledSize(1080, 2400)

        assertEquals(864, size.width)
        assertEquals(1920, size.height)
    }

    @Test
    fun `landscape displays are capped the same way`() {
        val size = CaptureSizing.scaledSize(2400, 1080)

        assertEquals(1920, size.width)
        assertEquals(864, size.height)
    }

    @Test
    fun `sizes below the cap keep their pixels but become even`() {
        val size = CaptureSizing.scaledSize(1001, 777)

        assertEquals(1000, size.width)
        assertEquals(776, size.height)
    }

    @Test
    fun `small displays are not upscaled`() {
        val size = CaptureSizing.scaledSize(480, 320)

        assertEquals(480, size.width)
        assertEquals(320, size.height)
    }

    @Test
    fun `every dimension stays even and positive`() {
        val sizes = listOf(
            CaptureSizing.scaledSize(1080, 2400),
            CaptureSizing.scaledSize(1440, 3200),
            CaptureSizing.scaledSize(721, 1281),
            CaptureSizing.scaledSize(3, 5),
        )

        sizes.forEach { size ->
            assertTrue(size.width > 0 && size.height > 0)
            assertEquals(0, size.width % 2)
            assertEquals(0, size.height % 2)
        }
    }

    @Test
    fun `bitrate scales with pixels and frame rate`() {
        assertEquals(6_200_000, CaptureSizing.bitrateFor(1920, 1080, 30))
        assertEquals(2_800_000, CaptureSizing.bitrateFor(1280, 720, 30))
    }

    @Test
    fun `bitrate is clamped to a streamable range`() {
        assertEquals(1_500_000, CaptureSizing.bitrateFor(480, 320, 24))
        assertEquals(12_000_000, CaptureSizing.bitrateFor(3840, 2160, 60))
    }

    @Test
    fun `frame rate follows the display`() {
        assertEquals(30, CaptureSizing.frameRateFor(120f))
        assertEquals(30, CaptureSizing.frameRateFor(60f))
        assertEquals(24, CaptureSizing.frameRateFor(24f))
    }
}
