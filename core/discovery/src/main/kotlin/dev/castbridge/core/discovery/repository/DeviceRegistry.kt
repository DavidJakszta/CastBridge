package dev.castbridge.core.discovery.repository

import dev.castbridge.core.model.CastDevice
import dev.castbridge.core.model.CastProtocol
import dev.castbridge.core.model.ChromecastDevice
import dev.castbridge.core.model.RokuDevice
import java.util.Locale

/**
 * Keeps a stable unified list across scan cycles. A device is only dropped after it has been absent
 * from two consecutive successful scans of its own protocol, which prevents one dropped multicast
 * reply from making the list flicker.
 */
internal class DeviceRegistry(
    private val clock: () -> Long = System::currentTimeMillis,
    private val missesBeforeRemoval: Int = 2,
) {
    private data class Entry(
        var device: CastDevice,
        var misses: Int,
    )

    private val entries = linkedMapOf<String, Entry>()

    fun applyScan(protocol: CastProtocol, devices: List<CastDevice>) {
        val now = clock()
        val foundIds = devices.mapTo(mutableSetOf()) { it.id }

        devices.forEach { device ->
            if (device.protocol != protocol) return@forEach
            entries[device.id] = Entry(device.withLastSeen(now), misses = 0)
        }

        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val (id, entry) = iterator.next()
            if (entry.device.protocol == protocol && id !in foundIds) {
                entry.misses++
                if (entry.misses >= missesBeforeRemoval) {
                    iterator.remove()
                }
            }
        }
    }

    fun snapshot(): List<CastDevice> = entries.values
        .map { it.device }
        .sortedWith(
            compareBy<CastDevice> { it.name.lowercase(Locale.ROOT) }
                .thenBy { it.protocol.ordinal }
                .thenBy { it.host },
        )

    fun clear() {
        entries.clear()
    }
}

private fun CastDevice.withLastSeen(now: Long): CastDevice = when (this) {
    is ChromecastDevice -> copy(lastSeenMillis = now)
    is RokuDevice -> copy(lastSeenMillis = now)
}
