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

    /**
     * A receiver that starts on the newest segment has no runway at all, because this phone
     * produces segments in real time. The start tag makes it open a few seconds behind live.
     */
    @Test
    fun `live playlist asks the player to start behind the live edge`() {
        val store = HlsMediaStore()
        repeat(HlsMediaStore.DEFAULT_PLAYLIST_WINDOW) { store.add(segment(it, 2_000_000)) }

        val playlist = store.playlist()

        // Six 2 s segments leave 12 s, minus the 4 s margin.
        assertTrue(playlist.contains("#EXT-X-START:TIME-OFFSET=-8"))
        assertEquals(6, playlist.lines().count { it.startsWith("segment") })
    }

    @Test
    fun `a short playlist does not ask for a negative start offset`() {
        val store = HlsMediaStore()
        store.add(segment(0, 2_000_000))

        assertFalse(store.playlist().contains("#EXT-X-START"))
    }

    @Test
    fun `a finished stream starts from its beginning`() {
        val store = HlsMediaStore()
        repeat(6) { store.add(segment(it, 2_000_000)) }
        store.markEnded()

        assertFalse(store.playlist().contains("#EXT-X-START"))
    }

    private fun segment(sequence: Int, durationUs: Long): HlsSegment = HlsSegment(
        sequence = sequence,
        name = "segment$sequence.ts",
        durationUs = durationUs,
        data = byteArrayOf(sequence.toByte()),
    )
}
