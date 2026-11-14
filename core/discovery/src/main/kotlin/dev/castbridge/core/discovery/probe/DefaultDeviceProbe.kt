package dev.castbridge.core.discovery.probe

import dev.castbridge.core.discovery.roku.RokuEcpUrls
import dev.castbridge.core.model.CastDevice
import dev.castbridge.core.model.ChromecastDevice
import dev.castbridge.core.model.DeviceProbe
import dev.castbridge.core.model.ProbeResult
import dev.castbridge.core.model.RokuDevice
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Verifies a device is still reachable before any streaming work begins.
 *
 * Roku answers its ECP HTTP endpoint; a Chromecast is validated by opening its Cast TCP port (8009).
 * Neither check sends a cast command or changes device state.
 */
class DefaultDeviceProbe(
    private val httpClient: OkHttpClient = defaultHttpClient(),
) : DeviceProbe {

    override suspend fun probe(device: CastDevice): ProbeResult = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        try {
            when (device) {
                is ChromecastDevice -> probeTcp(device.host, device.port)
                is RokuDevice -> probeRokuEcp(device)
            }

            val elapsed = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
            ProbeResult.Reachable(latencyMillis = elapsed)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            ProbeResult.Unreachable(reason = error.toFriendlyReason())
        }
    }

    private fun probeTcp(host: String, port: Int) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }
    }

    private fun probeRokuEcp(device: RokuDevice) {
        val request = Request.Builder()
            .url(RokuEcpUrls.deviceInfo(device.host, device.ecpPort))
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
        }
    }

    private fun Throwable.toFriendlyReason(): String = when (this) {
        is SocketTimeoutException -> "Timed out after ${CONNECT_TIMEOUT_MS / 1000} seconds"
        is UnknownHostException -> "Device not found on this network"
        is ConnectException -> "Connection refused"
        is IOException -> message ?: "Network error"
        else -> message ?: "Unknown error"
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 2_500

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
