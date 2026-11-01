package com.nuvio.tv.core.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player takes over the stream screen's list only when it is complete, fresh and of the same profile. */
class PlayerListReuseTest {
    @Test
    fun aCompleteFreshListOfTheSameProfileIsReused() {
        assertTrue(StreamPrefetchCache.reusableForPlayer(ageMs = 0, capHit = false, sameProfile = true))
        assertTrue(StreamPrefetchCache.reusableForPlayer(ageMs = StreamPrefetchCache.PLAYER_REUSE_MS, capHit = false,
            sameProfile = true))
    }

    @Test
    fun anOldCutShortOrOtherProfileListIsLoadedAgain() {
        assertFalse(StreamPrefetchCache.reusableForPlayer(ageMs = StreamPrefetchCache.PLAYER_REUSE_MS + 1,
            capHit = false, sameProfile = true))
        assertFalse(StreamPrefetchCache.reusableForPlayer(ageMs = 1_000, capHit = true, sameProfile = true))
        assertFalse(StreamPrefetchCache.reusableForPlayer(ageMs = 1_000, capHit = false, sameProfile = false))
        assertFalse(StreamPrefetchCache.reusableForPlayer(ageMs = -1, capHit = false, sameProfile = true))
    }

    @Test
    fun withoutAListForThisTitleOrEpisodeThePlayerLoadsItself() {
        assertNull(StreamPrefetchCache.completedForPlayer("movie", "tt-not-listed", null, null))
        assertNull(StreamPrefetchCache.completedForPlayer("series", "tt-not-listed", 1, 2))
        assertNull(StreamPrefetchCache.completedForPlayer("", "tt0073195", null, null))
    }
}
