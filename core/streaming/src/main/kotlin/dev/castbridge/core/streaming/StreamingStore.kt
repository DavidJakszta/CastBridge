package dev.castbridge.core.streaming

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide streaming state.
 *
 * The foreground capture service owns the HTTP server and encoder, while the UI may be recreated
 * during a session. This holder keeps them in sync without binding or broadcasts.
 */
object StreamingStore {
    private val mutableState = MutableStateFlow<StreamingState>(StreamingState.Idle)

    val state: StateFlow<StreamingState> = mutableState.asStateFlow()

    /** Used by the foreground capture service and the HLS session. */
    fun update(next: StreamingState) {
        mutableState.value = next
    }

    /** Clears a finished or failed result; an active session is left untouched. */
    fun reset() {
        when (mutableState.value) {
            is StreamingState.Stopped, is StreamingState.Failed ->
                mutableState.value = StreamingState.Idle
            else -> Unit
        }
    }
}
