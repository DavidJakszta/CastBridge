package dev.castbridge.core.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureFormatTest {

    @Test
    fun `elapsed time uses minutes below an hour`() {
        assertEquals("00:00", CaptureFormat.elapsed(0))
        assertEquals("01:05", CaptureFormat.elapsed(65_000))
        assertEquals("00:59", CaptureFormat.elapsed(59_999))
    }

    @Test
    fun `elapsed time adds hours when needed`() {
        assertEquals("1:02:05", CaptureFormat.elapsed(3_725_000))
    }

    @Test
    fun `negative elapsed time is clamped`() {
        assertEquals("00:00", CaptureFormat.elapsed(-1_000))
    }

    @Test
    fun `byte sizes use readable units`() {
        assertEquals("999 B", CaptureFormat.bytes(999))
        assertEquals("2 KB", CaptureFormat.bytes(2_048))
        assertEquals("5.0 MB", CaptureFormat.bytes(5L * 1024 * 1024))
        assertEquals("1.0 GB", CaptureFormat.bytes(1024L * 1024 * 1024))
    }

    @Test
    fun `bitrate is shown in megabits`() {
        assertEquals("6.2 Mbps", CaptureFormat.bitrate(6_200_000))
        assertEquals("1.5 Mbps", CaptureFormat.bitrate(1_500_000))
    }

    @Test
    fun `resolution uses a multiplication sign`() {
        assertEquals("1920×864", CaptureFormat.resolution(1920, 864))
    }
}
