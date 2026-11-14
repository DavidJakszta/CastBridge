package dev.castbridge.core.capture

/** Even pixel dimensions. MediaCodec requires chroma-aligned sizes for H.264. */
data class CaptureSize(val width: Int, val height: Int)

/**
 * Pure size and bitrate maths for screen capture.
 *
 * Mirroring at the full native resolution of a modern phone (for example 1080 x 2400) wastes
 * bandwidth and encoder time. The long edge is capped, the aspect ratio is preserved, and both
 * dimensions are rounded down to even numbers so the encoder never rejects the format.
 */
object CaptureSizing {
    const val MAX_LONG_EDGE = 1920
    const val MIN_LONG_EDGE = 480

    /** Rough bits per pixel per frame that keeps text legible while remaining streamable. */
    private const val BITS_PER_PIXEL_PER_FRAME = 0.10

    private const val MIN_BITRATE = 1_500_000
    private const val MAX_BITRATE = 12_000_000

    fun scaledSize(
        width: Int,
        height: Int,
        maxLongEdge: Int = MAX_LONG_EDGE,
    ): CaptureSize {
        require(width > 0 && height > 0) { "Display size must be positive." }
        require(maxLongEdge >= MIN_LONG_EDGE) { "Long edge must be at least $MIN_LONG_EDGE px." }

        val longEdge = maxOf(width, height)
        val scale = if (longEdge > maxLongEdge) {
            maxLongEdge.toDouble() / longEdge
        } else {
            1.0
        }

        val scaledWidth = evenAtLeastTwo((width * scale).toInt())
        val scaledHeight = evenAtLeastTwo((height * scale).toInt())

        return CaptureSize(scaledWidth, scaledHeight)
    }

    fun bitrateFor(width: Int, height: Int, frameRate: Int): Int {
        val raw = width.toLong() * height * frameRate * BITS_PER_PIXEL_PER_FRAME
        val clamped = raw.toLong().coerceIn(MIN_BITRATE.toLong(), MAX_BITRATE.toLong())
        // Round to 100 kbps so logs and the UI stay readable.
        return ((clamped + 50_000) / 100_000 * 100_000).toInt()
    }

    /** 30 fps is plenty for mirroring; 24 fps avoids stutter on low-refresh panels. */
    fun frameRateFor(displayRefreshRate: Float): Int = if (displayRefreshRate >= 30f) 30 else 24

    private fun evenAtLeastTwo(value: Int): Int = ((value.coerceAtLeast(2)) / 2) * 2
}
