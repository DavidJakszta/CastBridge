package dev.castbridge.core.capture

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.StateFlow

/** Entry point used by the UI to start and stop screen capture. */
class CaptureController(context: Context) {
    private val appContext = context.applicationContext

    val state: StateFlow<CaptureState> = CaptureStore.state

    fun start(resultCode: Int, data: Intent, config: CaptureConfig) {
        ScreenCaptureService.start(appContext, resultCode, data, config)
    }

    fun stop() {
        ScreenCaptureService.stop(appContext)
    }

    /** Clears a finished or failed result from the UI. */
    fun reset() {
        CaptureStore.reset()
    }
}
