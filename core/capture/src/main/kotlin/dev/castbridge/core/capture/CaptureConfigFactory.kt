package dev.castbridge.core.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics

/** Builds encoder settings from the current display. */
object CaptureConfigFactory {
    fun forDisplay(
        metrics: DisplayMetrics,
        refreshRate: Float = 60f,
    ): CaptureConfig = forDisplay(
        widthPixels = metrics.widthPixels,
        heightPixels = metrics.heightPixels,
        densityDpi = metrics.densityDpi,
        refreshRate = refreshRate,
    )

    fun forDisplay(
        widthPixels: Int,
        heightPixels: Int,
        densityDpi: Int,
        refreshRate: Float = 60f,
    ): CaptureConfig {
        val size = CaptureSizing.scaledSize(widthPixels, heightPixels)
        val frameRate = CaptureSizing.frameRateFor(refreshRate)
        return CaptureConfig(
            width = size.width,
            height = size.height,
            densityDpi = densityDpi,
            frameRate = frameRate,
            bitrate = CaptureSizing.bitrateFor(size.width, size.height, frameRate),
        )
    }
}

/**
 * Creates the system consent dialog.
 *
 * On Android 14+ the user can choose between the entire screen and a single app window, which is
 * the capture mode Chrome and other casting apps use. Older releases always capture the whole
 * screen.
 */
object ScreenCaptureRequest {
    fun intent(context: Context): Intent {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
            ?: throw IllegalStateException("Screen capture is not available on this device.")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else {
            manager.createScreenCaptureIntent()
        }
    }
}
