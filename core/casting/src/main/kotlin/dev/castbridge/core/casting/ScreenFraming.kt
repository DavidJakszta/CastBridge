package dev.castbridge.core.casting

/**
 * How a TV maps the portrait phone screen onto a landscape TV.
 *
 * Chromecast letterboxes the stream by itself, so this only changes what the Roku channel is told
 * to do. Both options keep the picture undistorted.
 */
enum class ScreenFraming {
    /** Show the whole phone screen, with black bars at the sides. */
    FIT,

    /** Zoom in until the phone screen covers the TV, cropping the top and bottom. */
    FILL,
}
