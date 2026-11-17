package dev.castbridge.core.streaming

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** Finds the address the TV should use to reach the phone. */
object LocalNetwork {
    fun wifiIpv4(): Inet4Address? {
        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        }.getOrDefault(emptyList())

        val addresses = interfaces
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .sortedByDescending { it.name.orEmpty().startsWith("wlan", ignoreCase = true) }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }

        return addresses.firstOrNull { it.isSiteLocalAddress } ?: addresses.firstOrNull()
    }
}
