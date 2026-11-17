package dev.castbridge.core.streaming

import java.util.Locale
import kotlin.math.ceil

/**
 * Thread-safe segment store and live playlist.
 *
 * The playlist lists the newest [playlistWindow] segments. A few older segments stay available so a
 * player that is one request behind can still fetch them; they are not advertised any more.
 *
 * Live playlists carry an `#EXT-X-START` tag so a receiver begins a few seconds behind the live
 * edge. Without it a player starts on the newest segment, which has zero seconds of runway: this
 * phone produces segments in real time, so such a player can never build a buffer and spends its
 * life alternating between playing and rebuffering.
 */
class HlsMediaStore(
    private val playlistWindow: Int = DEFAULT_PLAYLIST_WINDOW,
    private val retention: Int = DEFAULT_RETENTION,
) {
    private val lock = Any()
    private val segments = LinkedHashMap<Int, HlsSegment>()

    private var mediaSequence = 0
    private var targetDurationSeconds = DEFAULT_TARGET_DURATION_SECONDS
    private var startOffsetSeconds = 0
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
            startOffsetSeconds = startOffsetFor(listed)
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
            if (!ended && startOffsetSeconds > 0) {
                appendLine("#EXT-X-START:TIME-OFFSET=-$startOffsetSeconds")
            }
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

    /**
     * How far before the end of the playlist playback should start, in whole seconds.
     *
     * The whole window minus [START_MARGIN_SECONDS] leaves a couple of segments of margin, so the
     * start position stays inside the window even if the playlist advances while the receiver is
     * still opening the stream.
     */
    private fun startOffsetFor(listed: List<HlsSegment>): Int {
        val windowSeconds = listed.sumOf { it.durationUs } / MICROS_PER_SECOND_LONG
        return (windowSeconds - START_MARGIN_SECONDS).coerceAtLeast(0).toInt()
    }

    private fun formatDuration(durationUs: Long): String =
        String.format(Locale.ROOT, "%.3f", durationUs / MICROS_PER_SECOND)

    companion object {
        const val PLAYLIST_NAME = "index.m3u8"

        /**
         * Twelve seconds of advertised content: enough for a receiver to sit ~8 s behind the live
         * edge and still find the next segment already published.
         */
        const val DEFAULT_PLAYLIST_WINDOW = 6
        const val DEFAULT_RETENTION = 10
        private const val DEFAULT_TARGET_DURATION_SECONDS = 2
        private const val START_MARGIN_SECONDS = 4L
        private const val MICROS_PER_SECOND = 1_000_000.0
        private const val MICROS_PER_SECOND_LONG = 1_000_000L
    }
}
