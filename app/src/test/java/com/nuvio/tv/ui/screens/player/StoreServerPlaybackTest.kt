package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoreServerPlaybackTest {
    private val mb = 1024L * 1024L

    private fun policy(piece: Long, budget: Long) = AutoSpeedupPolicy(8, piece, false).apply {
        pieceBudgetBytes = budget
        preferredPieceBytes = 16L * mb
    }

    @Test
    fun `a store server's film starts on the preferred piece where it fits`() {
        assertEquals(16L * mb, policy(8L * mb, 256L * mb).rememberedStartPiece(4))
    }

    @Test
    fun `memory still decides when the preferred piece does not fit`() {
        assertEquals(12L * mb, policy(8L * mb, 64L * mb).rememberedStartPiece(4))
        assertEquals(8L * mb, policy(8L * mb, 16L * mb).rememberedStartPiece(4))
    }

    @Test
    fun `a bigger piece is never made smaller`() {
        assertEquals(24L * mb, policy(24L * mb, 256L * mb).rememberedStartPiece(4))
    }

    @Test
    fun `without a preference the piece stays`() {
        val p = AutoSpeedupPolicy(8, 8L * mb, false).apply { pieceBudgetBytes = 256L * mb }
        assertEquals(8L * mb, p.rememberedStartPiece(4))
    }

    @Test
    fun `only a 429 naming a long wait is a long refusal`() {
        assertEquals(300_000L, longRefusalMs(429, "300"))
        assertEquals(60_000L, longRefusalMs(429, "60"))
        assertNull(longRefusalMs(429, "30"))
        assertNull(longRefusalMs(429, null))
        assertNull(longRefusalMs(503, "300"))
    }
}
