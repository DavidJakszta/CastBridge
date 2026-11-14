package dev.castbridge.core.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide capture state.
 *
 * The foreground service owns the pipeline, but the UI lives in a different component and may be
 * recreated while recording continues. A small observable holder keeps both in sync without
 * binding or broadcasts.
 */
object CaptureStore {
    private val mutableState = MutableStateFlow<CaptureState>(CaptureState.Idle)

    val state: StateFlow<CaptureState> = mutableState.asStateFlow()

    internal fun update(next: CaptureState) {
        mutableState.value = next
    }

    /** Clears a finished or failed result; a running session is left untouched. */
    fun reset() {
        when (mutableState.value) {
            is CaptureState.Completed, is CaptureState.Failed -> update(CaptureState.Idle)
            else -> Unit
        }
    }
}
