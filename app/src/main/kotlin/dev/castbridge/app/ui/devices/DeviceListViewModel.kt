package dev.castbridge.app.ui.devices

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.castbridge.app.di.AppContainer
import dev.castbridge.core.capture.CaptureConfig
import dev.castbridge.core.capture.CaptureController
import dev.castbridge.core.capture.CaptureMode
import dev.castbridge.core.capture.CaptureState
import dev.castbridge.core.casting.CastingController
import dev.castbridge.core.casting.CastingState
import dev.castbridge.core.casting.ScreenFraming
import dev.castbridge.core.discovery.repository.DeviceDiscoveryRepository
import dev.castbridge.core.streaming.StreamingState
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
    val capture: CaptureState = CaptureState.Idle,
    val streaming: StreamingState = StreamingState.Idle,
    val casting: CastingState = CastingState.Idle,
    val framing: ScreenFraming = ScreenFraming.FIT,
    /** Set while the stream is starting for a TV the user picked. */
    val pendingCastDeviceId: String? = null,
)

sealed interface DeviceListEvent {
    data class ProbeSucceeded(val deviceName: String, val latencyMillis: Long) : DeviceListEvent

    data class ProbeFailed(val deviceName: String, val reason: String) : DeviceListEvent
}

class DeviceListViewModel(
    private val repository: DeviceDiscoveryRepository,
    private val deviceProbe: DeviceProbe,
    private val captureController: CaptureController,
    private val castingController: CastingController,
) : ViewModel() {

    private val selectedDeviceId = MutableStateFlow<String?>(null)
    private val filter = MutableStateFlow(DeviceFilter.ALL)
    private val probeState = MutableStateFlow(ProbeUiState())
    private val framing = MutableStateFlow(ScreenFraming.FIT)
    private val pendingCastDeviceId = MutableStateFlow<String?>(null)
    private var probeJob: Job? = null

    private val _events = MutableSharedFlow<DeviceListEvent>(extraBufferCapacity = 2)
    val events = _events.asSharedFlow()

    private data class SessionUi(
        val capture: CaptureState,
        val streaming: StreamingState,
        val casting: CastingState,
        val framing: ScreenFraming,
        val pendingCastDeviceId: String?,
    )

    private val sessionUi = combine(
        captureController.state,
        captureController.streamingState,
        castingController.state,
    ) { capture, streaming, casting -> SessionUi(capture, streaming, casting, framing.value, null) }

    private val playUi = combine(
        sessionUi,
        framing,
        pendingCastDeviceId,
    ) { session, activeFraming, pending ->
        session.copy(framing = activeFraming, pendingCastDeviceId = pending)
    }

    init {
        // The stream and the TV session are one feature: when the stream ends, nothing is left to
        // play, and when the user asks for a TV the stream is cast as soon as it is playable.
        viewModelScope.launch {
            captureController.streamingState.collect { streaming ->
                when (streaming) {
                    is StreamingState.Live -> castPending(streaming)
                    is StreamingState.Stopped, is StreamingState.Failed -> {
                        pendingCastDeviceId.value = null
                        castingController.stopIfActive()
                    }
                    else -> Unit
                }
            }
        }
    }

    val uiState: StateFlow<DeviceListUiState> = combine(
        repository.state,
        selectedDeviceId,
        filter,
        probeState,
        playUi,
    ) { discovery, selectedId, activeFilter, probe, session ->
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
            capture = session.capture,
            streaming = session.streaming,
            casting = session.casting,
            framing = session.framing,
            pendingCastDeviceId = session.pendingCastDeviceId,
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
        val previous = selectedDeviceId.value
        val next = if (previous == deviceId) null else deviceId
        selectedDeviceId.value = next

        if (next != probeState.value.deviceId) {
            probeJob?.cancel()
            probeState.value = ProbeUiState()
        }

        // A failure or stop notice belongs to the TV that produced it. Keeping it while another TV
        // is selected would make "Try again" cast somewhere the message is not about.
        if (next != previous) {
            castingController.reset()
            // A stream that is still starting was requested for the previous TV.
            pendingCastDeviceId.value = null
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

    fun startCapture(
        resultCode: Int,
        data: Intent,
        config: CaptureConfig,
        mode: CaptureMode = CaptureMode.RECORD,
    ) {
        captureController.start(resultCode, data, config, mode)
    }

    fun stopCapture() {
        captureController.stop()
    }

    fun dismissCaptureResult() {
        captureController.reset()
    }

    fun dismissStreamingResult() {
        captureController.resetStreaming()
    }

    /**
     * The single "play" action: starts mirroring the screen to the selected TV.
     *
     * When nothing is streaming yet the UI has to ask Android for screen-capture consent first, so
     * this returns true and the caller launches the system dialog. The TV is remembered and the
     * stream is cast automatically once it is live, which is why the user never has to tell
     * "streaming" and "casting" apart.
     */
    fun playOnSelected(): Boolean {
        val state = uiState.value
        val device = state.selectedDevice ?: return false

        return when (val streaming = state.streaming) {
            is StreamingState.Live -> {
                pendingCastDeviceId.value = null
                castingController.cast(
                    device = device,
                    streamUrl = streaming.url,
                    width = streaming.stats.width,
                    height = streaming.stats.height,
                    framing = framing.value,
                )
                false
            }

            StreamingState.Preparing -> {
                pendingCastDeviceId.value = device.id
                false
            }

            // The old session is still tearing down; a new capture request would be dropped.
            StreamingState.Stopping -> false

            else -> {
                if (state.capture.isActive()) return false
                pendingCastDeviceId.value = device.id
                true
            }
        }
    }

    /** Stops the TV session and the stream behind it. */
    fun stopMirroring() {
        pendingCastDeviceId.value = null
        castingController.stop()
        captureController.stop()
    }

    /** Changes how a Roku places the portrait phone screen on the TV. */
    fun setFraming(next: ScreenFraming) {
        if (framing.value == next) return
        framing.value = next

        // Chromecast letterboxes by itself, so only a running Roku needs to be told again.
        val state = uiState.value
        val playing = state.casting as? CastingState.Playing ?: return
        if (playing.protocol != CastProtocol.ROKU) return

        val live = state.streaming as? StreamingState.Live ?: return
        val device = repository.state.value.devices.firstOrNull { it.id == playing.deviceId } ?: return
        castingController.cast(
            device = device,
            streamUrl = live.url,
            width = live.stats.width,
            height = live.stats.height,
            framing = next,
        )
    }

    private fun castPending(live: StreamingState.Live) {
        val deviceId = pendingCastDeviceId.value ?: return
        pendingCastDeviceId.value = null

        val device = repository.state.value.devices.firstOrNull { it.id == deviceId } ?: return
        castingController.cast(
            device = device,
            streamUrl = live.url,
            width = live.stats.width,
            height = live.stats.height,
            framing = framing.value,
        )
    }

    fun castToSelected() {
        val device = uiState.value.selectedDevice ?: return
        val live = uiState.value.streaming as? StreamingState.Live ?: return
        castingController.cast(
            device = device,
            streamUrl = live.url,
            width = live.stats.width,
            height = live.stats.height,
            framing = framing.value,
        )
    }

    fun stopCasting() {
        castingController.stop()
    }

    fun dismissCastingResult() {
        castingController.reset()
    }

    override fun onCleared() {
        probeJob?.cancel()
        repository.stopScanning()
        castingController.stopIfActive()
        super.onCleared()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DeviceListViewModel(
                    repository = container.deviceRepository,
                    deviceProbe = container.deviceProbe,
                    captureController = container.captureController,
                    castingController = container.castingController,
                )
            }
        }
    }
}

private fun CaptureState.isActive(): Boolean =
    this is CaptureState.Preparing ||
        this is CaptureState.Running ||
        this is CaptureState.Finalizing
