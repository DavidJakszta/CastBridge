package dev.castbridge.core.discovery.roku

import org.junit.Assert.assertTrue
import org.junit.Test

class SsdpMessagesTest {

    @Test
    fun `roku search uses a valid SSDP discover request`() {
        val message = String(SsdpMessages.rokuSearch(), Charsets.US_ASCII)

        assertTrue(message.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(message.contains("\r\nHOST: 239.255.255.250:1900\r\n"))
        assertTrue(message.contains("\r\nMAN: \"ssdp:discover\"\r\n"))
        assertTrue(message.contains("\r\nMX: 2\r\n"))
        assertTrue(message.contains("\r\nST: roku:ecp\r\n"))
        assertTrue(message.endsWith("\r\n\r\n"))
    }
}
