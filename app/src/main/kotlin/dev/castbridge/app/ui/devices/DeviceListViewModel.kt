package dev.castbridge.app.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.castbridge.app.di.AppContainer
import dev.castbridge.core.discovery.repository.DeviceDiscoveryRepository
import dev.castbridge.core.model.CastDevice
import dev.castbridge.core.model.CastProtocol
import dev.castbridge.core.model.DeviceProbe
import dev.castbridge.core.model.DiscoveryIssue
import dev.castbridge.core.model.ProbeResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DeviceFilter {
    ALL,
    CHROMECAST,
    ROKU,
}

data class ProbeUiState(
    val deviceId: String? = null,
    val isChecking: Boolean = false,
    val result: ProbeResult? = null,
)

data class DeviceListUiState(
    val allDevices: List<CastDevice> = emptyList(),
    val visibleDevices: List<CastDevice> = emptyList(),
    val selectedDeviceId: String? = null,
    val selectedDevice: CastDevice? = null,
    val filter: DeviceFilter = DeviceFilter.ALL,
    val isScanning: Boolean = false,
    val issues: List<DiscoveryIssue> = emptyList(),
    val completedCycles: Int = 0,
    val probe: ProbeUiState = ProbeUiState(),
)

sealed interface DeviceListEvent {
    data class ProbeSucceeded(val deviceName: String, val latencyMillis: Long) : DeviceListEvent

    data class ProbeFailed(val deviceName: String, val reason: String) : DeviceListEvent
}

class DeviceListViewModel(
    private val repository: DeviceDiscoveryRepository,
    private val deviceProbe: DeviceProbe,
) : ViewModel() {

    private val selectedDeviceId = MutableStateFlow<String?>(null)
    private val filter = MutableStateFlow(DeviceFilter.ALL)
    private val probeState = MutableStateFlow(ProbeUiState())
    private var probeJob: Job? = null

    private val _events = MutableSharedFlow<DeviceListEvent>(extraBufferCapacity = 2)
    val events = _events.asSharedFlow()

    val uiState: StateFlow<DeviceListUiState> = combine(
        repository.state,
        selectedDeviceId,
        filter,
        probeState,
    ) { discovery, selectedId, activeFilter, probe ->
        val selected = discovery.devices.firstOrNull { it.id == selectedId }
        val visible = when (activeFilter) {
            DeviceFilter.ALL -> discovery.devices
            DeviceFilter.CHROMECAST -> discovery.devices.filter {
                it.protocol == CastProtocol.GOOGLE_CAST
            }
            DeviceFilter.ROKU -> discovery.devices.filter {
                it.protocol == CastProtocol.ROKU
            }
        }

        DeviceListUiState(
            allDevices = discovery.devices,
            visibleDevices = visible,
            selectedDeviceId = selected?.id,
            selectedDevice = selected,
            filter = activeFilter,
            isScanning = discovery.isScanning,
            issues = discovery.issues,
            completedCycles = discovery.completedCycles,
            probe = probe,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DeviceListUiState(),
    )

    fun onStart() {
        repository.startScanning()
    }

    fun onStop() {
        repository.stopScanning()
    }

    fun toggleSelection(deviceId: String) {
        val next = if (selectedDeviceId.value == deviceId) null else deviceId
        selectedDeviceId.value = next

        if (next != probeState.value.deviceId) {
            probeJob?.cancel()
            probeState.value = ProbeUiState()
        }
    }

    fun selectFilter(newFilter: DeviceFilter) {
        filter.value = newFilter
    }

    fun testSelectedConnection() {
        selectedDeviceId.value?.let(::testConnection)
    }

    fun testConnection(deviceId: String) {
        val device = repository.state.value.devices.firstOrNull { it.id == deviceId } ?: return
        if (probeState.value.isChecking) return

        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            probeState.value = ProbeUiState(deviceId = device.id, isChecking = true)
            val result = deviceProbe.probe(device)
            probeState.value = ProbeUiState(
                deviceId = device.id,
                isChecking = false,
                result = result,
            )

            when (result) {
                is ProbeResult.Reachable -> _events.emit(
                    DeviceListEvent.ProbeSucceeded(device.name, result.latencyMillis),
                )
                is ProbeResult.Unreachable -> _events.emit(
                    DeviceListEvent.ProbeFailed(device.name, result.reason),
                )
            }
        }
    }

    override fun onCleared() {
        probeJob?.cancel()
        repository.stopScanning()
        super.onCleared()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DeviceListViewModel(
                    repository = container.deviceRepository,
                    deviceProbe = container.deviceProbe,
                )
            }
        }
    }
}
