package dev.castbridge.core.discovery.roku

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dev.castbridge.core.discovery.mdns.ServiceResolutionQueue
import dev.castbridge.core.discovery.mdns.preferredHostAddress
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Best-effort mDNS fallback for Roku TVs that do not answer SSDP.
 *
 * Roku TVs with AirPlay enabled advertise `_display._tcp` and/or `_airplay._tcp`. These services are
 * only used to obtain candidate IP addresses; every candidate is confirmed through the Roku ECP
 * `/query/device-info` endpoint before it is shown to the user, so other AirPlay TVs are not
 * mistaken for Rokus.
 */
internal class RokuMdnsDiscovery(context: Context) {
    private val nsdManager: NsdManager? =
        context.applicationContext.getSystemService(NsdManager::class.java)

    suspend fun search(timeoutMillis: Long): List<String> = withContext(Dispatchers.IO) {
        val manager = nsdManager ?: return@withContext emptyList()
        val hosts = ConcurrentHashMap.newKeySet<String>()
        val resolver = ServiceResolutionQueue(manager) { serviceInfo ->
            serviceInfo.preferredHostAddress()?.let(hosts::add)
        }

        val registrations = SERVICE_TYPES.mapNotNull { serviceType ->
            val listener = createListener(resolver)
            try {
                manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                serviceType to listener
            } catch (e: Exception) {
                Log.d(TAG, "Could not start mDNS discovery for $serviceType: ${e.message}")
                null
            }
        }

        if (registrations.isEmpty()) {
            resolver.stop()
            return@withContext emptyList()
        }

        try {
            delay(timeoutMillis)
        } finally {
            registrations.forEach { (_, listener) ->
                runCatching { manager.stopServiceDiscovery(listener) }
            }
            resolver.stop()
        }

        Log.d(TAG, "mDNS fallback found ${hosts.size} candidate host(s)")
        hosts.toList()
    }

    private fun createListener(resolver: ServiceResolutionQueue) = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.d(TAG, "mDNS fallback discovery started for $serviceType")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            val attributes = serviceInfo.attributes.mapValues { (_, value) ->
                String(value, Charsets.UTF_8)
            }
            if (RokuMdnsMatcher.shouldResolve(
                    serviceType = serviceInfo.serviceType.orEmpty(),
                    serviceName = serviceInfo.serviceName.orEmpty(),
                    attributes = attributes,
                )
            ) {
                resolver.enqueue(serviceInfo)
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "mDNS fallback discovery stopped for $serviceType")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.d(TAG, "mDNS fallback could not start for $serviceType (error $errorCode)")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.d(TAG, "mDNS fallback could not stop for $serviceType (error $errorCode)")
        }
    }

    private companion object {
        const val TAG = "RokuMdns"

        /** Roku-specific display service first, then AirPlay as a secondary fallback. */
        val SERVICE_TYPES = listOf("_display._tcp.", "_airplay._tcp.")
    }
}

/**
 * Decides whether an advertised service is worth resolving.
 *
 * `_display._tcp` is Roku-specific on consumer networks. AirPlay is shared with Apple TVs, Samsung
 * and LG TVs, so those advertisements are only resolved when their name or TXT record mentions Roku.
 * Both remain subject to ECP verification afterwards.
 */
internal object RokuMdnsMatcher {
    fun shouldResolve(
        serviceType: String,
        serviceName: String,
        attributes: Map<String, String>,
    ): Boolean {
        if (serviceType.startsWith("_display", ignoreCase = true)) return true

        return sequenceOf(serviceName)
            .plus(attributes.values.asSequence())
            .any { it.contains("roku", ignoreCase = true) }
    }
}
