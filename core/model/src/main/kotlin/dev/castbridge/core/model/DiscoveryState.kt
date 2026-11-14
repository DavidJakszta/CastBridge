package dev.castbridge.core.model

/** A single snapshot of the unified device list and discovery health. */
data class DiscoveryState(
    val devices: List<CastDevice> = emptyList(),
    val isScanning: Boolean = false,
    val issues: List<DiscoveryIssue> = emptyList(),
    val lastUpdateMillis: Long? = null,
    val completedCycles: Int = 0,
)

/** Lightweight reachability check used by the device details UI. */
interface DeviceProbe {
    suspend fun probe(device: CastDevice): ProbeResult
}

sealed interface ProbeResult {
    data class Reachable(val latencyMillis: Long) : ProbeResult

    data class Unreachable(val reason: String) : ProbeResult
}
