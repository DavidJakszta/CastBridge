package dev.castbridge.core.casting

import dev.castbridge.core.model.CastDevice
import dev.castbridge.core.model.ChromecastDevice
import dev.castbridge.core.model.RokuDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays the live HLS stream on the selected TV and keeps track of the session.
 *
 * Chromecast uses the built-in Default Media Receiver over the local Cast v2 protocol. Roku uses a
 * side-loaded developer channel launched through ECP deep linking. Neither path needs an account,
 * a registration, a backend, or an internet connection.
 */
class CastingController internal constructor(
    private val roku: RokuEcpController,
    private val castClientFactory: (ChromecastDevice) -> CastV2Client,
    private val scope: CoroutineScope,
) {
    constructor() : this(
        roku = RokuEcpController(),
        castClientFactory = { device ->
            CastV2Client(
                host = device.host,
                port = device.port,
                log = { line -> android.util.Log.d("CastV2", line) },
            )
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val _state = MutableStateFlow<CastingState>(CastingState.Idle)
    val state: StateFlow<CastingState> = _state.asStateFlow()

    private var job: Job? = null
    private var castClient: CastV2Client? = null
    private var castApp: ReceiverApplication? = null
    private var castMediaSessionId: Long? = null
    private var activeDevice: CastDevice? = null
    private var activeUrl: String? = null

    /**
     * Starts playback on [device]. Calling it again with the same device and URL is a no-op, so the
     * UI can retry safely.
     *
     * [width] and [height] are the stream's pixel size. The Roku channel uses them to fill the TV
     * screen without distortion; Chromecast ignores them.
     */
    fun cast(device: CastDevice, streamUrl: String, width: Int = 0, height: Int = 0) {
        val pairingCode = PairingCode.fromUrl(streamUrl).orEmpty()
        val url = if (pairingCode.isEmpty()) {
            streamUrl
        } else {
            PairingCode.withCode(streamUrl, pairingCode)
        }

        val current = _state.value
        if (current is CastingState.Playing &&
            current.deviceId == device.id &&
            activeDevice?.id == device.id &&
            activeUrl == url
        ) {
            return
        }

        cancelJob()
        closeCastClient()

        activeDevice = device
        activeUrl = url

        _state.value = CastingState.Connecting(device.id, device.name, device.protocol)
        android.util.Log.d("Casting", "Casting to ${device.name} (${device.host}): $url")
        job = scope.launch {
            try {
                when (device) {
                    is ChromecastDevice -> playChromecast(device, url, pairingCode)
                    is RokuDevice -> playRoku(device, rokuUrl(url, width, height), pairingCode)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                android.util.Log.e("Casting", "Casting to ${device.name} failed", error)
                _state.value = CastingState.Failed(
                    deviceName = device.name,
                    message = error.message ?: "Could not start playback on ${device.name}.",
                )
            }
        }
    }

    /** Stops playback on the TV and returns to idle. */
    fun stop() {
        val device = activeDevice
        val client = castClient
        val app = castApp
        val mediaSessionId = castMediaSessionId

        cancelJob()
        activeDevice = null
        activeUrl = null
        castClient = null
        castApp = null
        castMediaSessionId = null

        if (device == null) {
            client?.close()
            return
        }

        _state.value = CastingState.Stopped(device.name)
        scope.launch {
            withContext(Dispatchers.IO) {
                when {
                    client != null && app != null -> {
                        // Stop playback first, then end the receiver session, then drop the socket.
                        runCatching { client.stopMedia(app.transportId, mediaSessionId) }
                        runCatching { client.stopReceiver(app.sessionId) }
                        client.close()
                    }

                    device is RokuDevice -> runCatching {
                        roku.launchChannel(
                            host = device.host,
                            port = device.ecpPort,
                            url = RokuEcpController.STOP_COMMAND,
                        )
                    }
                }
            }
        }
    }

    /** Stops only if something is actually connected, used when the stream itself ends. */
    fun stopIfActive() {
        if (_state.value is CastingState.Connecting || _state.value is CastingState.Playing) {
            stop()
        }
    }

    /** Clears a Stopped/Failed message without touching the TV. */
    fun reset() {
        if (_state.value is CastingState.Stopped || _state.value is CastingState.Failed) {
            _state.value = CastingState.Idle
        }
    }

    private suspend fun playChromecast(
        device: ChromecastDevice,
        url: String,
        pairingCode: String,
    ) {
        val client = castClientFactory(device)
        castClient = client

        val app = withContext(Dispatchers.IO) {
            client.connect()
            client.launchReceiver()
        }
        castApp = app

        val media = withContext(Dispatchers.IO) { client.load(app.transportId, url) }
        castMediaSessionId = media.mediaSessionId

        _state.value = CastingState.Playing(
            deviceId = device.id,
            deviceName = device.name,
            protocol = device.protocol,
            pairingCode = pairingCode,
        )

        monitorChromecast(device, client, app)
    }

    private suspend fun monitorChromecast(
        device: ChromecastDevice,
        client: CastV2Client,
        app: ReceiverApplication,
    ) {
        while (scope.isActive) {
            delay(MONITOR_INTERVAL_MILLIS)

            if (client.isClosedByPeer()) {
                _state.value = CastingState.Failed(
                    deviceName = device.name,
                    message = "The Chromecast closed the connection.",
                )
                return
            }

            val applications = client.receiverStatus()?.applications.orEmpty()
            val current = applications.firstOrNull()
            if (current == null || current.sessionId != app.sessionId) {
                _state.value = CastingState.Failed(
                    deviceName = device.name,
                    message = "Another device took over the Chromecast.",
                )
                return
            }

            val media = client.mediaStatus()
            if (media?.playerState == "IDLE" && media.idleReason == "CANCELLED") {
                _state.value = CastingState.Failed(
                    deviceName = device.name,
                    message = "Playback was stopped on the TV.",
                )
                return
            }
        }
    }

    /**
     * Adds the stream size to the deep-link URL so the Roku channel can fill the screen without
     * distorting the picture. The HLS server routes on the path, so the extra query is ignored.
     */
    private fun rokuUrl(url: String, width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return url
        val separator = if (url.contains('?')) '&' else '?'
        return "$url${separator}w=$width&h=$height"
    }

    private suspend fun playRoku(device: RokuDevice, url: String, pairingCode: String) {
        withContext(Dispatchers.IO) {
            roku.launchChannel(host = device.host, port = device.ecpPort, url = url)
        }

        _state.value = CastingState.Playing(
            deviceId = device.id,
            deviceName = device.name,
            protocol = device.protocol,
            pairingCode = pairingCode,
        )

        // Give the channel time to open before checking what is in the foreground.
        delay(ROKU_START_GRACE_MILLIS)
        monitorRoku(device)
    }

    private suspend fun monitorRoku(device: RokuDevice) {
        while (scope.isActive) {
            delay(MONITOR_INTERVAL_MILLIS)

            val activeApp = withContext(Dispatchers.IO) {
                roku.activeAppId(host = device.host, port = device.ecpPort)
            }
            // A null answer is transient (the TV can be busy); only act on a definite change.
            if (activeApp != null && activeApp != RokuEcpController.CHANNEL_ID) {
                _state.value = CastingState.Failed(
                    deviceName = device.name,
                    message = "Playback stopped on the TV.",
                )
                return
            }
        }
    }

    private fun cancelJob() {
        job?.cancel()
        job = null
    }

    private fun closeCastClient() {
        castClient?.close()
        castClient = null
        castApp = null
        castMediaSessionId = null
        activeUrl = null
    }

    private companion object {
        const val MONITOR_INTERVAL_MILLIS = 2_000L
        const val ROKU_START_GRACE_MILLIS = 4_000L
    }
}
