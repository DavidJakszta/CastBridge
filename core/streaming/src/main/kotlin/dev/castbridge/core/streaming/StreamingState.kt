package dev.castbridge.core.streaming

/** Encoder settings copied into the stream so the UI can describe the live session. */
data class StreamingMetadata(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitrate: Int,
)

/** Live counters for the streaming UI. */
data class StreamingStats(
    val elapsedMillis: Long,
    val segments: Int,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitrate: Int,
)

/** What a finished stream produced. */
data class StreamingSummary(
    val durationMillis: Long,
    val segments: Int,
    val bytes: Long,
)

/** Observable state of the live HLS session, shared by the service and the UI. */
sealed interface StreamingState {
    data object Idle : StreamingState

    data object Preparing : StreamingState

    data class Live(
        val url: String,
        val stats: StreamingStats,
    ) : StreamingState

    data object Stopping : StreamingState

    data class Stopped(val summary: StreamingSummary?) : StreamingState

    data class Failed(val message: String) : StreamingState
}
