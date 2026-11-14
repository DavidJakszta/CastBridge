package dev.castbridge.core.discovery.roku

import android.util.Log
import dev.castbridge.core.model.RokuDevice
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Minimal Roku External Control Protocol (ECP) client used for device identity and reachability. */
class RokuEcpClient(
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun fetchDeviceInfo(
        host: String,
        port: Int = RokuDevice.DEFAULT_ECP_PORT,
    ): RokuDeviceInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RokuEcpUrls.deviceInfo(host, port))
                .header("Accept", "text/xml")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                if (body.contentLength() > MAX_RESPONSE_CHARS) return@withContext null

                val text = body.string()
                if (text.length > MAX_RESPONSE_CHARS) return@withContext null

                val info = RokuDeviceInfoParser.parse(text)
                if (info == null) {
                    Log.d(TAG, "ECP device-info XML could not be parsed for $host:$port")
                }
                info
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.d(TAG, "ECP device-info failed for $host:${port}: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "ECP device-info skipped invalid endpoint '$host': ${e.message}")
            null
        } catch (e: Exception) {
            // Enrichment is optional: the SSDP endpoint already identified a usable device.
            Log.w(TAG, "ECP device-info failed unexpectedly for $host:$port", e)
            null
        }
    }

    companion object {
        private const val TAG = "RokuEcpClient"
        private const val MAX_RESPONSE_CHARS = 256 * 1024

        internal fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            // The Roku closes idle keep-alive connections; without retries a reused socket fails
            // with "unexpected end of stream". The request is a safe GET, so retrying is fine.
            .retryOnConnectionFailure(true)
            .build()
    }
}

internal object RokuEcpUrls {
    fun deviceInfo(host: String, port: Int): String = query(host, port, "device-info")

    fun query(host: String, port: Int, query: String): String {
        require(host.isNotBlank()) { "host is blank" }
        val cleaned = host.trim().trim('[', ']')
        require(cleaned.none { it.isWhitespace() || it == '/' }) { "invalid host" }

        val authority = if (cleaned.contains(':')) {
            "[${cleaned.substringBefore('%')}]"
        } else {
            cleaned
        }
        return "http://$authority:$port/query/$query"
    }
}
