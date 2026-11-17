package dev.castbridge.core.casting

import java.security.MessageDigest

/**
 * Derives the six-digit code shown both in the app and on the TV.
 *
 * The code is not a secret and is not used for authentication: it only lets the user confirm that
 * the TV is playing *this* phone's stream. Playback authority comes from the unguessable per-session
 * stream token, so knowing the code does not let another device take over.
 */
object PairingCode {
    fun fromUrl(url: String): String? {
        val token = TOKEN_PATTERN.find(url)?.groupValues?.getOrNull(1) ?: return null
        return fromToken(token)
    }

    fun fromToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        var value = 0L
        for (index in 0 until 6) {
            value = (value shl 8) or (digest[index].toLong() and 0xFF)
        }
        return (value % CODE_MODULUS).toString().padStart(6, '0')
    }

    /** Adds the code as a query parameter. The HLS server ignores query strings when routing. */
    fun withCode(url: String, code: String): String {
        val separator = if (url.contains('?')) '&' else '?'
        return "$url$separator$CODE_PARAMETER=$code"
    }

    const val CODE_PARAMETER = "pairing"

    private const val CODE_MODULUS = 1_000_000L
    private val TOKEN_PATTERN = Regex("/s/([0-9a-fA-F]{32})/")
}
