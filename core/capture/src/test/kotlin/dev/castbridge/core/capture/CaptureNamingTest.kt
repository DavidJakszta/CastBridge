package dev.castbridge.core.capture

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureNamingTest {

    private val startedAt = Instant.parse("2026-11-15T14:30:12Z").toEpochMilli()

    @Test
    fun `file name contains a sortable timestamp`() {
        assertEquals(
            "CastBridge-20261115-143012.mp4",
            CaptureNaming.fileName(startedAt, ZoneId.of("UTC")),
        )
    }

    @Test
    fun `file name follows the local time zone`() {
        assertEquals(
            "CastBridge-20261115-153012.mp4",
            CaptureNaming.fileName(startedAt, ZoneId.of("Europe/Berlin")),
        )
    }

    @Test
    fun `file name always ends with mp4`() {
        assertTrue(CaptureNaming.fileName(System.currentTimeMillis()).endsWith(".mp4"))
    }
}
