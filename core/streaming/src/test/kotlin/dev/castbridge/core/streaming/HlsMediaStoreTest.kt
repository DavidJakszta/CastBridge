package dev.castbridge.core.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsMediaStoreTest {
    @Test
    fun `lists the newest window and advances the media sequence`() {
        val store = HlsMediaStore(playlistWindow = 2, retention = 3)
        store.add(segment(0, 2_000_000))
        store.add(segment(1, 2_100_000))
        store.add(segment(2, 1_900_000))
        store.add(segment(3, 2_200_000))

        val playlist = store.playlist()

        assertTrue(playlist.contains("#EXT-X-MEDIA-SEQUENCE:2"))
        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:3"))
        assertTrue(playlist.contains("segment2.ts"))
        assertTrue(playlist.contains("segment3.ts"))
        assertFalse(playlist.contains("segment1.ts"))
        assertFalse(playlist.contains("#EXT-X-ENDLIST"))

        // Retention keeps one segment that is no longer advertised.
        assertNull(store.segment("segment0.ts"))
        assertEquals("segment1.ts", store.segment("segment1.ts")?.name)
        assertEquals(4, store.segmentCount)
    }

    @Test
    fun `adds the end list marker when the stream stops`() {
        val store = HlsMediaStore()
        store.add(segment(0, 2_000_000))
        store.markEnded()

        assertTrue(store.isEnded)
        assertTrue(store.playlist().trimEnd().endsWith("#EXT-X-ENDLIST"))
    }

    @Test
    fun `empty playlist is still valid`() {
        val playlist = HlsMediaStore().playlist()

        assertTrue(playlist.startsWith("#EXTM3U"))
        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:2"))
        assertTrue(playlist.contains("#EXT-X-MEDIA-SEQUENCE:0"))
    }

    @Test
    fun `formats durations with millisecond precision`() {
        val store = HlsMediaStore()
        store.add(segment(0, 1_500_000))
        store.add(segment(1, 2_000_000))

        assertTrue(store.playlist().contains("#EXTINF:1.500,"))
    }

    private fun segment(sequence: Int, durationUs: Long): HlsSegment = HlsSegment(
        sequence = sequence,
        name = "segment$sequence.ts",
        durationUs = durationUs,
        data = byteArrayOf(sequence.toByte()),
    )
}
