package dev.castbridge.core.discovery.chromecast

/** TXT record fields advertised by Google Cast devices on `_googlecast._tcp`. */
data class ChromecastTxt(
    val friendlyName: String? = null,
    val model: String? = null,
    val deviceId: String? = null,
    val version: String? = null,
    val status: String? = null,
)

object ChromecastTxtParser {
    private const val SERVICE_SUFFIX = "._googlecast._tcp"

    fun parse(attributes: Map<String, ByteArray>): ChromecastTxt = ChromecastTxt(
        friendlyName = attributes.value("fn"),
        model = attributes.value("md"),
        deviceId = attributes.value("id"),
        version = attributes.value("ve"),
        status = attributes.value("rs"),
    )

    /** Fallback label when a device does not publish `fn`, e.g. "Chromecast-Ultra-1234". */
    fun deriveName(serviceName: String): String {
        val withoutServiceType = serviceName
            .substringBeforeLast(SERVICE_SUFFIX, serviceName)
            .trim()
            .trimEnd('.')

        return withoutServiceType.ifBlank { "Chromecast" }
    }

    private fun Map<String, ByteArray>.value(key: String): String? = this[key]
        ?.toString(Charsets.UTF_8)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
