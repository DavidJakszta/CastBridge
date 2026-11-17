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
    private var activeFraming: ScreenFraming? = null

    /**
     * Starts playback on [device]. Calling it again with the same device, URL and framing is a
     * no-op, so the UI can retry safely.
     *
     * [width] and [height] are the stream's pixel size. The Roku channel uses them to fit or fill
     * the TV screen without distortion; Chromecast letterboxes by itself and ignores them.
     */
    fun cast(
        device: CastDevice,
        streamUrl: String,
        width: Int = 0,
        height: Int = 0,
        framing: ScreenFraming = ScreenFraming.FIT,
    ) {
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
            activeUrl == url &&
            activeFraming == framing
        ) {
            return
        }

        // Switching TVs must not leave the previous one playing. The stop runs in its own job so a
        // quick second switch cannot cancel it and leave the first TV running.
        val previousDevice = activeDevice
        val previousClient = castClient
        val previousApp = castApp
        val previousMediaSessionId = castMediaSessionId
        val switching = previousDevice != null && previousDevice.id != device.id
        val previousStop: Job? = if (previousDevice != null && previousDevice.id != device.id) {
            scope.launch(Dispatchers.IO) {
                stopDevice(previousDevice, previousClient, previousApp, previousMediaSessionId)
            }
        } else {
            null
        }

        cancelJob()
        castClient = null
        castApp = null
        castMediaSessionId = null
        activeUrl = null
        activeFraming = null
        // A same-device retry can drop the old socket right away; a switch still needs it to stop.
        if (!switching) previousClient?.close()

        activeDevice = device
        activeUrl = url
        activeFraming = framing

        _state.value = CastingState.Connecting(device.id, device.name, device.protocol)
        android.util.Log.d(
            "Casting",
            "Casting to ${device.name} (${device.host}) framing=$framing: $url",
        )
        job = scope.launch {
            try {
                previousStop?.join()

                when (device) {
                    is ChromecastDevice -> playChromecast(device, url, pairingCode)
                    is RokuDevice ->
                        playRoku(device, rokuUrl(url, width, height, framing), pairingCode)
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
        activeFraming = null
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
                stopDevice(device, client, app, mediaSessionId)
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

        val launched = withContext(Dispatchers.IO) {
            client.connect()
            client.launchReceiver()
        }
        castApp = launched

        val loaded = withContext(Dispatchers.IO) { client.loadWithRecovery(launched, url) }
        castApp = loaded.app
        castMediaSessionId = loaded.media.mediaSessionId

        _state.value = CastingState.Playing(
            deviceId = device.id,
            deviceName = device.name,
            protocol = device.protocol,
            pairingCode = pairingCode,
        )

        monitorChromecast(device, client, loaded.app, url)
    }

    /**
     * Watches a playing receiver and repairs a stuck one.
     *
     * A Default Media Receiver can keep a media session forever while showing a black screen and a
     * spinner: it reports `BUFFERING` (or `PLAYING` with a frozen clock) and never recovers on its
     * own. Restarting the receiver and loading again is the only reliable way out, and it has to
     * happen here because [CastV2Client.load] has already returned successfully.
     */
    private suspend fun monitorChromecast(
        device: ChromecastDevice,
        client: CastV2Client,
        initialApp: ReceiverApplication,
        url: String,
    ) {
        var app = initialApp
        var bufferingSinceMillis = 0L
        var lastPosition = -1.0
        var lastProgressAtMillis = System.currentTimeMillis()

        while (scope.isActive) {
            delay(MONITOR_INTERVAL_MILLIS)
            val now = System.currentTimeMillis()

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

            val media = client.pollMediaStatus(app.transportId)
            if (media?.playerState == "IDLE" && media.idleReason == "CANCELLED") {
                _state.value = CastingState.Failed(
                    deviceName = device.name,
                    message = "Playback was stopped on the TV.",
                )
                return
            }
            if (media == null) continue

            val stalled = when (media.playerState) {
                "BUFFERING" -> {
                    if (bufferingSinceMillis == 0L) bufferingSinceMillis = now
                    now - bufferingSinceMillis >= BUFFER_STALL_MILLIS
                }

                "PLAYING" -> {
                    bufferingSinceMillis = 0
                    if (media.currentTime != lastPosition) {
                        lastPosition = media.currentTime
                        lastProgressAtMillis = now
                        false
                    } else {
                        now - lastProgressAtMillis >= PLAYBACK_STALL_MILLIS
                    }
                }

                else -> {
                    bufferingSinceMillis = 0
                    lastPosition = media.currentTime
                    lastProgressAtMillis = now
                    false
                }
            }

            if (!stalled) continue

            android.util.Log.d(
                "Casting",
                "Playback stalled (${media.playerState} at ${media.currentTime}s); restarting receiver",
            )
            val recovered = recoverPlayback(device, client, app, url) ?: return
            app = recovered
            bufferingSinceMillis = 0
            lastPosition = -1.0
            lastProgressAtMillis = System.currentTimeMillis()
        }
    }

    /** Restarts the receiver and loads [url] again. Returns the new app, or null on failure. */
    private suspend fun recoverPlayback(
        device: ChromecastDevice,
        client: CastV2Client,
        app: ReceiverApplication,
        url: String,
    ): ReceiverApplication? {
        return try {
            val loaded = withContext(Dispatchers.IO) {
                client.loadWithRecovery(app, url, forceRestart = true)
            }
            castApp = loaded.app
            castMediaSessionId = loaded.media.mediaSessionId
            loaded.app
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            android.util.Log.e("Casting", "Could not recover playback on ${device.name}", error)
            _state.value = CastingState.Failed(
                deviceName = device.name,
                message = "The Chromecast could not play the stream. Try again.",
            )
            null
        }
    }

    /**
     * Adds the stream size and framing to the deep-link URL so the Roku channel can place the
     * picture without distortion. The HLS server routes on the path, so the extra query is ignored.
     */
    private fun rokuUrl(
        url: String,
        width: Int,
        height: Int,
        framing: ScreenFraming,
    ): String {
        val params = mutableListOf<String>()
        if (width > 0 && height > 0) {
            params += "w=$width"
            params += "h=$height"
        }
        if (framing == ScreenFraming.FILL) params += "fill=1"
        if (params.isEmpty()) return url

        val separator = if (url.contains('?')) '&' else '?'
        return url + separator + params.joinToString("&")
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

    /**
     * Ends playback on [device] and drops any open Cast connection. Used both when the user stops
     * casting and when they switch to another TV. Every network step is best effort: the user's
     * intent already happened locally, and a TV that is off or busy must not block it.
     */
    private suspend fun stopDevice(
        device: CastDevice,
        client: CastV2Client?,
        app: ReceiverApplication?,
        mediaSessionId: Long?,
    ) {
        when {
            client != null -> {
                // Stop playback first, then end the receiver session, then drop the socket.
                if (app != null) {
                    runCatching { client.stopMedia(app.transportId, mediaSessionId) }
                    runCatching { client.stopReceiver(app.sessionId) }
                }
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

    private companion object {
        const val MONITOR_INTERVAL_MILLIS = 2_000L
        const val ROKU_START_GRACE_MILLIS = 4_000L

        /** Long enough to ride out a normal live-edge refill, short enough to not look stuck. */
        const val BUFFER_STALL_MILLIS = 12_000L

        /** A playing receiver whose clock stopped for this long is frozen, not paused by a user. */
        const val PLAYBACK_STALL_MILLIS = 20_000L
    }
}
