package dev.castbridge.core.capture

import java.util.Locale

/** Compact, locale-independent labels for capture stats. */
object CaptureFormat {
    fun elapsed(millis: Long): String {
        val totalSeconds = (millis.coerceAtLeast(0) / 1000)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    fun bytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0)
        return when {
            value >= 1024L * 1024L * 1024L ->
                String.format(Locale.ROOT, "%.1f GB", value / (1024.0 * 1024.0 * 1024.0))
            value >= 1024L * 1024L ->
                String.format(Locale.ROOT, "%.1f MB", value / (1024.0 * 1024.0))
            value >= 1024L ->
                String.format(Locale.ROOT, "%.0f KB", value / 1024.0)
            else -> "$value B"
        }
    }

    fun bitrate(bitsPerSecond: Int): String =
        String.format(Locale.ROOT, "%.1f Mbps", bitsPerSecond / 1_000_000.0)

    fun resolution(width: Int, height: Int): String = "$width×$height"
}
