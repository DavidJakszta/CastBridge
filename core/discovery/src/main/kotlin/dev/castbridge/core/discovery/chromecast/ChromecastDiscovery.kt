package dev.castbridge.core.discovery.chromecast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dev.castbridge.core.discovery.mdns.ServiceResolutionQueue
import dev.castbridge.core.discovery.mdns.preferredHostAddress
import dev.castbridge.core.model.CastDevice
import dev.castbridge.core.model.CastProtocol
import dev.castbridge.core.model.ChromecastDevice
import dev.castbridge.core.model.DeviceDiscovery
import dev.castbridge.core.model.DiscoveryException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Google Cast discovery over mDNS using Android's [NsdManager].
 *
 * Discovery runs for a bounded window per scan. Each advertised service is resolved to obtain the
 * address and TXT record; Android only allows one resolve operation at a time, so resolutions are
 * processed by a small serial queue.
 */
class ChromecastDiscovery(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : DeviceDiscovery {
    private val nsdManager: NsdManager? =
        context.applicationContext.getSystemService(NsdManager::class.java)

    override val protocol: CastProtocol = CastProtocol.GOOGLE_CAST

    override suspend fun scan(timeoutMillis: Long): List<CastDevice> {
        val manager = nsdManager
            ?: throw DiscoveryException("mDNS discovery is not available on this device.")

        return coroutineScope {
            val devices = ConcurrentHashMap<String, CastDevice>()
            val startFailure = CompletableDeferred<Throwable>()

            val resolver = ServiceResolutionQueue(manager) { serviceInfo ->
                serviceInfo.toChromecastDevice(clock)?.let { device ->
                    devices[device.id] = device
                }
            }

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d(TAG, "Chromecast mDNS discovery started")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    val type = serviceInfo.serviceType.orEmpty()
                    if (type.isEmpty() || type.startsWith(SERVICE_TYPE_PREFIX, ignoreCase = true)) {
                        resolver.enqueue(serviceInfo)
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "Chromecast service lost: ${serviceInfo.serviceName}")
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "Chromecast mDNS discovery stopped")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    startFailure.complete(
                        DiscoveryException("Android could not start mDNS discovery (error $errorCode)."),
                    )
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.d(TAG, "Could not stop mDNS discovery (error $errorCode)")
                }
            }

            try {
                manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                resolver.stop()
                throw DiscoveryException("Android could not start mDNS discovery.", e)
            }

            try {
                val failure = withTimeoutOrNull(timeoutMillis) { startFailure.await() }
                if (failure != null) throw failure
            } finally {
                runCatching { manager.stopServiceDiscovery(listener) }
                resolver.stop()
            }

            devices.values.toList()
        }
    }

    private fun NsdServiceInfo.toChromecastDevice(now: () -> Long): ChromecastDevice? {
        val address = preferredHostAddress() ?: return null
        val txt = ChromecastTxtParser.parse(attributes)
        val stableId = txt.deviceId?.takeIf { it.isNotBlank() }
            ?: serviceName.takeIf { it.isNotBlank() }
            ?: address

        return ChromecastDevice(
            id = "cast:$stableId",
            name = txt.friendlyName ?: ChromecastTxtParser.deriveName(serviceName),
            host = address,
            port = port.takeIf { it in 1..65535 } ?: ChromecastDevice.DEFAULT_CAST_PORT,
            model = txt.model,
            deviceUuid = txt.deviceId,
            lastSeenMillis = now(),
        )
    }

    private companion object {
        const val TAG = "ChromecastDiscovery"
        const val SERVICE_TYPE = "_googlecast._tcp."
        const val SERVICE_TYPE_PREFIX = "_googlecast._tcp"
    }
}
