package dev.castbridge.core.discovery.repository

import android.util.Log
import dev.castbridge.core.model.CastDevice
import dev.castbridge.core.model.DeviceDiscovery
import dev.castbridge.core.model.DiscoveryException
import dev.castbridge.core.model.DiscoveryIssue
import dev.castbridge.core.model.DiscoveryState
import java.net.SocketException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs every [DeviceDiscovery] in parallel, merges results into one list and exposes discovery health.
 *
 * Each transport is scanned independently so fast transports (SSDP) publish immediately instead of
 * waiting for the slowest one (mDNS resolution). A successful scan clears that transport's issue;
 * a failed scan leaves previously discovered devices in place.
 */
class DeviceDiscoveryRepository(
    private val discoveries: List<DeviceDiscovery>,
    private val scanTimeoutMillis: Long = DEFAULT_SCAN_TIMEOUT_MS,
    private val pauseBetweenScansMillis: Long = DEFAULT_PAUSE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val registry = DeviceRegistry(clock = clock)
    private val registryMutex = Mutex()

    private val _state = MutableStateFlow(DiscoveryState())
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    private val lifecycleLock = Any()
    private var scanJob: Job? = null
    private var generation = 0

    fun startScanning() {
        synchronized(lifecycleLock) {
            if (scanJob?.isActive == true) return

            val myGeneration = ++generation
            _state.update { it.copy(isScanning = true) }

            scanJob = scope.launch {
                try {
                    while (isActive) {
                        scanOnce()
                        if (!isActive) break
                        delay(pauseBetweenScansMillis)
                    }
                } finally {
                    if (myGeneration == generation) {
                        _state.update { it.copy(isScanning = false) }
                    }
                }
            }
        }
    }

    fun stopScanning() {
        synchronized(lifecycleLock) {
            generation++
            scanJob?.cancel()
            scanJob = null
            _state.update { it.copy(isScanning = false) }
        }
    }

    private suspend fun scanOnce() {
        coroutineScope {
            discoveries.forEach { discovery ->
                launch {
                    try {
                        val devices: List<CastDevice> = discovery.scan(scanTimeoutMillis)
                        registryMutex.withLock {
                            registry.applyScan(discovery.protocol, devices)
                            _state.update { current ->
                                current.copy(
                                    devices = registry.snapshot(),
                                    issues = current.issues.filterNot {
                                        it.protocol == discovery.protocol
                                    },
                                    lastUpdateMillis = clock(),
                                )
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        Log.w(TAG, "${discovery.protocol} discovery failed", error)
                        _state.update { current ->
                            current.copy(
                                issues = current.issues.filterNot {
                                    it.protocol == discovery.protocol
                                } + DiscoveryIssue(
                                    protocol = discovery.protocol,
                                    message = error.toUserMessage(),
                                ),
                            )
                        }
                    }
                }
            }
        }

        _state.update { it.copy(completedCycles = it.completedCycles + 1) }
    }

    private fun Throwable.toUserMessage(): String = when (this) {
        is DiscoveryException -> message ?: "Discovery could not start."
        is SecurityException -> "Local network access was blocked by the system."
        is SocketException -> "The network blocked local discovery. Guest or isolated Wi-Fi often does this."
        else -> message ?: "Unknown discovery error."
    }

    companion object {
        private const val TAG = "DeviceRepository"
        private const val DEFAULT_SCAN_TIMEOUT_MS = 12_000L
        private const val DEFAULT_PAUSE_MS = 1_500L
    }
}
