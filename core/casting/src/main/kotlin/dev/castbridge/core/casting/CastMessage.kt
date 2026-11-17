package dev.castbridge.core.casting

/**
 * One Cast v2 protocol message.
 *
 * The protocol wraps a small protobuf envelope (`CastMessage`) in a four-byte big-endian length
 * prefix. Only the fields this app needs are modelled; unknown fields are ignored when decoding so
 * newer receiver firmware cannot break parsing.
 */
internal data class CastMessage(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payloadUtf8: String? = null,
    val payloadType: Int = PAYLOAD_TYPE_STRING,
    val protocolVersion: Int = PROTOCOL_VERSION_CASTV2_1_0,
) {
    companion object {
        const val PROTOCOL_VERSION_CASTV2_1_0 = 0
        const val PAYLOAD_TYPE_STRING = 0
        const val PAYLOAD_TYPE_BINARY = 1

        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
    }
}

/** Minimal protobuf encoder/decoder for the `CastMessage` envelope. */
internal object CastMessageCodec {
    private const val FIELD_PROTOCOL_VERSION = 1
    private const val FIELD_SOURCE_ID = 2
    private const val FIELD_DESTINATION_ID = 3
    private const val FIELD_NAMESPACE = 4
    private const val FIELD_PAYLOAD_TYPE = 5
    private const val FIELD_PAYLOAD_UTF8 = 6
    private const val FIELD_PAYLOAD_BINARY = 7

    fun encode(message: CastMessage): ByteArray {
        val out = ByteArrayBuilder()
        out.writeTag(FIELD_PROTOCOL_VERSION, WIRE_VARINT)
        out.writeVarint(message.protocolVersion.toLong())
        out.writeString(FIELD_SOURCE_ID, message.sourceId)
        out.writeString(FIELD_DESTINATION_ID, message.destinationId)
        out.writeString(FIELD_NAMESPACE, message.namespace)
        out.writeTag(FIELD_PAYLOAD_TYPE, WIRE_VARINT)
        out.writeVarint(message.payloadType.toLong())
        if (message.payloadUtf8 != null) {
            out.writeString(FIELD_PAYLOAD_UTF8, message.payloadUtf8)
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): CastMessage? {
        var protocolVersion = CastMessage.PROTOCOL_VERSION_CASTV2_1_0
        var sourceId = ""
        var destinationId = ""
        var namespace = ""
        var payloadType = CastMessage.PAYLOAD_TYPE_STRING
        var payloadUtf8: String? = null

        var index = 0
        while (index < bytes.size) {
            val tag = readVarint(bytes, index) ?: return null
            index = tag.next
            val field = (tag.value ushr 3).toInt()
            when (tag.value and 0x7) {
                WIRE_VARINT.toLong() -> {
                    val value = readVarint(bytes, index) ?: return null
                    index = value.next
                    when (field) {
                        FIELD_PROTOCOL_VERSION -> protocolVersion = value.value.toInt()
                        FIELD_PAYLOAD_TYPE -> payloadType = value.value.toInt()
                    }
                }

                WIRE_LENGTH_DELIMITED.toLong() -> {
                    val length = readVarint(bytes, index) ?: return null
                    index = length.next
                    val size = length.value
                    if (size < 0 || index + size > bytes.size) return null
                    val end = (index + size).toInt()
                    when (field) {
                        FIELD_SOURCE_ID -> sourceId = bytes.decodeToString(index, end)
                        FIELD_DESTINATION_ID -> destinationId = bytes.decodeToString(index, end)
                        FIELD_NAMESPACE -> namespace = bytes.decodeToString(index, end)
                        FIELD_PAYLOAD_UTF8 -> payloadUtf8 = bytes.decodeToString(index, end)
                        FIELD_PAYLOAD_BINARY -> Unit
                    }
                    index = end
                }

                else -> return null
            }
        }

        return CastMessage(
            sourceId = sourceId,
            destinationId = destinationId,
            namespace = namespace,
            payloadUtf8 = payloadUtf8,
            payloadType = payloadType,
            protocolVersion = protocolVersion,
        )
    }

    private fun ByteArray.decodeToString(start: Int, end: Int): String =
        String(this, start, end - start, Charsets.UTF_8)

    private data class Varint(val value: Long, val next: Int)

    private fun readVarint(bytes: ByteArray, start: Int): Varint? {
        var result = 0L
        var shift = 0
        var index = start
        while (index < bytes.size && shift < 64) {
            val byte = bytes[index].toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            index++
            if (byte and 0x80 == 0) return Varint(result, index)
            shift += 7
        }
        return null
    }

    private class ByteArrayBuilder(initialCapacity: Int = 128) {
        private var buffer = ByteArray(initialCapacity)
        private var size = 0

        fun writeTag(field: Int, wireType: Int) = writeVarint(((field shl 3) or wireType).toLong())

        fun writeVarint(value: Long) {
            var remaining = value
            while (true) {
                val byte = (remaining and 0x7F).toInt()
                remaining = remaining ushr 7
                if (remaining == 0L) {
                    append(byte)
                    return
                }
                append(byte or 0x80)
            }
        }

        fun writeString(field: Int, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeTag(field, WIRE_LENGTH_DELIMITED)
            writeVarint(bytes.size.toLong())
            bytes.forEach { append(it.toInt() and 0xFF) }
        }

        private fun append(byte: Int) {
            if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            buffer[size++] = byte.toByte()
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2
}
