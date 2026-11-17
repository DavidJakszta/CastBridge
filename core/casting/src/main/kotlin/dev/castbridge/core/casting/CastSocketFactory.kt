package dev.castbridge.core.casting

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Opens the Cast v2 control channel.
 *
 * Cast devices listen on port 8009 with a self-signed certificate that is unique per device and
 * cannot be validated against a public CA. The channel is only reachable on the local network and
 * carries no credentials, so the certificate is accepted as-is. This is the same approach used by
 * every local Cast client; the alternative would require a Cast developer registration.
 */
internal fun interface CastSocketFactory {
    fun open(host: String, port: Int, timeoutMillis: Int): Socket
}

internal object TlsCastSocketFactory : CastSocketFactory {
    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val context: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
    }

    override fun open(host: String, port: Int, timeoutMillis: Int): Socket {
        val socket = context.socketFactory.createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            socket.soTimeout = READ_TIMEOUT_MILLIS
            socket.startHandshake()
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw IOException("Could not connect to $host:$port", error)
        }
        return socket
    }

    private const val READ_TIMEOUT_MILLIS = 0
}
