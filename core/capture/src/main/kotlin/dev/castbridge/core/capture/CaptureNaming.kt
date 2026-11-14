package dev.castbridge.core.capture

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Output file names are timestamped so every session keeps its own recording. */
object CaptureNaming {
    private val FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun fileName(startedAtMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = FORMATTER.format(Instant.ofEpochMilli(startedAtMillis).atZone(zone))
        return "CastBridge-$stamp.mp4"
    }
}
