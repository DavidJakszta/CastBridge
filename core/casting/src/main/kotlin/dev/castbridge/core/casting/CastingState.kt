package dev.castbridge.core.casting

import dev.castbridge.core.model.CastProtocol

/** What the phone is currently doing with a TV. */
sealed interface CastingState {
    data object Idle : CastingState

    data class Connecting(
        val deviceId: String,
        val deviceName: String,
        val protocol: CastProtocol,
    ) : CastingState

    data class Playing(
        val deviceId: String,
        val deviceName: String,
        val protocol: CastProtocol,
        val pairingCode: String,
    ) : CastingState

    data class Stopped(val deviceName: String) : CastingState

    data class Failed(
        val deviceName: String?,
        val message: String,
    ) : CastingState
}

/** True while a connection or playback is in progress. */
val CastingState.isBusy: Boolean
    get() = this is CastingState.Connecting || this is CastingState.Playing

/** The device name involved in the current state, if any. */
val CastingState.deviceNameOrNull: String?
    get() = when (this) {
        is CastingState.Connecting -> deviceName
        is CastingState.Playing -> deviceName
        is CastingState.Stopped -> deviceName
        is CastingState.Failed -> deviceName
        CastingState.Idle -> null
    }
