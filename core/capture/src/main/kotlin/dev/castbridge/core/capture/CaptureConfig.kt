package dev.castbridge.core.capture

/**
 * Encoder settings for one screen-capture session.
 *
 * The size is fixed for the lifetime of a session: MediaCodec cannot change the resolution of a
 * surface-backed encoder without a restart, and a fixed size keeps the MP4 container valid.
 */
data class CaptureConfig(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val frameRate: Int,
    val bitrate: Int,
    val keyFrameIntervalSeconds: Int = 2,
) {
    init {
        require(width > 0 && height > 0) { "Capture size must be positive." }
        require(densityDpi > 0) { "Display density must be positive." }
        require(frameRate in MIN_FRAME_RATE..MAX_FRAME_RATE) {
            "Frame rate must be between $MIN_FRAME_RATE and $MAX_FRAME_RATE."
        }
        require(bitrate > 0) { "Bitrate must be positive." }
        require(keyFrameIntervalSeconds in 1..10) { "Key-frame interval must be 1-10 seconds." }
    }

    val pixels: Long get() = width.toLong() * height

    companion object {
        const val MIN_FRAME_RATE = 15
        const val MAX_FRAME_RATE = 60
    }
}
