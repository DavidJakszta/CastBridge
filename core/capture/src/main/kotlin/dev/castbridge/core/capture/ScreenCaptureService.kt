package dev.castbridge.core.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dev.castbridge.core.streaming.HlsStreamingSession
import dev.castbridge.core.streaming.StreamingMetadata
import dev.castbridge.core.streaming.StreamingState
import dev.castbridge.core.streaming.StreamingStore
import dev.castbridge.core.streaming.MediaSampleSink
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that owns the MediaProjection and encoder.
 *
 * Android 14+ requires the media-projection foreground service to be running *before*
 * `getMediaProjection()` is called, so the service starts its notification first and only then
 * obtains the projection token. The same service hosts either an MP4 recording or a live HLS
 * session; the two modes share the encoder pipeline.
 */
class ScreenCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var recorder: H264ScreenRecorder? = null
    private var streamingSession: HlsStreamingSession? = null
    private var mode = CaptureMode.RECORD
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var projectionStopped = false
    private var finishing = false
    private var startedForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (recorder != null || finishing) {
            Log.d(TAG, "Ignoring start request, capture is already active")
            return
        }

        mode = intent.toCaptureMode()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val permissionData = IntentCompat.getParcelableExtra(
            intent,
            EXTRA_RESULT_DATA,
            Intent::class.java,
        )
        val config = intent.toCaptureConfig()

        if (resultCode != Activity.RESULT_OK || permissionData == null || config == null) {
            fail(getString(R.string.capture_error_permission))
            return
        }

        if (mode == CaptureMode.STREAM) {
            StreamingStore.update(StreamingState.Preparing)
        } else {
            CaptureStore.update(CaptureState.Preparing)
        }
        startForegroundInternal()
        if (finishing) return

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = try {
            manager?.getMediaProjection(resultCode, permissionData)
        } catch (e: Exception) {
            Log.w(TAG, "Could not obtain the MediaProjection", e)
            null
        }

        if (mediaProjection == null) {
            fail(getString(R.string.capture_error_projection))
            return
        }

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection was stopped")
                projectionStopped = true
                if (!finishing) handleStop()
            }
        }
        projectionStopped = false
        mediaProjection.registerCallback(callback, Handler(Looper.getMainLooper()))
        projection = mediaProjection
        projectionCallback = callback

        val outputFile: File?
        val streamSink: MediaSampleSink?
        if (mode == CaptureMode.STREAM) {
            val session = try {
                HlsStreamingSession(
                    metadata = StreamingMetadata(
                        width = config.width,
                        height = config.height,
                        frameRate = config.frameRate,
                        bitrate = config.bitrate,
                    ),
                    log = { message -> Log.d(STREAM_TAG, message) },
                ).also { it.start() }
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the HLS streaming session", e)
                null
            }

            if (session == null) {
                fail(getString(R.string.capture_error_stream))
                return
            }

            streamingSession = session
            outputFile = null
            streamSink = session
        } else {
            outputFile = RecordingFiles.createOutputFile(this, System.currentTimeMillis())
            streamSink = null
        }

        val activeRecorder = H264ScreenRecorder(
            projection = mediaProjection,
            config = config,
            outputFile = outputFile,
            streamSink = streamSink,
            onStats = { stats ->
                if (mode == CaptureMode.RECORD) {
                    CaptureStore.update(CaptureState.Running(stats))
                }
            },
        )
        recorder = activeRecorder
        activeRecorder.start()

        scope.launch {
            val outcome = activeRecorder.outcome.await()
            complete(outcome)
        }

        if (mode == CaptureMode.STREAM) {
            // A capture that never delivers a frame (for example "Share one app" while that app is
            // in the background) would otherwise leave the UI on "Starting stream" forever.
            scope.launch {
                delay(PREPARING_TIMEOUT_MS)
                if (!finishing && StreamingStore.state.value is StreamingState.Preparing) {
                    fail(getString(R.string.capture_error_no_frames))
                }
            }
        }
    }

    private fun handleStop() {
        val activeRecorder = recorder
        if (activeRecorder == null) {
            if (mode == CaptureMode.STREAM) {
                if (StreamingStore.state.value is StreamingState.Preparing) {
                    fail(getString(R.string.capture_error_projection))
                } else {
                    stopSelf()
                }
            } else if (CaptureStore.state.value is CaptureState.Preparing) {
                fail(getString(R.string.capture_error_projection))
            } else {
                stopSelf()
            }
            return
        }

        if (!finishing) {
            if (mode == CaptureMode.STREAM) {
                StreamingStore.update(StreamingState.Stopping)
            } else {
                CaptureStore.update(CaptureState.Finalizing)
            }
        }
        activeRecorder.requestStop()

        scope.launch {
            val finished = withTimeoutOrNull(STOP_TIMEOUT_MS) { activeRecorder.outcome.await() }
            if (finished == null && !finishing) {
                Log.w(TAG, "Encoder did not stop within $STOP_TIMEOUT_MS ms")
                complete(
                    CaptureOutcome.Failure(
                        message = getString(R.string.capture_error_timeout),
                        cause = null,
                    ),
                )
            }
        }
    }

    private fun complete(outcome: CaptureOutcome) {
        if (finishing) return
        finishing = true

        when (outcome) {
            is CaptureOutcome.Success -> {
                if (mode == CaptureMode.STREAM) {
                    Log.i(
                        TAG,
                        "Stream finished: ${outcome.stats.frames} frames, " +
                            "${outcome.stats.bytes} bytes",
                    )
                } else {
                    val recording = outcome.recording
                    if (recording == null) {
                        Log.w(TAG, "Recording finished without a file")
                        CaptureStore.update(
                            CaptureState.Failed(getString(R.string.capture_error_projection)),
                        )
                    } else {
                        val summary = RecordingFiles.summarize(recording)
                        Log.i(
                            TAG,
                            "Recording saved: ${summary.filePath} " +
                                "(${summary.durationMillis} ms, ${summary.width}x${summary.height}, " +
                                "${summary.sizeBytes} bytes, ${summary.frameCount} frames)",
                        )
                        CaptureStore.update(CaptureState.Completed(summary))
                    }
                }
            }

            is CaptureOutcome.Failure -> {
                Log.w(TAG, "Capture failed: ${outcome.message}", outcome.cause)
                if (mode == CaptureMode.STREAM) {
                    streamingSession?.close()
                    StreamingStore.update(StreamingState.Failed(outcome.message))
                } else {
                    CaptureStore.update(CaptureState.Failed(outcome.message))
                }
            }
        }

        cleanup()
        stopForegroundAndSelf()
    }

    private fun fail(message: String) {
        if (finishing) return
        finishing = true
        Log.w(TAG, message)
        if (mode == CaptureMode.STREAM) {
            streamingSession?.close()
            StreamingStore.update(StreamingState.Failed(message))
        } else {
            CaptureStore.update(CaptureState.Failed(message))
        }
        cleanup()
        stopForegroundAndSelf()
    }

    private fun cleanup() {
        streamingSession?.close()
        streamingSession = null

        projectionCallback?.let { callback ->
            runCatching { projection?.unregisterCallback(callback) }
        }
        projectionCallback = null
        if (!projectionStopped) {
            runCatching { projection?.stop() }
        }
        projection = null
        recorder = null
    }

    private fun stopForegroundAndSelf() {
        if (startedForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            startedForeground = false
        }
        stopSelf()
    }

    private fun startForegroundInternal() {
        val notification = buildNotification()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            startedForeground = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the foreground service", e)
            fail(getString(R.string.capture_error_foreground))
        }
    }

    private fun buildNotification(): Notification {
        ensureChannel()

        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val streaming = mode == CaptureMode.STREAM
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_capture_notification)
            .setContentTitle(
                getString(
                    if (streaming) {
                        R.string.capture_notification_title_stream
                    } else {
                        R.string.capture_notification_title
                    },
                ),
            )
            .setContentText(
                getString(
                    if (streaming) {
                        R.string.capture_notification_text_stream
                    } else {
                        R.string.capture_notification_text
                    },
                ),
            )
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .addAction(
                0,
                getString(
                    if (streaming) {
                        R.string.capture_notification_stop_stream
                    } else {
                        R.string.capture_notification_stop
                    },
                ),
                stopIntent,
            )
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.capture_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.capture_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        finishing = true
        scope.cancel()
        streamingSession?.close()
        recorder?.requestStop()
        cleanup()
        super.onDestroy()
    }

    private fun Intent.toCaptureConfig(): CaptureConfig? = runCatching {
        CaptureConfig(
            width = getIntExtra(EXTRA_WIDTH, 0),
            height = getIntExtra(EXTRA_HEIGHT, 0),
            densityDpi = getIntExtra(EXTRA_DENSITY, 0),
            frameRate = getIntExtra(EXTRA_FRAME_RATE, 0),
            bitrate = getIntExtra(EXTRA_BITRATE, 0),
        )
    }.getOrNull()

    private fun Intent.toCaptureMode(): CaptureMode = runCatching {
        CaptureMode.valueOf(getStringExtra(EXTRA_MODE) ?: CaptureMode.RECORD.name)
    }.getOrDefault(CaptureMode.RECORD)

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val STREAM_TAG = "HlsStreaming"
        private const val ACTION_START = "dev.castbridge.core.capture.action.START"
        private const val ACTION_STOP = "dev.castbridge.core.capture.action.STOP"

        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val EXTRA_WIDTH = "extra_width"
        private const val EXTRA_HEIGHT = "extra_height"
        private const val EXTRA_DENSITY = "extra_density"
        private const val EXTRA_FRAME_RATE = "extra_frame_rate"
        private const val EXTRA_BITRATE = "extra_bitrate"
        private const val EXTRA_MODE = "extra_mode"

        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 41
        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2
        private const val STOP_TIMEOUT_MS = 6_000L
        private const val PREPARING_TIMEOUT_MS = 15_000L

        fun start(
            context: Context,
            resultCode: Int,
            data: Intent,
            config: CaptureConfig,
            mode: CaptureMode = CaptureMode.RECORD,
        ) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_WIDTH, config.width)
                .putExtra(EXTRA_HEIGHT, config.height)
                .putExtra(EXTRA_DENSITY, config.densityDpi)
                .putExtra(EXTRA_FRAME_RATE, config.frameRate)
                .putExtra(EXTRA_BITRATE, config.bitrate)
                .putExtra(EXTRA_MODE, mode.name)

            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the capture service", e)
                if (mode == CaptureMode.STREAM) {
                    StreamingStore.update(
                        StreamingState.Failed("Screen capture could not be started."),
                    )
                } else {
                    CaptureStore.update(CaptureState.Failed("Screen capture could not be started."))
                }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "Could not stop the capture service", it) }
        }
    }
}
