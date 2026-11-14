package dev.castbridge.core.model

/**
 * One discovery transport, e.g. mDNS for Google Cast or SSDP for Roku.
 *
 * Implementations are cold and side-effect free from the caller's perspective: one [scan] call performs
 * one bounded discovery pass. The repository decides how often to repeat scans and merges the results.
 */
interface DeviceDiscovery {
    val protocol: CastProtocol

    /**
     * Scan for [timeoutMillis] and return every device seen. Implementations should not throw for
     * individual malformed replies; throw [DiscoveryException] only when the transport cannot run.
     */
    suspend fun scan(timeoutMillis: Long): List<CastDevice>
}

class DiscoveryException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** A non-fatal problem with one discovery transport, surfaced in the UI with troubleshooting hints. */
data class DiscoveryIssue(
    val protocol: CastProtocol,
    val message: String,
)
