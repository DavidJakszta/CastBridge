package dev.castbridge.core.casting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {
    private val url = "http://192.168.0.23:40413/s/0123456789abcdef0123456789abcdef/index.m3u8"

    @Test
    fun `derives a six digit code`() {
        val code = PairingCode.fromUrl(url)

        assertEquals(6, code?.length)
        assertTrue(code!!.all { it.isDigit() })
    }

    @Test
    fun `is stable for the same token`() {
        assertEquals(PairingCode.fromUrl(url), PairingCode.fromUrl(url))
    }

    @Test
    fun `differs for different tokens`() {
        val other = "http://192.168.0.23:40413/s/fedcba9876543210fedcba9876543210/index.m3u8"

        assertTrue(PairingCode.fromUrl(url) != PairingCode.fromUrl(other))
    }

    @Test
    fun `returns null when the URL has no token`() {
        assertNull(PairingCode.fromUrl("http://192.168.0.23:40413/index.m3u8"))
    }

    @Test
    fun `appends the code as a query parameter`() {
        val withCode = PairingCode.withCode(url, "123456")

        assertEquals("$url?pairing=123456", withCode)
        assertEquals(PairingCode.fromUrl(url), PairingCode.fromUrl(withCode))
    }

    @Test
    fun `uses an ampersand when a query already exists`() {
        assertEquals(
            "$url?x=1&pairing=123456",
            PairingCode.withCode("$url?x=1", "123456"),
        )
    }
}
