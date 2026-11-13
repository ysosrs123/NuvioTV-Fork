package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertNull(PlayerErrorCauses.forLoadFailure(listOf("java.net.SocketTimeoutException"), 1L))
        assertNull(PlayerErrorCauses.forLoadFailure(listOf("java.io.EOFException"), 0L))
        assertNull(PlayerErrorCauses.forLoadFailure(emptyList(), 0L))
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
}
