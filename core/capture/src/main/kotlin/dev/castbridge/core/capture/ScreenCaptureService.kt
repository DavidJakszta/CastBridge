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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that owns the MediaProjection and encoder.
 *
 * Android 14+ requires the media-projection foreground service to be running *before*
 * `getMediaProjection()` is called, so the service starts its notification first and only then
 * obtains the projection token.
 */
class ScreenCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var recorder: H264ScreenRecorder? = null
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

        CaptureStore.update(CaptureState.Preparing)
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

        val outputFile = RecordingFiles.createOutputFile(this, System.currentTimeMillis())
        val activeRecorder = H264ScreenRecorder(
            projection = mediaProjection,
            config = config,
            outputFile = outputFile,
            onStats = { stats -> CaptureStore.update(CaptureState.Running(stats)) },
        )
        recorder = activeRecorder
        activeRecorder.start()

        scope.launch {
            val outcome = activeRecorder.outcome.await()
            complete(outcome)
        }
    }

    private fun handleStop() {
        val activeRecorder = recorder
        if (activeRecorder == null) {
            if (CaptureStore.state.value is CaptureState.Preparing) {
                fail(getString(R.string.capture_error_projection))
            } else {
                stopSelf()
            }
            return
        }

        if (!finishing) CaptureStore.update(CaptureState.Finalizing)
        activeRecorder.requestStop()

        scope.launch {
            val finished = withTimeoutOrNull(STOP_TIMEOUT_MS) { activeRecorder.outcome.await() }
            if (finished == null && !finishing) {
                Log.w(TAG, "Encoder did not stop within $STOP_TIMEOUT_MS ms")
                complete(
                    RecordingOutcome.Failure(
                        message = getString(R.string.capture_error_timeout),
                        cause = null,
                    ),
                )
            }
        }
    }

    private fun complete(outcome: RecordingOutcome) {
        if (finishing) return
        finishing = true

        val nextState = when (outcome) {
            is RecordingOutcome.Success -> {
                val summary = RecordingFiles.summarize(outcome.result)
                Log.i(
                    TAG,
                    "Recording saved: ${summary.filePath} " +
                        "(${summary.durationMillis} ms, ${summary.width}x${summary.height}, " +
                        "${summary.sizeBytes} bytes, ${summary.frameCount} frames)",
                )
                CaptureState.Completed(summary)
            }

            is RecordingOutcome.Failure -> {
                Log.w(TAG, "Recording failed: ${outcome.message}", outcome.cause)
                CaptureState.Failed(outcome.message)
            }
        }

        CaptureStore.update(nextState)
        cleanup()
        stopForegroundAndSelf()
    }

    private fun fail(message: String) {
        if (finishing) return
        finishing = true
        Log.w(TAG, message)
        CaptureStore.update(CaptureState.Failed(message))
        cleanup()
        stopForegroundAndSelf()
    }

    private fun cleanup() {
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_capture_notification)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.capture_notification_stop), stopIntent)
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
        scope.cancel()
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

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val ACTION_START = "dev.castbridge.core.capture.action.START"
        private const val ACTION_STOP = "dev.castbridge.core.capture.action.STOP"

        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val EXTRA_WIDTH = "extra_width"
        private const val EXTRA_HEIGHT = "extra_height"
        private const val EXTRA_DENSITY = "extra_density"
        private const val EXTRA_FRAME_RATE = "extra_frame_rate"
        private const val EXTRA_BITRATE = "extra_bitrate"

        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 41
        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2
        private const val STOP_TIMEOUT_MS = 6_000L

        fun start(context: Context, resultCode: Int, data: Intent, config: CaptureConfig) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_WIDTH, config.width)
                .putExtra(EXTRA_HEIGHT, config.height)
                .putExtra(EXTRA_DENSITY, config.densityDpi)
                .putExtra(EXTRA_FRAME_RATE, config.frameRate)
                .putExtra(EXTRA_BITRATE, config.bitrate)

            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the capture service", e)
                CaptureStore.update(CaptureState.Failed("Screen capture could not be started."))
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "Could not stop the capture service", it) }
        }
    }
}
