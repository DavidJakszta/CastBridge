package dev.castbridge.core.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import dev.castbridge.core.streaming.AnnexB
import dev.castbridge.core.streaming.EncodedAudioSample
import dev.castbridge.core.streaming.EncodedVideoSample
import dev.castbridge.core.streaming.MediaSampleSink
import dev.castbridge.core.streaming.VideoStreamFormat
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

internal sealed interface CaptureOutcome {
    data class Success(
        val stats: CaptureStats,
        val recording: RecordingResult?,
    ) : CaptureOutcome

    data class Failure(val message: String, val cause: Throwable?) : CaptureOutcome
}

/**
 * Surface-backed H.264 encoder that can write an MP4, feed a live stream, or do both.
 *
 * The virtual display renders directly into the encoder's input surface, so frames never touch the
 * CPU or a bitmap. Output buffers are normalised to Annex-B and handed to the streaming sink on the
 * encoder thread; the HLS muxer and HTTP server never block the display pipeline for long.
 */
internal class H264ScreenRecorder(
    private val projection: MediaProjection,
    private val config: CaptureConfig,
    private val outputFile: File?,
    private val streamSink: MediaSampleSink?,
    private val onStats: (CaptureStats) -> Unit,
) {
    private val stopRequested = AtomicBoolean(false)
    private val startedAtElapsed = SystemClock.elapsedRealtime()
    private val finished = CompletableDeferred<CaptureOutcome>()
    private var thread: Thread? = null

    /** Silent AAC frames for the Cast receiver, encoded once and then looped. */
    private var audioTrack: SilentAacTrack? = null
    private var audioOriginUs: Long? = null
    private var audioFrameIndex = 0L

    /** Completes when the encoder has flushed its last frame and all outputs are closed. */
    val outcome: CompletableDeferred<CaptureOutcome> get() = finished

    fun start() {
        check(thread == null) { "Recorder already started." }
        thread = Thread(::runPipeline, "CastBridgeEncoder").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    /** Asks the encoder to emit its final frames and close the outputs. */
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
        var streamEnded = false

        val sessionLimitMs = if (outputFile != null) RECORDING_LIMIT_MS else STREAMING_LIMIT_MS

        try {
            val activeMuxer = outputFile?.let { file ->
                MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }
            muxer = activeMuxer

            val activeCodec = createEncoder()
            codec = activeCodec
            val surface = activeCodec.createInputSurface()
            inputSurface = surface
            activeCodec.start()

            if (streamSink != null) {
                audioTrack = SilentAacTrack.create()
                if (audioTrack == null) {
                    Log.w(TAG, "No AAC encoder available; the stream will be video-only")
                }
            }

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
                        val outputFormat = activeCodec.outputFormat
                        val width = outputFormat.intOr(MediaFormat.KEY_WIDTH, config.width)
                        val height = outputFormat.intOr(MediaFormat.KEY_HEIGHT, config.height)
                        val sps = runCatching {
                            outputFormat.getByteBuffer("csd-0")?.copyBytes()
                        }.getOrNull()
                        val pps = runCatching {
                            outputFormat.getByteBuffer("csd-1")?.copyBytes()
                        }.getOrNull()

                        if (activeMuxer != null && !muxerStarted) {
                            trackIndex = activeMuxer.addTrack(outputFormat)
                            activeMuxer.start()
                            muxerStarted = true
                        }

                        streamSink?.onFormat(
                            VideoStreamFormat(
                                width = width,
                                height = height,
                                sps = sps,
                                pps = pps,
                            ),
                        )
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                    else -> if (index >= 0) {
                        val buffer = activeCodec.getOutputBuffer(index)
                        if (buffer != null && bufferInfo.size > 0) {
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)

                            val isCodecConfig =
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

                            if (isCodecConfig) {
                                val configData = buffer.copyBytes()
                                streamSink?.onCodecConfig(AnnexB.normalize(configData))
                            } else {
                                frames++
                                bytes += bufferInfo.size

                                if (muxerStarted) {
                                    activeMuxer?.writeSampleData(trackIndex, buffer, bufferInfo)
                                }

                                val sink = streamSink
                                if (sink != null) {
                                    buffer.position(bufferInfo.offset)
                                    buffer.limit(bufferInfo.offset + bufferInfo.size)
                                    val presentationTimeUs = bufferInfo.presentationTimeUs
                                    sink.onSample(
                                        EncodedVideoSample(
                                            data = AnnexB.normalize(buffer.copyBytes()),
                                            presentationTimeUs = presentationTimeUs,
                                            isKeyFrame = (
                                                bufferInfo.flags and
                                                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                                                ) != 0,
                                        ),
                                    )
                                    emitSilence(sink, presentationTimeUs)
                                }
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

                if (now - startedAtElapsed >= sessionLimitMs) {
                    Log.w(TAG, "Capture reached the ${sessionLimitMs / 60_000} minute limit")
                    stopRequested.set(true)
                }
            }
        } catch (t: Throwable) {
            failure = t
            Log.w(TAG, "Encoder pipeline failed", t)
        } finally {
            if (!streamEnded) {
                streamEnded = true
                runCatching { streamSink?.onEnd() }
                    .onFailure { Log.w(TAG, "Streaming sink failed to finish", it) }
            }

            runCatching { virtualDisplay?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { inputSurface?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }

            val durationMillis = SystemClock.elapsedRealtime() - startedAtElapsed
            val recordingValid = outputFile == null || (
                muxerStarted &&
                    frames > 0 &&
                    outputFile.length() > 0
                )
            val valid = failure == null && frames > 0 && recordingValid

            if (!valid) {
                runCatching { outputFile?.delete() }
            }

            val outcome = if (valid) {
                val stats = CaptureStats(
                    elapsedMillis = durationMillis,
                    frames = frames,
                    bytes = bytes,
                    width = config.width,
                    height = config.height,
                    bitrate = config.bitrate,
                    frameRate = config.frameRate,
                )
                val recording = outputFile?.let { file ->
                    RecordingResult(
                        filePath = file.absolutePath,
                        durationMillis = durationMillis,
                        width = config.width,
                        height = config.height,
                        sizeBytes = file.length(),
                        frameCount = frames,
                    )
                }
                CaptureOutcome.Success(stats = stats, recording = recording)
            } else {
                CaptureOutcome.Failure(
                    message = failure?.message
                        ?: "The encoder did not produce any video. Try again.",
                    cause = failure,
                )
            }
            finished.complete(outcome)
        }
    }

    /**
     * Creates the H.264 encoder, asking for High profile at the level the stream actually needs.
     *
     * Some encoders reject explicit profile or level keys, so the second attempt falls back to the
     * device defaults instead of failing the capture.
     */
    private fun createEncoder(): MediaCodec {
        val preferred = MediaCodec.createEncoderByType(MIME_TYPE)
        try {
            preferred.configure(
                createVideoFormat(withLevelOverrides = true),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            return preferred
        } catch (error: Exception) {
            Log.w(TAG, "Encoder rejected the H.264 profile/level overrides; using defaults", error)
            runCatching { preferred.release() }
        }

        return MediaCodec.createEncoderByType(MIME_TYPE).also {
            it.configure(
                createVideoFormat(withLevelOverrides = false),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
        }
    }

    private fun createVideoFormat(withLevelOverrides: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(MIME_TYPE, config.width, config.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyFrameIntervalSeconds)
            capFrameRate()
            if (withLevelOverrides) {
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                )
                setInteger(
                    MediaFormat.KEY_LEVEL,
                    H264Levels.forFrame(config.width, config.height, declaredFrameRate),
                )
            }
        }

    /**
     * Makes the encoder drop source frames above [CaptureConfig.frameRate].
     *
     * The virtual display mirrors a 60 Hz (or faster) screen, so it hands the encoder roughly twice
     * as many frames as requested regardless of [MediaFormat.KEY_FRAME_RATE]. Encoding all of them
     * has three bad consequences:
     *
     *  * the SPS then describes a stream that is faster than the level we advertise, which strict
     *    receivers such as the Cast Default Media Receiver reject outright;
     *  * 59.x fps on a 60 Hz TV repeats a frame every second or so, which reads as judder;
     *  * twice as many IDRs per segment steals bitrate from the frames in between, so the picture
     *    visibly pumps in sharpness.
     *
     * Dropping happens before encoding, so no reference frame is ever removed.
     */
    private fun MediaFormat.capFrameRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, config.frameRate.toFloat())
        }
    }

    /**
     * The frame rate the encoder is actually allowed to emit, used to pick the H.264 level.
     *
     * Declaring a level for fewer frames than the stream really contains produces an invalid
     * bitstream: a 886x1920 frame at 59 fps needs 403k macroblocks/s, which is level 4.2, not the
     * level 4.0 that 30 fps would justify. Below Android 10 there is no way to cap the encoder, so
     * the ceiling of a mirrored display is assumed instead.
     */
    private val declaredFrameRate: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            config.frameRate
        } else {
            H264Levels.SURFACE_INPUT_CEILING_FPS
        }

    /**
     * Emits the silent AAC frames that cover the video timeline up to [videoPtsUs].
     *
     * A static screen stops producing video for a while, so after a long gap the audio grid jumps
     * forward instead of emitting minutes of catch-up frames.
     */
    private fun emitSilence(sink: MediaSampleSink, videoPtsUs: Long) {
        val track = audioTrack ?: return
        val origin = audioOriginUs ?: videoPtsUs.also { audioOriginUs = it }

        var ptsUs = origin + track.frameTimeUs(audioFrameIndex)
        if (videoPtsUs - ptsUs > MAX_AUDIO_CATCH_UP_US) {
            audioFrameIndex += (videoPtsUs - ptsUs) / track.frameDurationUs
            ptsUs = origin + track.frameTimeUs(audioFrameIndex)
        }

        while (ptsUs <= videoPtsUs) {
            sink.onAudioSample(EncodedAudioSample(track.frame(audioFrameIndex), ptsUs))
            audioFrameIndex++
            ptsUs = origin + track.frameTimeUs(audioFrameIndex)
        }
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        runCatching { getInteger(key) }.getOrDefault(fallback)

    private fun ByteBuffer.copyBytes(): ByteArray {
        val duplicate = duplicate()
        val data = ByteArray(duplicate.remaining())
        duplicate.get(data)
        return data
    }

    private companion object {
        const val TAG = "CaptureEncoder"
        const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        const val DISPLAY_NAME = "CastBridge"
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val STATS_INTERVAL_MS = 500L
        const val RECORDING_LIMIT_MS = 30 * 60 * 1000L
        const val STREAMING_LIMIT_MS = 2 * 60 * 60 * 1000L

        /** After a static-screen gap, resync the silent audio instead of emitting stale frames. */
        const val MAX_AUDIO_CATCH_UP_US = 2_000_000L
    }
}
