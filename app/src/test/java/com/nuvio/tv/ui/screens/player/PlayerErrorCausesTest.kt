package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerErrorCausesTest {
    @Test
    fun httpAnswersMapToTheirCause() {
        assertEquals(PlayerErrorCause.Limit, PlayerErrorCauses.forHttpStatus(429))
        assertEquals(PlayerErrorCause.ServerRefused, PlayerErrorCauses.forHttpStatus(403))
        assertEquals(PlayerErrorCause.ServerRefused, PlayerErrorCauses.forHttpStatus(404))
        assertEquals(PlayerErrorCause.ServerRefused, PlayerErrorCauses.forHttpStatus(503))
        assertEquals(PlayerErrorCause.ServerRefused, PlayerErrorCauses.forHttpStatus(451))
        assertNull(PlayerErrorCauses.forHttpStatus(400))
        assertNull(PlayerErrorCauses.forHttpStatus(416))
        assertNull(PlayerErrorCauses.forHttpStatus(405))
        assertNull(PlayerErrorCauses.forHttpStatus(200))
        assertNull(PlayerErrorCauses.forHttpStatus(302))
    }

    @Test
    fun loadFailuresNameTheServerOnlyWhenNothingArrived() {
        assertEquals(
            PlayerErrorCause.ServerUnreachable,
            PlayerErrorCauses.forLoadFailure(listOf("java.io.IOException", "java.net.UnknownHostException"), 0L)
        )
        assertEquals(PlayerErrorCause.ServerUnreachable, PlayerErrorCauses.forLoadFailure(listOf("java.net.ConnectException"), 0L))
        assertEquals(PlayerErrorCause.ServerSilent, PlayerErrorCauses.forLoadFailure(listOf("java.net.SocketTimeoutException"), 0L))
        assertEquals(PlayerErrorCause.ServerSilent, PlayerErrorCauses.forLoadFailure(listOf("java.net.SocketTimeoutException"), 25_000L))
        assertNull(PlayerErrorCauses.forLoadFailure(listOf("java.net.SocketTimeoutException"), 300_000L))
        assertNull(PlayerErrorCauses.forLoadFailure(listOf("java.net.ConnectException"), 25_000L))
        assertNull(PlayerErrorCauses.forLoadFailure(listOf("java.io.EOFException"), 0L))
        assertNull(PlayerErrorCauses.forLoadFailure(emptyList(), 0L))
    }

    @Test
    fun aSourceIsSlowOnlyWhenItDeliversLessThanTheFilmNeeds() {
        val film = PlayerErrorCauses.filmBitsPerSecond(12_000_000_000L, 120)!!
        assertEquals(13_333_333L, film)
        assertTrue(PlayerErrorCauses.isSlowSource(8_000_000L, 11_600L, film))
        assertFalse(PlayerErrorCauses.isSlowSource(40_000_000L, 11_600L, film))
        assertFalse(PlayerErrorCauses.isSlowSource(200_000L, 11_600L, film))
        assertFalse(PlayerErrorCauses.isSlowSource(8_000_000L, 3_000L, film))
        assertFalse(PlayerErrorCauses.isSlowSource(8_000_000L, 11_600L, null))
        assertNull(PlayerErrorCauses.filmBitsPerSecond(12_000_000_000L, 5))
        assertNull(PlayerErrorCauses.filmBitsPerSecond(null, 120))
    }

    @Test
    fun sizesAndRatesAreNumbersOnly() {
        assertEquals("25 KB", PlayerErrorCauses.sizeText(25_000L))
        assertEquals("8 MB", PlayerErrorCauses.sizeText(8_000_000L))
        assertEquals("1.2 GB", PlayerErrorCauses.sizeText(1_200_000_000L))
        assertEquals("5.5 Mbit/s", PlayerErrorCauses.rateText(PlayerErrorCauses.bitsPerSecond(8_000_000L, 11_600L)))
        assertEquals("13 Mbit/s", PlayerErrorCauses.rateText(13_333_333L))
    }

    @Test
    fun mpvConnectionFailuresNameTheServer() {
        assertEquals(PlayerErrorCause.ServerUnreachable, PlayerErrorCauses.forMpvLoadFailure("[ffmpeg] tcp: Connection refused"))
        assertEquals(PlayerErrorCause.ServerUnreachable, PlayerErrorCauses.forMpvLoadFailure("[ffmpeg] tls: Error in the pull function."))
        assertEquals(PlayerErrorCause.ServerUnreachable, PlayerErrorCauses.forMpvLoadFailure("[ffmpeg] Connection reset by peer"))
        assertNull(PlayerErrorCauses.forMpvLoadFailure("[ffmpeg] Invalid data found when processing input"))
        assertNull(PlayerErrorCauses.forMpvLoadFailure(null))
    }

    @Test
    fun formatSummaryUsesOnlyNumbersAndCodecNames() {
        assertEquals("7680 × 4320 · HEVC · 60 fps", PlayerErrorCauses.formatSummary(7680, 4320, "video/hevc", 60f))
        assertEquals("3840 × 2160 · AV1 · 23.98 fps", PlayerErrorCauses.formatSummary(3840, 2160, "video/av01", 23.976f))
        assertEquals("1920 × 1080", PlayerErrorCauses.formatSummary(1920, 1080, "video/unknown", -1f))
        assertEquals("Dolby Vision", PlayerErrorCauses.formatSummary(-1, -1, "video/dolby-vision", 0f))
        assertNull(PlayerErrorCauses.formatSummary(-1, -1, null, -1f))
    }

    @Test
    fun factsLineSkipsEmptyParts() {
        assertEquals("SELection · HTTP 500", PlayerErrorCauses.factsLine("SELection", null, " ", "HTTP 500"))
        assertNull(PlayerErrorCauses.factsLine(null, ""))
    }

    @Test
    fun aCardCountsOnlyWhileItsMessageIsOnScreen() {
        val card = PlayerErrorCard(PlayerErrorCause.NotVideo, "t", "m", null)
        assertEquals(card, card.matching("m"))
        assertNull(card.matching("other"))
        assertNull(card.matching(null))
        assertNull((null as PlayerErrorCard?).matching("m"))
    }

    @Test
    fun aNameLookupFailureWrappedByTheDataSourceStillNamesTheServer() {
        val wrapped = java.io.IOException(
            java.io.IOException(java.util.concurrent.ExecutionException(java.net.UnknownHostException("no address")))
        )
        val classes = PlayerErrorCauses.causeClasses(wrapped)
        assertEquals("java.net.UnknownHostException", classes.last())
        assertEquals(PlayerErrorCause.ServerUnreachable, PlayerErrorCauses.forLoadFailure(classes, 0L))
        assertNull(PlayerErrorCauses.forLoadFailure(PlayerErrorCauses.causeClasses(java.io.IOException("reset")), 0L))
    }
}
