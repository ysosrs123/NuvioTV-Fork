package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceErrorAtEndTest {

    private val episodeMs = 3_225_856L

    @Test
    fun `expired link on the last bytes counts as the end`() {
        assertTrue(
            isSourceErrorAtEnd(
                errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                positionMs = 3_225_855L,
                durationMs = episodeMs,
                isLive = false
            )
        )
        assertTrue(
            isSourceErrorAtEnd(
                errorCode = PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                positionMs = episodeMs - 2_000L,
                durationMs = episodeMs,
                isLive = false
            )
        )
    }

    @Test
    fun `error earlier in the file is a real error`() {
        assertFalse(
            isSourceErrorAtEnd(
                errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                positionMs = episodeMs - 2_001L,
                durationMs = episodeMs,
                isLive = false
            )
        )
    }

    @Test
    fun `decoder and audio errors near the end stay errors`() {
        assertFalse(
            isSourceErrorAtEnd(
                errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
                positionMs = episodeMs - 100L,
                durationMs = episodeMs,
                isLive = false
            )
        )
        assertFalse(
            isSourceErrorAtEnd(
                errorCode = PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
                positionMs = episodeMs - 100L,
                durationMs = episodeMs,
                isLive = false
            )
        )
    }

    @Test
    fun `live or unknown duration never counts as the end`() {
        assertFalse(
            isSourceErrorAtEnd(
                errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                positionMs = episodeMs,
                durationMs = episodeMs,
                isLive = true
            )
        )
        assertFalse(
            isSourceErrorAtEnd(
                errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                positionMs = 0L,
                durationMs = C.TIME_UNSET,
                isLive = false
            )
        )
        assertFalse(
            isSourceErrorAtEnd(
                errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                positionMs = 0L,
                durationMs = 0L,
                isLive = false
            )
        )
    }
}
