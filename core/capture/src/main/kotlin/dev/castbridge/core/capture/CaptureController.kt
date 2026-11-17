package dev.castbridge.core.capture

import android.content.Context
import android.content.Intent
import dev.castbridge.core.streaming.StreamingState
import dev.castbridge.core.streaming.StreamingStore
import kotlinx.coroutines.flow.StateFlow

/** Entry point used by the UI to start and stop screen capture or live streaming. */
class CaptureController(context: Context) {
    private val appContext = context.applicationContext

    val state: StateFlow<CaptureState> = CaptureStore.state

    val streamingState: StateFlow<StreamingState> = StreamingStore.state

    fun start(
        resultCode: Int,
        data: Intent,
        config: CaptureConfig,
        mode: CaptureMode = CaptureMode.RECORD,
    ) {
        ScreenCaptureService.start(appContext, resultCode, data, config, mode)
    }

    fun stop() {
        ScreenCaptureService.stop(appContext)
    }

    /** Clears a finished or failed recording result from the UI. */
    fun reset() {
        CaptureStore.reset()
    }

    /** Clears a finished or failed streaming result from the UI. */
    fun resetStreaming() {
        StreamingStore.reset()
    }
}
