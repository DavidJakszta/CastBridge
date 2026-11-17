package dev.castbridge.core.streaming

import java.util.Locale
import kotlin.math.ceil

/**
 * Thread-safe segment store and live playlist.
 *
 * The playlist lists the newest [playlistWindow] segments. A few older segments stay available so a
 * player that is one request behind can still fetch them; they are not advertised any more.
 */
class HlsMediaStore(
    private val playlistWindow: Int = DEFAULT_PLAYLIST_WINDOW,
    private val retention: Int = DEFAULT_RETENTION,
) {
    private val lock = Any()
    private val segments = LinkedHashMap<Int, HlsSegment>()

    private var mediaSequence = 0
    private var targetDurationSeconds = DEFAULT_TARGET_DURATION_SECONDS
    private var ended = false
    private var totalSegments = 0
    private var totalBytes = 0L

    val segmentCount: Int get() = synchronized(lock) { totalSegments }

    val bytes: Long get() = synchronized(lock) { totalBytes }

    val isEnded: Boolean get() = synchronized(lock) { ended }

    fun add(segment: HlsSegment) {
        synchronized(lock) {
            segments[segment.sequence] = segment
            totalSegments++
            totalBytes += segment.data.size

            while (segments.size > retention) {
                val oldest = segments.keys.first()
                segments.remove(oldest)
            }

            val listed = segments.values.toList().takeLast(playlistWindow)
            mediaSequence = listed.firstOrNull()?.sequence ?: 0
            targetDurationSeconds = listed
                .maxOfOrNull { ceil(it.durationUs / MICROS_PER_SECOND).toInt() }
                ?.coerceAtLeast(1)
                ?: DEFAULT_TARGET_DURATION_SECONDS
        }
    }

    fun markEnded() {
        synchronized(lock) { ended = true }
    }

    fun playlist(): String = synchronized(lock) {
        buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:$targetDurationSeconds")
            appendLine("#EXT-X-MEDIA-SEQUENCE:$mediaSequence")
            appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
            segments.values.toList().takeLast(playlistWindow).forEach { segment ->
                appendLine("#EXTINF:${formatDuration(segment.durationUs)},")
                appendLine(segment.name)
            }
            if (ended) appendLine("#EXT-X-ENDLIST")
        }
    }

    fun segment(name: String): HlsSegment? = synchronized(lock) {
        segments.values.firstOrNull { it.name == name }
    }

    private fun formatDuration(durationUs: Long): String =
        String.format(Locale.ROOT, "%.3f", durationUs / MICROS_PER_SECOND)

    companion object {
        const val PLAYLIST_NAME = "index.m3u8"
        const val DEFAULT_PLAYLIST_WINDOW = 4
        const val DEFAULT_RETENTION = 8
        private const val DEFAULT_TARGET_DURATION_SECONDS = 2
        private const val MICROS_PER_SECOND = 1_000_000.0
    }
}
