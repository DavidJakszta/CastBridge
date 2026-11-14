package dev.castbridge.core.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

internal sealed interface RecordingOutcome {
    data class Success(val result: RecordingResult) : RecordingOutcome

    data class Failure(val message: String, val cause: Throwable?) : RecordingOutcome
}

/**
 * Surface-backed H.264 encoder that mirrors the MediaProjection output into an MP4.
 *
 * The virtual display renders directly into the encoder's input surface, so frames never touch the
 * CPU or a bitmap. The same encoder surface will later feed the streaming transport, which is why
 * this milestone records locally first: it proves the capture and encode path end to end.
 */
internal class H264ScreenRecorder(
    private val projection: MediaProjection,
    private val config: CaptureConfig,
    private val outputFile: File,
    private val onStats: (CaptureStats) -> Unit,
) {
    private val stopRequested = AtomicBoolean(false)
    private val startedAtElapsed = SystemClock.elapsedRealtime()
    private val finished = CompletableDeferred<RecordingOutcome>()
    private var thread: Thread? = null

    /** Completes when the encoder has flushed its last frame and the MP4 is closed. */
    val outcome: CompletableDeferred<RecordingOutcome> get() = finished

    fun start() {
        check(thread == null) { "Recorder already started." }
        thread = Thread(::runPipeline, "CastBridgeEncoder").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    /** Asks the encoder to emit its final frames and close the file. */
    fun requestStop() {
        stopRequested.set(true)
    }

    private fun runPipeline() {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var virtualDisplay: android.hardware.display.VirtualDisplay? = null
        var inputSurface: Surface? = null
        var trackIndex = -1
        var muxerStarted = false
        var frames = 0L
        var bytes = 0L
        var failure: Throwable? = null

        try {
            val activeMuxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            muxer = activeMuxer

            val format = MediaFormat.createVideoFormat(MIME_TYPE, config.width, config.height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyFrameIntervalSeconds)
            }

            val activeCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec = activeCodec
            activeCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = activeCodec.createInputSurface()
            inputSurface = surface
            activeCodec.start()

            virtualDisplay = projection.createVirtualDisplay(
                DISPLAY_NAME,
                config.width,
                config.height,
                config.densityDpi,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null,
            )

            val bufferInfo = MediaCodec.BufferInfo()
            var endOfStreamSignalled = false
            var lastStatsAt = SystemClock.elapsedRealtime()

            while (true) {
                if (stopRequested.get() && !endOfStreamSignalled) {
                    runCatching { activeCodec.signalEndOfInputStream() }
                    endOfStreamSignalled = true
                }

                when (val index = activeCodec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = activeMuxer.addTrack(activeCodec.outputFormat)
                            activeMuxer.start()
                            muxerStarted = true
                        }
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                    else -> if (index >= 0) {
                        val buffer = activeCodec.getOutputBuffer(index)
                        if (buffer != null &&
                            bufferInfo.size > 0 &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            if (muxerStarted) {
                                activeMuxer.writeSampleData(trackIndex, buffer, bufferInfo)
                                frames++
                                bytes += bufferInfo.size
                            }
                        }
                        activeCodec.releaseOutputBuffer(index, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }

                val now = SystemClock.elapsedRealtime()
                if (now - lastStatsAt >= STATS_INTERVAL_MS) {
                    lastStatsAt = now
                    onStats(
                        CaptureStats(
                            elapsedMillis = now - startedAtElapsed,
                            frames = frames,
                            bytes = bytes,
                            width = config.width,
                            height = config.height,
                            bitrate = config.bitrate,
                            frameRate = config.frameRate,
                        ),
                    )
                }

                if (now - startedAtElapsed >= MAX_SESSION_MS) {
                    Log.w(TAG, "Capture reached the ${MAX_SESSION_MS / 60_000} minute limit")
                    stopRequested.set(true)
                }
            }
        } catch (t: Throwable) {
            failure = t
            Log.w(TAG, "Encoder pipeline failed", t)
        } finally {
            runCatching { virtualDisplay?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { inputSurface?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }

            val durationMillis = SystemClock.elapsedRealtime() - startedAtElapsed
            val valid = failure == null && muxerStarted && frames > 0 && outputFile.length() > 0
            if (!valid) {
                runCatching { outputFile.delete() }
            }

            val outcome = if (valid) {
                RecordingOutcome.Success(
                    RecordingResult(
                        filePath = outputFile.absolutePath,
                        durationMillis = durationMillis,
                        width = config.width,
                        height = config.height,
                        sizeBytes = outputFile.length(),
                        frameCount = frames,
                    ),
                )
            } else {
                RecordingOutcome.Failure(
                    message = failure?.message
                        ?: "The encoder did not produce any video. Try recording again.",
                    cause = failure,
                )
            }
            finished.complete(outcome)
        }
    }

    private companion object {
        const val TAG = "CaptureEncoder"
        const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        const val DISPLAY_NAME = "CastBridge"
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val STATS_INTERVAL_MS = 500L
        const val MAX_SESSION_MS = 30 * 60 * 1000L
    }
}
