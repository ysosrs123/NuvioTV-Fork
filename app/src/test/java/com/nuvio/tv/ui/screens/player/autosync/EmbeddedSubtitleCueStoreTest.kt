package com.nuvio.tv.ui.screens.player.autosync

import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedSubtitleCueStoreTest {
    @Test
    fun keepsTheCuesOfTheTwoNewestStreamsOnly() {
        val first = "https://host/store-test/first.mkv"
        val second = "https://host/store-test/second.mkv"
        val third = "https://host/store-test/third.mkv"

        listOf(first, second, third).forEach { source ->
            EmbeddedSubtitleCueStore.reset(source)
            recordCues(source)
        }

        assertTrue(EmbeddedSubtitleCueStore.candidateTracks(first, "en").isEmpty())
        assertEquals(1, EmbeddedSubtitleCueStore.candidateTracks(second, "en").size)
        assertEquals(1, EmbeddedSubtitleCueStore.candidateTracks(third, "en").size)
    }

    @Test
    fun aStreamStartedAgainCountsAsTheNewest() {
        val first = "https://host/store-test/again-first.mkv"
        val second = "https://host/store-test/again-second.mkv"
        val third = "https://host/store-test/again-third.mkv"

        EmbeddedSubtitleCueStore.reset(first)
        EmbeddedSubtitleCueStore.reset(second)
        recordCues(second)
        EmbeddedSubtitleCueStore.reset(first)
        recordCues(first)
        EmbeddedSubtitleCueStore.reset(third)

        assertEquals(1, EmbeddedSubtitleCueStore.candidateTracks(first, "en").size)
        assertTrue(EmbeddedSubtitleCueStore.candidateTracks(second, "en").isEmpty())
    }

    private fun recordCues(source: String) {
        repeat(3) { index ->
            EmbeddedSubtitleCueStore.record(
                sourceKey = source,
                trackKey = "media3:1",
                language = "en",
                label = null,
                selectionFlags = 0,
                roleFlags = 0,
                cue = SubtitleSyncCue(
                    startTimeMs = index * 2_000L,
                    endTimeMs = index * 2_000L + 1_000L,
                    text = "Line $index",
                ),
            )
        }
    }
}
