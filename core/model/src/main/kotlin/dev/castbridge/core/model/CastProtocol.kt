package dev.castbridge.core.model

/**
 * Protocols supported by the unified device layer.
 *
 * New TV platforms are added by implementing [DeviceDiscovery] and adding a protocol value here. The
 * rest of the app only depends on [CastDevice] and does not care how discovery happens.
 */
enum class CastProtocol(
    val displayName: String,
    val shortName: String,
) {
    GOOGLE_CAST(displayName = "Google Cast", shortName = "Chromecast"),
    ROKU(displayName = "Roku", shortName = "Roku"),
}
