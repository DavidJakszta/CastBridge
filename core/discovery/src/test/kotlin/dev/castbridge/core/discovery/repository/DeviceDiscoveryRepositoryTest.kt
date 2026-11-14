package dev.castbridge.core.discovery.repository

import dev.castbridge.core.model.CastDevice
import dev.castbridge.core.model.CastProtocol
import dev.castbridge.core.model.ChromecastDevice
import dev.castbridge.core.model.DeviceDiscovery
import dev.castbridge.core.model.DiscoveryException
import dev.castbridge.core.model.RokuDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceDiscoveryRepositoryTest {

    @Test
    fun `publishes devices from all transports`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cast = FakeDiscovery(
            protocol = CastProtocol.GOOGLE_CAST,
            devices = listOf(castDevice("cast:a", "Living Room")),
        )
        val roku = FakeDiscovery(
            protocol = CastProtocol.ROKU,
            devices = listOf(rokuDevice("roku:b", "Bedroom")),
        )
        val repository = repository(dispatcher, cast, roku)

        repository.startScanning()
        runCurrent()

        val state = repository.state.value
        assertTrue(state.isScanning)
        assertEquals(2, state.devices.size)
        assertEquals(1, state.completedCycles)
        assertTrue(state.issues.isEmpty())
        assertEquals(setOf("Living Room", "Bedroom"), state.devices.map { it.name }.toSet())

        repository.stopScanning()
        runCurrent()
        assertFalse(repository.state.value.isScanning)
    }

    @Test
    fun `removes a device after two successful scans without it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cast = FakeDiscovery(
            protocol = CastProtocol.GOOGLE_CAST,
            devices = listOf(castDevice("cast:a", "Living Room")),
        )
        val repository = repository(dispatcher, cast)

        repository.startScanning()
        runCurrent()
        assertEquals(1, repository.state.value.devices.size)

        cast.devices = emptyList()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, repository.state.value.devices.size)

        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(repository.state.value.devices.isEmpty())

        repository.stopScanning()
    }

    @Test
    fun `failure is reported but previously found devices stay`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cast = FakeDiscovery(
            protocol = CastProtocol.GOOGLE_CAST,
            devices = listOf(castDevice("cast:a", "Living Room")),
        )
        val roku = FakeDiscovery(protocol = CastProtocol.ROKU)
        val repository = repository(dispatcher, cast, roku)

        repository.startScanning()
        runCurrent()

        roku.error = DiscoveryException("SSDP blocked")
        advanceTimeBy(1_000)
        runCurrent()

        val state = repository.state.value
        assertEquals(1, state.devices.size)
        assertEquals(1, state.issues.size)
        assertEquals(CastProtocol.ROKU, state.issues.single().protocol)
        assertEquals("SSDP blocked", state.issues.single().message)

        roku.error = null
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(repository.state.value.issues.isEmpty())

        repository.stopScanning()
    }

    @Test
    fun `stopping cancels an in-flight scan`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val blocking = object : DeviceDiscovery {
            override val protocol = CastProtocol.GOOGLE_CAST
            override suspend fun scan(timeoutMillis: Long): List<CastDevice> {
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val repository = repository(dispatcher, blocking)

        repository.startScanning()
        runCurrent()
        repository.stopScanning()
        runCurrent()

        assertFalse(repository.state.value.isScanning)
        assertTrue(repository.state.value.devices.isEmpty())
    }

    private fun repository(
        dispatcher: TestDispatcher,
        vararg discoveries: DeviceDiscovery,
    ): DeviceDiscoveryRepository = DeviceDiscoveryRepository(
        discoveries = discoveries.toList(),
        pauseBetweenScansMillis = 1_000,
        clock = { 42L },
        dispatcher = dispatcher,
    )

    private fun castDevice(id: String, name: String): CastDevice =
        ChromecastDevice(id = id, name = name, host = "10.0.0.2")

    private fun rokuDevice(id: String, name: String): CastDevice =
        RokuDevice(id = id, name = name, host = "10.0.0.3")

    private class FakeDiscovery(
        override val protocol: CastProtocol,
        var devices: List<CastDevice> = emptyList(),
        var error: Exception? = null,
    ) : DeviceDiscovery {
        override suspend fun scan(timeoutMillis: Long): List<CastDevice> {
            error?.let { throw it }
            return devices
        }
    }
}
