package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.player.thumbnail.isThumbnailSource
import org.junit.Assert.*
import org.junit.Test

class PlaybackPurposeBoundaryTest {
    @Test fun rawTsAndExtensionlessLiveUrlsCannotEnterThumbnailFetch() {
        for (url in listOf("https://example.invalid/live/101.ts", "https://example.invalid/101")) {
            assertTrue(isThumbnailSource(url)) // Existing VOD default is intentionally preserved.
            for (purpose in PlaybackPurpose.entries.filter { it != PlaybackPurpose.VOD }) {
                assertFalse(isThumbnailSource(url, purpose = purpose))
            }
        }
    }
    @Test fun existingVodAdaptiveExclusionsRemain() {
        assertFalse(isThumbnailSource("https://example.invalid/live.m3u8"))
        assertFalse(isThumbnailSource("https://example.invalid/file", "application/dash+xml"))
        assertTrue(isThumbnailSource("https://example.invalid/movie.mkv"))
    }
}
