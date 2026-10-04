package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioRejectionRetryBeforeStashTest {
    private val route = "type:hdmi|name:avr"

    @Test
    fun `nothing is stashed while a same-configuration retry is scheduled`() {
        assertNull(
            audioRejectionToStash(
                errorCode = PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                retryScheduled = true,
                failingMime = MimeTypes.AUDIO_DTS_HD,
                routeKey = route
            )
        )
    }

    @Test
    fun `a refusal with no retry left is stashed under its route and group`() {
        assertEquals(
            "$route::DTS_HD",
            audioRejectionToStash(
                errorCode = PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                retryScheduled = false,
                failingMime = MimeTypes.AUDIO_DTS_HD,
                routeKey = route
            )
        )
    }

    @Test
    fun `only a failed open of a deniable bitstream on a known route is stashed`() {
        assertNull(
            audioRejectionToStash(
                errorCode = PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
                retryScheduled = false,
                failingMime = MimeTypes.AUDIO_TRUEHD,
                routeKey = route
            )
        )
        assertNull(
            audioRejectionToStash(
                errorCode = PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                retryScheduled = false,
                failingMime = MimeTypes.AUDIO_RAW,
                routeKey = route
            )
        )
        assertNull(
            audioRejectionToStash(
                errorCode = PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                retryScheduled = false,
                failingMime = MimeTypes.AUDIO_TRUEHD,
                routeKey = null
            )
        )
    }

    @Test
    fun `a cured retry leaves the ledger empty and a spent one is recorded once the fallback opens`() {
        val ledger = AudioRejectionLedger()
        val url = "https://example.invalid/film.mkv"

        audioRejectionToStash(
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, true, MimeTypes.AUDIO_TRUEHD, route
        )?.let { ledger.stashPending(url, it) }
        assertNull(ledger.takePendingFor(url))

        audioRejectionToStash(
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, false, MimeTypes.AUDIO_TRUEHD, route
        )?.let { ledger.stashPending(url, it) }
        assertEquals("$route::TRUEHD", ledger.takePendingFor(url))
        assertNull(ledger.takePendingFor(url))
    }

    @Test
    fun `retries of one refused format still end in a learned refusal`() {
        val ledger = AudioRejectionLedger()
        val url = "https://example.invalid/film.mkv"
        for (retry in listOf(true, true, false)) {
            ledger.noteOpenRefused(route, com.nuvio.tv.core.player.AudioPassthroughPolicy.Group.DTS)
            audioRejectionToStash(
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, retry, MimeTypes.AUDIO_DTS, route
            )?.let { ledger.stashPending(url, it) }
        }
        val entry = ledger.takePendingFor(url)
        assertEquals("$route::DTS", entry)
        assertEquals(true, ledger.commit(entry!!).learn)
    }

    @Test
    fun `an ARC link refusing TrueHD and then DTS-HD after their retries learns both`() {
        val ledger = AudioRejectionLedger()
        val url = "https://example.invalid/film.mkv"
        val refusals = listOf(
            com.nuvio.tv.core.player.AudioPassthroughPolicy.Group.TRUEHD to MimeTypes.AUDIO_TRUEHD,
            com.nuvio.tv.core.player.AudioPassthroughPolicy.Group.DTS_HD to MimeTypes.AUDIO_DTS_HD
        )
        for ((group, mime) in refusals) {
            for (retry in listOf(true, true, false)) {
                ledger.noteOpenRefused(route, group)
                audioRejectionToStash(
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, retry, mime, route
                )?.let { ledger.stashPending(url, it) }
            }
            assertEquals(true, ledger.commit(ledger.takePendingFor(url)!!).learn)
        }
    }
}
