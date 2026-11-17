package dev.castbridge.core.capture

/** What the encoder should do with its output. */
enum class CaptureMode {
    /** Write an MP4 to the app-specific Movies directory. */
    RECORD,

    /** Serve the encoded samples as a live HLS stream over the local network. */
    STREAM,
}
