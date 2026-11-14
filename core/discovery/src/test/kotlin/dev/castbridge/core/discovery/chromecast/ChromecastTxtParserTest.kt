package dev.castbridge.core.discovery.chromecast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChromecastTxtParserTest {

    @Test
    fun `extracts advertised fields`() {
        val txt = ChromecastTxtParser.parse(
            mapOf(
                "fn" to "Living Room".toByteArray(),
                "md" to "Chromecast Ultra".toByteArray(),
                "id" to "1234567890abcdef".toByteArray(),
                "ve" to "05".toByteArray(),
                "rs" to "idle".toByteArray(),
            ),
        )

        assertEquals("Living Room", txt.friendlyName)
        assertEquals("Chromecast Ultra", txt.model)
        assertEquals("1234567890abcdef", txt.deviceId)
        assertEquals("05", txt.version)
        assertEquals("idle", txt.status)
    }

    @Test
    fun `missing fields are null`() {
        val txt = ChromecastTxtParser.parse(emptyMap())

        assertNull(txt.friendlyName)
        assertNull(txt.model)
        assertNull(txt.deviceId)
    }

    @Test
    fun `blank values are ignored`() {
        val txt = ChromecastTxtParser.parse(mapOf("fn" to "   ".toByteArray()))

        assertNull(txt.friendlyName)
    }

    @Test
    fun `derives a readable name from the service name`() {
        assertEquals(
            "Chromecast-Ultra-1234",
            ChromecastTxtParser.deriveName("Chromecast-Ultra-1234._googlecast._tcp.local."),
        )
        assertEquals(
            "Family Room",
            ChromecastTxtParser.deriveName("Family Room._googlecast._tcp.local."),
        )
        assertEquals("Chromecast", ChromecastTxtParser.deriveName(""))
    }
}
