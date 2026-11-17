package dev.castbridge.core.streaming

/** One finished HLS media segment. */
class HlsSegment(
    val sequence: Int,
    val name: String,
    val durationUs: Long,
    val data: ByteArray,
)

/**
 * Cuts encoded samples into HLS segments.
 *
 * A segment may only end on a keyframe: the next segment must be independently decodable, and the
 * playlist advertises independent segments. The target duration is therefore a lower bound; with a
 * two-second keyframe interval a 1.5-second target still produces two-second segments.
 *
 * Screens that do not change produce no new frames at all, which would leave the very first segment
 * open forever. [flushPending] closes such a segment on an idle timeout and marks the stream as
 * waiting for the next keyframe, so the next segment still starts independently decodable.
 */
class HlsSegmenter(
    private val targetDurationUs: Long = DEFAULT_TARGET_DURATION_US,
    private val onSegment: (HlsSegment) -> Unit,
) {
    private val muxer = MpegTsMuxer()
    private val lock = Any()

    private var nextSequence = 0
    private var currentStartPtsUs: Long? = null
    private var firstPtsUs: Long? = null
    private var lastPtsUs = 0L
    private var lastFrameDurationUs = DEFAULT_FRAME_DURATION_US
    private var previousPtsUs: Long? = null
    private var samplesInSegment = 0
    private var started = false

    /** True after an idle flush: the next segment must begin with a keyframe. */
    private var awaitingKeyFrame = false

    fun onFormat(format: VideoStreamFormat) = synchronized(lock) {
        muxer.setCodecConfig(format)
    }

    fun onCodecConfig(data: ByteArray) = synchronized(lock) {
        muxer.setCodecConfig(data)
    }

    /**
     * Adds a silent audio frame to the open segment.
     *
     * Audio that arrives before the first keyframe, or after the segment has been closed, has no
     * home: it is dropped rather than opening a segment that would not start with a keyframe.
     */
    fun onAudioSample(sample: EncodedAudioSample): Unit = synchronized(lock) {
        val segmentStart = currentStartPtsUs ?: return
        if (awaitingKeyFrame || samplesInSegment == 0) return

        val basePtsUs = firstPtsUs ?: return
        val ptsUs = sample.presentationTimeUs - basePtsUs
        if (ptsUs < segmentStart) return

        muxer.writeAudioSample(EncodedAudioSample(sample.data, ptsUs))
    }

    fun onSample(sample: EncodedVideoSample) = synchronized(lock) {
        // After an idle flush the encoder continues with a P-frame. Starting a segment on one would
        // produce a segment no player can decode on its own, so drop frames until the next keyframe.
        if (awaitingKeyFrame && !sample.isKeyFrame) return

        awaitingKeyFrame = false

        // Rebase to the first sample. Surface timestamps can be large and would overflow the
        // 33-bit MPEG-TS PTS field; a stream that starts at zero is also friendlier to players.
        val basePtsUs = firstPtsUs ?: sample.presentationTimeUs.also { firstPtsUs = it }
        val ptsUs = sample.presentationTimeUs - basePtsUs
        val segmentStart = currentStartPtsUs

        if (segmentStart != null &&
            samplesInSegment > 0 &&
            sample.isKeyFrame &&
            ptsUs - segmentStart >= targetDurationUs
        ) {
            emitSegment(endPtsUs = ptsUs)
        }

        if (currentStartPtsUs == null) {
            currentStartPtsUs = ptsUs
            muxer.startSegment()
            samplesInSegment = 0
        }

        muxer.writeSample(
            EncodedVideoSample(
                data = sample.data,
                presentationTimeUs = ptsUs,
                isKeyFrame = sample.isKeyFrame,
            ),
        )
        samplesInSegment++

        previousPtsUs?.let { previous ->
            val delta = ptsUs - previous
            if (delta > 0) lastFrameDurationUs = delta
        }
        previousPtsUs = ptsUs
        lastPtsUs = ptsUs
        started = true
    }

    /**
     * Emits the open segment because no frame arrived for a while, then waits for a keyframe.
     *
     * Returns the flushed segment, or null when there is nothing pending.
     */
    fun flushPending(): HlsSegment? = synchronized(lock) {
        if (!started || samplesInSegment == 0) return null

        val segment = emitSegment(endPtsUs = lastPtsUs + lastFrameDurationUs)
        if (segment != null) awaitingKeyFrame = true
        return segment
    }

    /** Emits the final partial segment, if any. */
    fun finish(): HlsSegment? = synchronized(lock) {
        if (!started || samplesInSegment == 0) return null
        return emitSegment(endPtsUs = lastPtsUs + lastFrameDurationUs)
    }

    private fun emitSegment(endPtsUs: Long): HlsSegment? {
        val startPtsUs = currentStartPtsUs ?: return null
        if (samplesInSegment == 0) return null

        val durationUs = (endPtsUs - startPtsUs)
            .coerceAtLeast(lastFrameDurationUs)
            .coerceAtLeast(1L)
        val sequence = nextSequence
        nextSequence++

        val segment = HlsSegment(
            sequence = sequence,
            name = "segment$sequence.ts",
            durationUs = durationUs,
            data = muxer.finishSegment(),
        )

        currentStartPtsUs = null
        samplesInSegment = 0
        onSegment(segment)
        return segment
    }

    companion object {
        const val DEFAULT_TARGET_DURATION_US = 1_500_000L
        private const val DEFAULT_FRAME_DURATION_US = 33_333L
    }
}
