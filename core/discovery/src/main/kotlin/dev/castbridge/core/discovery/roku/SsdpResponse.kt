package dev.castbridge.core.discovery.roku

import java.net.URI
import java.util.Locale
import dev.castbridge.core.model.RokuDevice

/** One SSDP response, with header names normalised to lower case. */
data class SsdpResponse(
    val headers: Map<String, String>,
    val sourceAddress: String,
) {
    val location: String? get() = headers["location"]
    val usn: String? get() = headers["usn"]
    val server: String? get() = headers["server"]

    /**
     * Roku devices answer the `roku:ecp` search target. The additional checks protect against devices
     * that answer `ssdp:all` on networks where that reply also reaches this socket.
     */
    fun isRokuResponse(): Boolean {
        val usn = usn.orEmpty()
        val server = server.orEmpty()
        val searchTarget = headers["st"].orEmpty()
        val location = location.orEmpty()

        return usn.contains("roku", ignoreCase = true) ||
            server.contains("roku", ignoreCase = true) ||
            searchTarget.equals("roku:ecp", ignoreCase = true) ||
            location.contains(":8060")
    }
}

/** Parses the text of an SSDP reply. Returns null for malformed or non-200 packets. */
object SsdpResponseParser {
    fun parse(packet: String, sourceAddress: String): SsdpResponse? {
        if (packet.isBlank()) return null

        val lines = packet.replace("\r\n", "\n").trim().split('\n')
        val statusLine = lines.firstOrNull()?.trim().orEmpty()
        if (!statusLine.startsWith("HTTP/1.1 200") && !statusLine.startsWith("HTTP/1.0 200")) {
            return null
        }

        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue

            val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            if (name.isNotEmpty() && value.isNotEmpty()) {
                headers[name] = value
            }
        }

        if (headers.isEmpty()) return null
        return SsdpResponse(headers = headers, sourceAddress = sourceAddress)
    }
}

internal data class RokuEndpoint(
    val host: String,
    val port: Int,
    val serialNumber: String?,
    val usn: String?,
)

internal fun SsdpResponse.toRokuEndpoint(): RokuEndpoint? {
    val endpoint = parseLocation(location ?: return fallbackEndpoint())
        ?: return fallbackEndpoint()

    return RokuEndpoint(
        host = endpoint.host,
        port = endpoint.port,
        serialNumber = usn?.let(::extractRokuSerial),
        usn = usn,
    )
}

private fun SsdpResponse.fallbackEndpoint(): RokuEndpoint? {
    val host = sourceAddress.trim()
    if (host.isEmpty()) return null
    return RokuEndpoint(
        host = host,
        port = RokuDevice.DEFAULT_ECP_PORT,
        serialNumber = usn?.let(::extractRokuSerial),
        usn = usn,
    )
}

private data class IpEndpoint(val host: String, val port: Int)

private fun parseLocation(location: String?): IpEndpoint? {
    if (location.isNullOrBlank()) return null
    return runCatching {
        val uri = URI(location)
        val host = uri.host?.trim()?.trim('[', ']')?.takeIf { it.isNotEmpty() } ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: RokuDevice.DEFAULT_ECP_PORT
        IpEndpoint(host = host, port = port)
    }.getOrNull()
}

/** `uuid:roku:ecp:1A2B3C4D5E6F` becomes `1A2B3C4D5E6F`. */
internal fun extractRokuSerial(usn: String): String? {
    val candidate = usn.substringAfterLast(':').trim()
    return candidate.takeIf {
        it.isNotEmpty() &&
            !it.equals("ecp", ignoreCase = true) &&
            !it.startsWith("uuid", ignoreCase = true)
    }
}
