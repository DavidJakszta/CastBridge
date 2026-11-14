package dev.castbridge.core.discovery.roku

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import dev.castbridge.core.model.DiscoveryException
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends an SSDP `M-SEARCH` for `roku:ecp` and collects unicast replies for a bounded amount of time.
 *
 * A [WifiManager.MulticastLock] is held for the duration of the scan. Android filters multicast traffic
 * for apps that do not hold the lock, which is the most common reason Roku devices appear invisible.
 */
internal class SsdpSearch(
    context: Context,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val appContext = context.applicationContext

    suspend fun searchRoku(timeoutMillis: Long): List<SsdpResponse> = withContext(Dispatchers.IO) {
        val multicastLock = acquireMulticastLock(appContext)
        try {
            performSearch(timeoutMillis)
        } catch (e: DiscoveryException) {
            throw e
        } catch (e: IOException) {
            throw DiscoveryException(
                "Roku discovery could not start. Check that Wi-Fi is enabled and allows multicast.",
                e,
            )
        } finally {
            multicastLock?.let { lock ->
                runCatching { if (lock.isHeld) lock.release() }
            }
        }
    }

    private fun performSearch(timeoutMillis: Long): List<SsdpResponse> {
        MulticastSocket(0).use { socket ->
            socket.timeToLive = MULTICAST_TTL
            socket.soTimeout = RECEIVE_POLL_TIMEOUT_MS
            findMulticastInterface()?.let { networkInterface ->
                runCatching { socket.networkInterface = networkInterface }
                    .onFailure { Log.d(TAG, "Could not bind SSDP socket to ${networkInterface.name}") }
            }

            val group = InetAddress.getByName(SSDP_GROUP)
            val payload = SsdpMessages.rokuSearch()
            val responses = linkedMapOf<String, SsdpResponse>()
            val deadline = elapsedMillis() + timeoutMillis

            var sendAttempts = 0
            var nextSendAt = elapsedMillis()
            var lastSendError: IOException? = null
            val receiveBuffer = ByteArray(MAX_PACKET_BYTES)

            while (elapsedMillis() < deadline) {
                val now = elapsedMillis()
                if (sendAttempts < SEARCH_ATTEMPTS && now >= nextSendAt) {
                    try {
                        socket.send(DatagramPacket(payload, payload.size, group, SSDP_PORT))
                        lastSendError = null
                    } catch (e: IOException) {
                        lastSendError = e
                        Log.d(TAG, "SSDP M-SEARCH send failed: ${e.message}")
                    }
                    sendAttempts++
                    nextSendAt = now + RESEND_INTERVAL_MS
                }

                val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (e: IOException) {
                    throw DiscoveryException("Roku discovery failed while receiving replies.", e)
                }

                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val source = packet.address?.hostAddress.orEmpty()
                val response = SsdpResponseParser.parse(text, source) ?: continue
                if (!response.isRokuResponse()) continue

                val key = response.usn ?: response.location ?: "$source:${packet.port}"
                responses.putIfAbsent(key, response)
            }

            Log.d(TAG, "SSDP search finished with ${responses.size} Roku response(s)")

            if (responses.isEmpty() && lastSendError != null) {
                throw DiscoveryException(
                    "Roku multicast search could not be sent. Check the Wi-Fi network.",
                    lastSendError,
                )
            }
            return responses.values.toList()
        }
    }

    private fun findMulticastInterface(): NetworkInterface? = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { networkInterface ->
                runCatching {
                    networkInterface.isUp &&
                        networkInterface.supportsMulticast() &&
                        !networkInterface.isLoopback
                }.getOrDefault(false)
            }
            .filter { networkInterface ->
                Collections.list(networkInterface.inetAddresses).any {
                    it is Inet4Address && it.isSiteLocalAddress
                }
            }
            // Prefer the Wi-Fi interface when cellular, VPN or Ethernet interfaces are also up.
            .sortedByDescending { it.name.startsWith("wlan", ignoreCase = true) }
            .firstOrNull()
    }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? = try {
        context.getSystemService(WifiManager::class.java)
            ?.createMulticastLock(MULTICAST_LOCK_TAG)
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
    } catch (e: Exception) {
        Log.w(TAG, "Multicast lock unavailable: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "SsdpSearch"
        const val SSDP_GROUP = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val MULTICAST_TTL = 4
        const val MAX_PACKET_BYTES = 8 * 1024
        const val RECEIVE_POLL_TIMEOUT_MS = 250
        const val RESEND_INTERVAL_MS = 750L
        const val SEARCH_ATTEMPTS = 3
        const val MULTICAST_LOCK_TAG = "castbridge:ssdp"
    }
}

internal object SsdpMessages {
    fun rokuSearch(): ByteArray = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: 239.255.255.250:1900\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: 2\r\n")
        append("ST: roku:ecp\r\n")
        append("USER-AGENT: Android/1.0 UPnP/1.1 CastBridge/0.1\r\n")
        append("\r\n")
    }.toByteArray(Charsets.US_ASCII)
}
