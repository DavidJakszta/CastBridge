package dev.castbridge.core.capture

/** Live counters emitted while the encoder runs. */
data class CaptureStats(
    val elapsedMillis: Long,
    val frames: Long,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val frameRate: Int,
)

/** Metadata for a finished recording, read back from the MP4 where possible. */
data class RecordingResult(
    val filePath: String,
    val durationMillis: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val frameCount: Long,
)

/** Observable state of the capture pipeline, shared by the service and the UI. */
sealed interface CaptureState {
    data object Idle : CaptureState

    data object Preparing : CaptureState

    data class Running(val stats: CaptureStats) : CaptureState

    data object Finalizing : CaptureState

    data class Completed(val result: RecordingResult) : CaptureState

    data class Failed(val message: String) : CaptureState
}
