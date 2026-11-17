package dev.castbridge.core.casting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CastMessageCodecTest {
    @Test
    fun `round trips a string message`() {
        val original = CastMessage(
            sourceId = "sender-0",
            destinationId = "receiver-0",
            namespace = "urn:x-cast:com.google.cast.receiver",
            payloadUtf8 = """{"type":"LAUNCH","appId":"CC1AD845"}""",
        )

        val decoded = CastMessageCodec.decode(CastMessageCodec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `round trips a message with an empty payload`() {
        val original = CastMessage(
            sourceId = "sender-0",
            destinationId = "receiver-0",
            namespace = "urn:x-cast:com.google.cast.tp.connection",
        )

        val decoded = CastMessageCodec.decode(CastMessageCodec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `encodes the documented protobuf field layout`() {
        val encoded = CastMessageCodec.encode(
            CastMessage(
                sourceId = "a",
                destinationId = "b",
                namespace = "n",
                payloadUtf8 = "p",
            ),
        )

        // field 1 (protocol version) varint 0, then strings for fields 2, 3, 4, payload type 0,
        // and the payload string in field 6.
        val expected = byteArrayOf(
            0x08, 0x00,
            0x12, 0x01, 'a'.code.toByte(),
            0x1A, 0x01, 'b'.code.toByte(),
            0x22, 0x01, 'n'.code.toByte(),
            0x28, 0x00,
            0x32, 0x01, 'p'.code.toByte(),
        )

        assertEquals(expected.toList(), encoded.toList())
    }

    @Test
    fun `decodes UTF-8 payloads`() {
        val payload = """{"title":"Wohnzimmer · 50€"}"""
        val encoded = CastMessageCodec.encode(
            CastMessage(
                sourceId = "sender-0",
                destinationId = "receiver-0",
                namespace = "urn:x-cast:com.google.cast.media",
                payloadUtf8 = payload,
            ),
        )

        assertEquals(payload, CastMessageCodec.decode(encoded)?.payloadUtf8)
    }

    @Test
    fun `ignores unknown fields`() {
        val base = CastMessageCodec.encode(
            CastMessage(
                sourceId = "a",
                destinationId = "b",
                namespace = "n",
                payloadUtf8 = "p",
            ),
        )
        // Field 15, varint 7, appended after the known fields.
        val withUnknown = base + byteArrayOf(0x78, 0x07)

        assertNotNull(CastMessageCodec.decode(withUnknown))
        assertEquals("p", CastMessageCodec.decode(withUnknown)?.payloadUtf8)
    }

    @Test
    fun `rejects truncated input`() {
        val encoded = CastMessageCodec.encode(
            CastMessage(
                sourceId = "sender-0",
                destinationId = "receiver-0",
                namespace = "n",
                payloadUtf8 = "payload",
            ),
        )

        assertNull(CastMessageCodec.decode(encoded.copyOf(encoded.size - 3)))
    }
}
