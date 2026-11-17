package dev.castbridge.core.streaming

import java.security.SecureRandom

/**
 * Per-session URL token.
 *
 * The HTTP server only answers requests that carry this token. It does not stop another phone from
 * launching a Cast app on the TV, but it does stop anyone who finds the IP and port from watching
 * the mirrored screen.
 */
object TokenGenerator {
    private const val BYTE_COUNT = 16
    private val HEX = "0123456789abcdef".toCharArray()

    fun newToken(): String {
        val random = ByteArray(BYTE_COUNT)
        SecureRandom().nextBytes(random)
        val output = CharArray(BYTE_COUNT * 2)
        random.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            output[index * 2] = HEX[value ushr 4]
            output[index * 2 + 1] = HEX[value and 0x0F]
        }
        return String(output)
    }
}
