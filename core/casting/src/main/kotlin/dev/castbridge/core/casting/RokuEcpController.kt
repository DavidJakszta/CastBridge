package dev.castbridge.core.casting

import dev.castbridge.core.model.RokuDevice
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Launches the CastBridge playback channel on a Roku TV.
 *
 * Only the read-only/launch part of ECP is used (`launch` and `query/active-app`). No keypress,
 * power, volume, or input-switch commands are ever sent, so the TV stays in Limited mode.
 */
internal class RokuEcpController(
    private val client: OkHttpClient = defaultClient(),
) {
    /**
     * Starts the developer channel and hands it the stream URL through Roku deep linking.
     *
     * The URL is percent-encoded because it contains `://`, `/`, and a query string.
     */
    fun launchChannel(
        host: String,
        port: Int = RokuDevice.DEFAULT_ECP_PORT,
        url: String,
        timeoutMillis: Long = LAUNCH_TIMEOUT_MILLIS,
    ) {
        val encoded = URLEncoder.encode(url, "UTF-8")
        val endpoint = "http://${authority(host)}:$port/launch/$CHANNEL_ID" +
            "?contentId=$encoded&mediaType=movie"

        val request = Request.Builder()
            .url(endpoint)
            .post(EMPTY_BODY)
            .build()

        val timeoutClient = client.newBuilder()
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        timeoutClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(describeFailure(response.code))
            }
        }
    }

    /** Returns the id of the app currently in the foreground, or null when it cannot be read. */
    fun activeAppId(
        host: String,
        port: Int = RokuDevice.DEFAULT_ECP_PORT,
    ): String? = try {
        val request = Request.Builder()
            .url("http://${authority(host)}:$port/query/active-app")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            Regex("<id>([^<]*)</id>").find(body)?.groupValues?.getOrNull(1)?.trim()
        }
    } catch (_: IOException) {
        null
    }

    private fun describeFailure(code: Int): String = when (code) {
        403 -> "The Roku refused the request. Allow mobile app control in the TV settings."
        404 -> "The CastBridge channel is not installed on this Roku. Install it once in developer mode."
        else -> "The Roku returned HTTP $code."
    }

    private fun authority(host: String): String {
        require(host.isNotBlank()) { "host is blank" }
        val cleaned = host.trim().trim('[', ']')
        require(cleaned.none { it.isWhitespace() || it == '/' }) { "invalid host" }
        return if (cleaned.contains(':')) "[${cleaned.substringBefore('%')}]" else cleaned
    }

    companion object {
        const val CHANNEL_ID = "dev"
        const val STOP_COMMAND = "castbridge-stop"

        private val EMPTY_BODY = ByteArray(0).toRequestBody(null, 0, 0)
        private const val LAUNCH_TIMEOUT_MILLIS = 6_000L

        internal fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
