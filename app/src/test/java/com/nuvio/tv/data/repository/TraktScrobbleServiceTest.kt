package com.nuvio.tv.data.repository

import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the dedup decision only: a stop or pause dispatched while a start for
 * the same item was still in flight must not be dropped by the dedup window.
 */
class TraktScrobbleServiceTest {

    private fun service() = TraktScrobbleService(
        traktApi = mockk(relaxed = true),
        traktAuthService = mockk(relaxed = true),
        traktProgressService = mockk(relaxed = true),
        profileManager = mockk(relaxed = true)
    )

    private fun stamp(
        action: String,
        itemKey: String = BRAVEHEART,
        progress: Float = 45.59f,
        timestampMs: Long = 1_000_000L,
        profileId: Int = 1
    ) = TraktScrobbleService.ScrobbleStamp(
        profileId = profileId,
        action = action,
        itemKey = itemKey,
        progress = progress,
        timestampMs = timestampMs
    )

    @Test
    fun `stop is not skipped while a start for the same item is still in flight`() {
        val skip = service().shouldSkipDecision(
            last = stamp(action = "stop", timestampMs = 1_000_000L),
            issued = stamp(action = "start", timestampMs = 1_001_150L),
            nowMs = 1_001_320L,
            profileId = 1,
            action = "stop",
            itemKey = BRAVEHEART,
            progress = 45.597f
        )
        assertFalse(skip)
    }

    @Test
    fun `pause is not skipped while a start for the same item is still in flight`() {
        val skip = service().shouldSkipDecision(
            last = stamp(action = "pause", timestampMs = 1_000_000L),
            issued = stamp(action = "start", timestampMs = 1_001_150L),
            nowMs = 1_001_320L,
            profileId = 1,
            action = "pause",
            itemKey = BRAVEHEART,
            progress = 45.597f
        )
        assertFalse(skip)
    }

    @Test
    fun `a genuine repeat stop inside the window is still skipped`() {
        val skip = service().shouldSkipDecision(
            last = stamp(action = "stop", timestampMs = 1_000_000L),
            issued = stamp(action = "stop", timestampMs = 1_000_000L),
            nowMs = 1_001_300L,
            profileId = 1,
            action = "stop",
            itemKey = BRAVEHEART,
            progress = 45.597f
        )
        assertTrue(skip)
    }

    @Test
    fun `a start is never rescued by the in-flight guard`() {
        val skip = service().shouldSkipDecision(
            last = stamp(action = "start", timestampMs = 1_000_000L),
            issued = stamp(action = "start", timestampMs = 1_000_000L),
            nowMs = 1_001_300L,
            profileId = 1,
            action = "start",
            itemKey = BRAVEHEART,
            progress = 45.597f
        )
        assertTrue(skip)
    }

    private companion object {
        const val BRAVEHEART = "movie:tt0112573:0"
    }
}
