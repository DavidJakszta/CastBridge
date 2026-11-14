package dev.castbridge.core.capture

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import java.io.File

/** Creates capture files inside the app-specific Movies directory (no storage permission needed). */
internal object RecordingFiles {
    private const val DIRECTORY = "captures"

    fun createOutputFile(context: Context, startedAtMillis: Long): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val directory = File(base, DIRECTORY).apply { mkdirs() }
        return File(directory, CaptureNaming.fileName(startedAtMillis))
    }

    /**
     * Reads the real duration and dimensions back from the container. The encoder's counters are
     * used as a fallback if the platform cannot open the file.
     */
    fun summarize(recording: RecordingResult): RecordingResult {
        val file = File(recording.filePath)
        if (!file.exists()) return recording

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: recording.durationMillis
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: recording.width
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: recording.height

            recording.copy(
                durationMillis = duration,
                width = width,
                height = height,
                sizeBytes = file.length(),
            )
        } catch (_: Exception) {
            recording.copy(sizeBytes = file.length())
        } finally {
            runCatching { retriever.release() }
        }
    }
}
