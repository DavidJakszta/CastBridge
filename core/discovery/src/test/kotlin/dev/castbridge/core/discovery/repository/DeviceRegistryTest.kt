package dev.castbridge.core.discovery.repository

import dev.castbridge.core.model.CastDevice
import dev.castbridge.core.model.CastProtocol
import dev.castbridge.core.model.ChromecastDevice
import dev.castbridge.core.model.RokuDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRegistryTest {

    private var now = 1_000L
    private val registry = DeviceRegistry(clock = { now })

    @Test
    fun `merges protocols and sorts by name`() {
        registry.applyScan(
            CastProtocol.GOOGLE_CAST,
            listOf(castDevice(id = "cast:a", name = "Zebra TV")),
        )
        registry.applyScan(
            CastProtocol.ROKU,
            listOf(rokuDevice(id = "roku:b", name = "Attic TV")),
        )

        val snapshot = registry.snapshot()
        assertEquals(listOf("Attic TV", "Zebra TV"), snapshot.map { it.name })
        assertEquals(CastProtocol.ROKU, snapshot[0].protocol)
        assertEquals(CastProtocol.GOOGLE_CAST, snapshot[1].protocol)
    }

    @Test
    fun `device survives one missing scan and is removed after two`() {
        val device = castDevice(id = "cast:a", name = "Living Room")
        registry.applyScan(CastProtocol.GOOGLE_CAST, listOf(device))

        registry.applyScan(CastProtocol.GOOGLE_CAST, emptyList())
        assertEquals(1, registry.snapshot().size)

        registry.applyScan(CastProtocol.GOOGLE_CAST, emptyList())
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `reappearing device resets its miss counter`() {
        val device = castDevice(id = "cast:a", name = "Living Room")
        registry.applyScan(CastProtocol.GOOGLE_CAST, listOf(device))
        registry.applyScan(CastProtocol.GOOGLE_CAST, emptyList())

        registry.applyScan(CastProtocol.GOOGLE_CAST, listOf(device))
        registry.applyScan(CastProtocol.GOOGLE_CAST, emptyList())

        assertEquals(1, registry.snapshot().size)
    }

    @Test
    fun `a scan for one protocol does not age devices from another`() {
        registry.applyScan(
            CastProtocol.GOOGLE_CAST,
            listOf(castDevice(id = "cast:a", name = "Living Room")),
        )

        registry.applyScan(CastProtocol.ROKU, emptyList())
        registry.applyScan(CastProtocol.ROKU, emptyList())

        assertEquals(1, registry.snapshot().size)
    }

    @Test
    fun `a rediscovered device updates mutable fields and last-seen time`() {
        registry.applyScan(
            CastProtocol.ROKU,
            listOf(rokuDevice(id = "roku:a", name = "Old Name", host = "10.0.0.2")),
        )

        now = 5_000L
        registry.applyScan(
            CastProtocol.ROKU,
            listOf(rokuDevice(id = "roku:a", name = "New Name", host = "10.0.0.9")),
        )

        val device = registry.snapshot().single() as RokuDevice
        assertEquals("New Name", device.name)
        assertEquals("10.0.0.9", device.host)
        assertEquals(5_000L, device.lastSeenMillis)
    }

    @Test
    fun `empty snapshot after clear`() {
        registry.applyScan(
            CastProtocol.GOOGLE_CAST,
            listOf(castDevice(id = "cast:a", name = "Living Room")),
        )
        registry.clear()

        assertTrue(registry.snapshot().isEmpty())
        assertNull(registry.snapshot().firstOrNull())
    }

    private fun castDevice(id: String, name: String, host: String = "10.0.0.2"): CastDevice =
        ChromecastDevice(id = id, name = name, host = host)

    private fun rokuDevice(id: String, name: String, host: String = "10.0.0.3"): CastDevice =
        RokuDevice(id = id, name = name, host = host)
}
