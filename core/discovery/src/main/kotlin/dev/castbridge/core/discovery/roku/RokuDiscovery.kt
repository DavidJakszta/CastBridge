package dev.castbridge.core.discovery.roku

import android.content.Context
import android.util.Log
import dev.castbridge.core.model.CastDevice
import dev.castbridge.core.model.CastProtocol
import dev.castbridge.core.model.DeviceDiscovery
import dev.castbridge.core.model.RokuDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * Discovers Roku devices with SSDP and enriches them through the Roku ECP `/query/device-info` call.
 * Device identity prefers the serial number so the same TV keeps one entry across DHCP changes.
 *
 * If SSDP yields nothing (or fails), the scan falls back to mDNS via the AirPlay/display services.
 * Fallback candidates must answer ECP before they are accepted, which keeps other AirPlay TVs out.
 */
class RokuDiscovery(
    context: Context,
    private val ecpClient: RokuEcpClient = RokuEcpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) : DeviceDiscovery {
    private val ssdpSearch = SsdpSearch(context.applicationContext)
    private val mdnsDiscovery = RokuMdnsDiscovery(context.applicationContext)

    override val protocol: CastProtocol = CastProtocol.ROKU

    override suspend fun scan(timeoutMillis: Long): List<CastDevice> = coroutineScope {
        var ssdpFailure: Throwable? = null
        val ssdpEndpoints = try {
            ssdpSearch.searchRoku(timeoutMillis)
                .mapNotNull { it.toRokuEndpoint() }
                .distinctBy { it.host }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.d(TAG, "Roku SSDP search failed, trying mDNS fallback: ${error.message}")
            ssdpFailure = error
            emptyList()
        }

        val endpoints: List<RokuEndpoint>
        val requireEcpVerification: Boolean
        if (ssdpEndpoints.isNotEmpty()) {
            endpoints = ssdpEndpoints
            requireEcpVerification = false
        } else {
            // Give Chromecast's in-flight resolve callbacks time to finish; NsdManager allows only
            // one active resolve at a time.
            delay(MDNS_SETTLE_MS)
            val hosts = try {
                mdnsDiscovery.search(timeoutMillis.coerceAtMost(MDNS_TIMEOUT_MS))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.d(TAG, "Roku mDNS fallback failed: ${error.message}")
                emptyList()
            }

            if (hosts.isEmpty() && ssdpFailure != null) throw ssdpFailure!!

            endpoints = hosts.distinct().map { host ->
                RokuEndpoint(
                    host = host,
                    port = RokuDevice.DEFAULT_ECP_PORT,
                    serialNumber = null,
                    usn = null,
                )
            }
            requireEcpVerification = true
        }

        endpoints
            .map { endpoint -> async(Dispatchers.IO) { endpoint.toDevice(requireEcpVerification) } }
            .awaitAll()
            .filterNotNull()
    }

    private suspend fun RokuEndpoint.toDevice(requireEcpVerification: Boolean): RokuDevice? {
        val info = ecpClient.fetchDeviceInfo(host, port)
        if (requireEcpVerification && info == null) {
            Log.d(TAG, "Ignoring mDNS candidate $host: no Roku ECP response")
            return null
        }

        val serial = info?.serialNumber ?: serialNumber
        val id = serial?.takeIf { it.isNotBlank() }?.let { "roku:$it" } ?: "roku:$host"

        return RokuDevice(
            id = id,
            name = info?.friendlyName ?: "Roku ($host)",
            host = host,
            ecpPort = port,
            model = info?.modelName,
            serialNumber = serial,
            vendorName = info?.vendorName,
            lastSeenMillis = clock(),
        )
    }

    private companion object {
        const val TAG = "RokuDiscovery"
        const val MDNS_SETTLE_MS = 750L
        const val MDNS_TIMEOUT_MS = 6_000L
    }
}
