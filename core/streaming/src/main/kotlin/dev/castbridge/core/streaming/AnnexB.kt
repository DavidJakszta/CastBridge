package dev.castbridge.core.streaming

import java.io.ByteArrayOutputStream

/**
 * H.264 bitstream helpers.
 *
 * MediaCodec encoders normally emit Annex-B, but a few devices and the `csd-0`/`csd-1` buffers use
 * length-prefixed AVCC. MPEG-TS needs Annex-B, so every buffer is normalised at the boundary.
 */
object AnnexB {
    private val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    fun normalize(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        if (hasStartCode(data)) return data
        return fromLengthPrefixed(data) ?: (START_CODE + data)
    }

    fun hasStartCode(data: ByteArray): Boolean {
        if (data.size < 3) return false
        if (data[0] == 0x00.toByte() && data[1] == 0x00.toByte()) {
            if (data[2] == 0x01.toByte()) return true
            if (data.size >= 4 && data[2] == 0x00.toByte() && data[3] == 0x01.toByte()) return true
        }
        return false
    }

    /**
     * Converts a complete sequence of 4-byte length-prefixed NAL units to Annex-B. Returns null if
     * the buffer is not a valid AVCC sequence, in which case the caller treats it as a single NAL.
     */
    private fun fromLengthPrefixed(data: ByteArray): ByteArray? {
        val output = ByteArrayOutputStream(data.size + 8)
        var offset = 0
        var nalCount = 0

        while (offset + 4 <= data.size) {
            val length = ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)

            if (length <= 0 || offset + 4 + length > data.size) return null

            output.write(START_CODE)
            output.write(data, offset + 4, length)
            offset += 4 + length
            nalCount++
        }

        return if (offset == data.size && nalCount > 0) output.toByteArray() else null
    }
}
