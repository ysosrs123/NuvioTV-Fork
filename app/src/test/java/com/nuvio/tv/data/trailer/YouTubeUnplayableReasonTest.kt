package com.nuvio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeUnplayableReasonTest {

    @Test
    fun `playable and unknown statuses give no reason`() {
        assertNull(youTubeUnplayableReasonOf("OK", null))
        assertNull(youTubeUnplayableReasonOf(null, null))
        assertNull(youTubeUnplayableReasonOf("LIVE_STREAM_OFFLINE", "This live event will begin in a few moments."))
    }

    @Test
    fun `age restriction is recognised in every form YouTube uses`() {
        assertEquals(
            YouTubeUnplayableReason.AGE_RESTRICTED,
            youTubeUnplayableReasonOf("LOGIN_REQUIRED", "Sign in to confirm your age")
        )
        assertEquals(
            YouTubeUnplayableReason.AGE_RESTRICTED,
            youTubeUnplayableReasonOf("AGE_VERIFICATION_REQUIRED", null)
        )
        assertEquals(
            YouTubeUnplayableReason.AGE_RESTRICTED,
            youTubeUnplayableReasonOf("UNPLAYABLE", "This video may be inappropriate for some users.")
        )
    }

    @Test
    fun `removed, private and blocked videos are unavailable`() {
        assertEquals(YouTubeUnplayableReason.UNAVAILABLE, youTubeUnplayableReasonOf("ERROR", "Video unavailable"))
        assertEquals(
            YouTubeUnplayableReason.UNAVAILABLE,
            youTubeUnplayableReasonOf("LOGIN_REQUIRED", "This video is private")
        )
        assertEquals(
            YouTubeUnplayableReason.UNAVAILABLE,
            youTubeUnplayableReasonOf("UNPLAYABLE", "The uploader has not made this video available in your country")
        )
        assertEquals(
            YouTubeUnplayableReason.UNAVAILABLE,
            youTubeUnplayableReasonOf("ERROR", "This page is not available in your language")
        )
    }

    @Test
    fun `any other sign-in request is a sign-in request`() {
        assertEquals(
            YouTubeUnplayableReason.SIGN_IN_REQUIRED,
            youTubeUnplayableReasonOf("LOGIN_REQUIRED", "Sign in to confirm you're not a bot")
        )
        assertEquals(YouTubeUnplayableReason.SIGN_IN_REQUIRED, youTubeUnplayableReasonOf("LOGIN_REQUIRED", null))
    }

    @Test
    fun `only a sign-in request is worth a second attempt`() {
        assertTrue(YouTubeUnplayableReason.AGE_RESTRICTED.isDefinite)
        assertTrue(YouTubeUnplayableReason.UNAVAILABLE.isDefinite)
        assertFalse(YouTubeUnplayableReason.SIGN_IN_REQUIRED.isDefinite)
    }

    @Test
    fun `the answers of several clients become one reason`() {
        assertNull(combineYouTubeUnplayableReasons(emptyList()))
        assertEquals(
            YouTubeUnplayableReason.UNAVAILABLE,
            combineYouTubeUnplayableReasons(
                listOf(YouTubeUnplayableReason.UNAVAILABLE, YouTubeUnplayableReason.UNAVAILABLE)
            )
        )
        assertEquals(
            YouTubeUnplayableReason.AGE_RESTRICTED,
            combineYouTubeUnplayableReasons(
                listOf(YouTubeUnplayableReason.UNAVAILABLE, YouTubeUnplayableReason.AGE_RESTRICTED)
            )
        )
        assertEquals(
            YouTubeUnplayableReason.SIGN_IN_REQUIRED,
            combineYouTubeUnplayableReasons(
                listOf(YouTubeUnplayableReason.AGE_RESTRICTED, YouTubeUnplayableReason.SIGN_IN_REQUIRED)
            )
        )
    }
}
