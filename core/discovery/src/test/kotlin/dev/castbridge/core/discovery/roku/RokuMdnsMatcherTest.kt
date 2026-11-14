package dev.castbridge.core.discovery.roku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RokuMdnsMatcherTest {

    @Test
    fun `display services are always resolved`() {
        assertTrue(
            RokuMdnsMatcher.shouldResolve(
                serviceType = "_display._tcp.",
                serviceName = "50\" TCL Roku TV",
                attributes = emptyMap(),
            ),
        )
    }

    @Test
    fun `airplay services are resolved when the txt record names Roku`() {
        assertTrue(
            RokuMdnsMatcher.shouldResolve(
                serviceType = "_airplay._tcp.",
                serviceName = "A8169DB152DF@50\" TCL Roku TV",
                attributes = mapOf("manufacturer" to "Roku", "model" to "50RP630"),
            ),
        )
    }

    @Test
    fun `airplay services from other vendors are ignored`() {
        assertFalse(
            RokuMdnsMatcher.shouldResolve(
                serviceType = "_airplay._tcp.",
                serviceName = "Samsung TV",
                attributes = mapOf("manufacturer" to "Samsung", "model" to "QE55"),
            ),
        )
    }

    @Test
    fun `roku in the service name is enough for airplay`() {
        assertTrue(
            RokuMdnsMatcher.shouldResolve(
                serviceType = "_airplay._tcp.",
                serviceName = "Roku-Streaming-Stick",
                attributes = emptyMap(),
            ),
        )
    }
}
