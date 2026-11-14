package dev.castbridge.core.model

/**
 * A TV or streaming device found on the local network.
 *
 * Chromecast and Roku details stay in their own types, while callers that only need a device list
 * (repository, UI, future connection manager) depend on this common interface.
 */
sealed interface CastDevice {
    /** Stable identity. Built from the Cast UUID or Roku serial when the device exposes one. */
    val id: String

    val name: String

    /** IPv4 or IPv6 address without a scheme. */
    val host: String

    val model: String?

    val protocol: CastProtocol

    /** Wall-clock time of the most recent sighting, in milliseconds. */
    val lastSeenMillis: Long
}

data class ChromecastDevice(
    override val id: String,
    override val name: String,
    override val host: String,
    val port: Int = DEFAULT_CAST_PORT,
    override val model: String? = null,
    val deviceUuid: String? = null,
    override val lastSeenMillis: Long = 0L,
) : CastDevice {
    override val protocol: CastProtocol = CastProtocol.GOOGLE_CAST

    companion object {
        const val DEFAULT_CAST_PORT = 8009
    }
}

data class RokuDevice(
    override val id: String,
    override val name: String,
    override val host: String,
    val ecpPort: Int = DEFAULT_ECP_PORT,
    override val model: String? = null,
    val serialNumber: String? = null,
    val vendorName: String? = null,
    override val lastSeenMillis: Long = 0L,
) : CastDevice {
    override val protocol: CastProtocol = CastProtocol.ROKU

    companion object {
        const val DEFAULT_ECP_PORT = 8060
    }
}
