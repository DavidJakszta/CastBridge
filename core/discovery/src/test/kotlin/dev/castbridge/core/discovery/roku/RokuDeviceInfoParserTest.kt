package dev.castbridge.core.discovery.roku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RokuDeviceInfoParserTest {

    private val deviceInfoXml = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <device-info>
            <udn>uuid:roku:ecp:1A2B3C4D5E6F</udn>
            <serial-number>1A2B3C4D5E6F</serial-number>
            <device-id>1A2B3C4D5E6F</device-id>
            <vendor-name>Roku</vendor-name>
            <model-name>Roku Streaming Stick 4K</model-name>
            <friendly-device-name>Living Room TV</friendly-device-name>
            <supports-ecp-command>true</supports-ecp-command>
        </device-info>
    """.trimIndent()

    @Test
    fun `parses device identity fields`() {
        val info = RokuDeviceInfoParser.parse(deviceInfoXml)

        assertNotNull(info)
        info!!
        assertEquals("Living Room TV", info.friendlyName)
        assertEquals("Roku Streaming Stick 4K", info.modelName)
        assertEquals("1A2B3C4D5E6F", info.serialNumber)
        assertEquals("Roku", info.vendorName)
        assertEquals("1A2B3C4D5E6F", info.deviceId)
        assertEquals("uuid:roku:ecp:1A2B3C4D5E6F", info.udn)
    }

    @Test
    fun `falls back to user device name`() {
        val info = RokuDeviceInfoParser.parse(
            "<device-info><user-device-name>Bedroom</user-device-name><model-name>TCL Roku TV</model-name></device-info>",
        )

        assertEquals("Bedroom", info?.friendlyName)
        assertEquals("TCL Roku TV", info?.modelName)
    }

    @Test
    fun `returns null for unrelated or malformed documents`() {
        assertNull(RokuDeviceInfoParser.parse("<html><body>hello</body></html>"))
        assertNull(RokuDeviceInfoParser.parse("<device-info><friendly-device-name>oops</device-info>"))
        assertNull(RokuDeviceInfoParser.parse(""))
    }

    @Test
    fun `rejects documents with a doctype`() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE device-info [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <device-info><friendly-device-name>&xxe;</friendly-device-name></device-info>
        """.trimIndent()

        assertNull(RokuDeviceInfoParser.parse(xml))
    }

    @Test
    fun `handles extra whitespace around values`() {
        val info = RokuDeviceInfoParser.parse(
            "<device-info><friendly-device-name>  Den TV  </friendly-device-name><serial-number>\n123\n</serial-number></device-info>",
        )

        assertEquals("Den TV", info?.friendlyName)
        assertEquals("123", info?.serialNumber)
    }
}
