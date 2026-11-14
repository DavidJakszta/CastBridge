package dev.castbridge.core.discovery.roku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdpResponseParserTest {

    private val rokuReply = """
        HTTP/1.1 200 OK
        CACHE-CONTROL: max-age=1800
        EXT:
        LOCATION: http://192.168.1.42:8060/
        SERVER: Roku/12.5.0 UPnP/1.0 Roku/12.5.0
        ST: roku:ecp
        USN: uuid:roku:ecp:1A2B3C4D5E6F

    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun `parses status and headers case-insensitively`() {
        val response = SsdpResponseParser.parse(rokuReply, "192.168.1.42")

        assertNotNull(response)
        response!!
        assertEquals("http://192.168.1.42:8060/", response.location)
        assertEquals("uuid:roku:ecp:1A2B3C4D5E6F", response.usn)
        assertEquals("Roku/12.5.0 UPnP/1.0 Roku/12.5.0", response.server)
    }

    @Test
    fun `normalises lower-case header names`() {
        val response = SsdpResponseParser.parse(
            "HTTP/1.1 200 OK\r\nlocation: http://10.0.0.9:8060/\r\nst: roku:ecp\r\n\r\n",
            "10.0.0.9",
        )

        assertEquals("http://10.0.0.9:8060/", response?.headers?.get("location"))
    }

    @Test
    fun `rejects non-success replies and blank packets`() {
        assertNull(SsdpResponseParser.parse("HTTP/1.1 404 Not Found\r\nST: roku:ecp\r\n\r\n", "10.0.0.2"))
        assertNull(SsdpResponseParser.parse("", "10.0.0.2"))
        assertNull(SsdpResponseParser.parse("NOTIFY * HTTP/1.1\r\nST: roku:ecp\r\n\r\n", "10.0.0.2"))
    }

    @Test
    fun `recognises Roku replies`() {
        assertTrue(SsdpResponseParser.parse(rokuReply, "192.168.1.42")!!.isRokuResponse())

        val other = SsdpResponseParser.parse(
            "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.10:80/\r\nSERVER: Linux UPnP/1.0\r\nST: upnp:rootdevice\r\nUSN: uuid:abc:device\r\n\r\n",
            "192.168.1.10",
        )!!
        assertFalse(other.isRokuResponse())
    }

    @Test
    fun `builds endpoint from location and extracts serial`() {
        val endpoint = SsdpResponseParser.parse(rokuReply, "192.168.1.42")!!.toRokuEndpoint()

        assertNotNull(endpoint)
        assertEquals("192.168.1.42", endpoint!!.host)
        assertEquals(8060, endpoint.port)
        assertEquals("1A2B3C4D5E6F", endpoint.serialNumber)
    }

    @Test
    fun `uses source address when location is missing`() {
        val endpoint = SsdpResponseParser.parse(
            "HTTP/1.1 200 OK\r\nST: roku:ecp\r\nUSN: uuid:roku:ecp:ABC123\r\n\r\n",
            "192.168.1.77",
        )!!.toRokuEndpoint()

        assertEquals("192.168.1.77", endpoint!!.host)
        assertEquals(8060, endpoint.port)
        assertEquals("ABC123", endpoint.serialNumber)
    }

    @Test
    fun `serial extraction rejects the ecp marker`() {
        assertNull(extractRokuSerial("uuid:roku:ecp"))
        assertEquals("XYZ", extractRokuSerial("uuid:roku:ecp:XYZ"))
    }
}
