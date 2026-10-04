package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SeekStripLayoutTest {

    private val base = 30_000L

    private fun screenStrides(anchorMs: Double, i: Long, glideMs: Double, spacingMs: Double) =
        (anchorMs + i * spacingMs - glideMs) / spacingMs

    @Test
    fun multiple_followsTheHoldRamp() {
        assertEquals(1L, SeekStripLayout.multiple(10_000L, 10_000L, base))
        assertEquals(3L, SeekStripLayout.multiple(30_000L, 30_000L, base))
        assertEquals(6L, SeekStripLayout.multiple(60_000L, 60_000L, base))
        assertEquals(12L, SeekStripLayout.multiple(120_000L, 120_000L, base))
    }

    @Test
    fun multiple_oneLongStepDoesNotRespace() {
        assertEquals(1L, SeekStripLayout.multiple(20_000L, 10_000L, base))
        assertEquals(1L, SeekStripLayout.multiple(10_000L, 20_000L, base))
        assertEquals(2L, SeekStripLayout.multiple(20_000L, 20_000L, base))
    }

    @Test
    fun multiple_firstStepAndBadBase() {
        assertEquals(3L, SeekStripLayout.multiple(30_000L, 0L, base))
        assertEquals(1L, SeekStripLayout.multiple(0L, 0L, base))
        assertEquals(1L, SeekStripLayout.multiple(30_000L, 30_000L, 0L))
    }

    @Test
    fun respacedAnchor_keepsEveryTileWhereItWas() {
        val glide = 612_345.0
        for ((old, new) in listOf(30_000.0 to 90_000.0, 90_000.0 to 180_000.0, 180_000.0 to 30_000.0)) {
            val anchor = 0.0
            val respaced = SeekStripLayout.respacedAnchor(anchor, glide, old, new)
            val oldPhase = screenStrides(anchor, 0, glide, old).let { it - Math.floor(it) }
            val newPhase = screenStrides(respaced, 0, glide, new).let { it - Math.floor(it) }
            assertEquals(oldPhase, newPhase, 1e-9)
        }
    }

    @Test
    fun respacedAnchor_noTileMovesTowardTheCentre() {
        val glide = 612_345.0
        val anchor = 0.0
        val old = 30_000.0
        val new = 90_000.0
        val respaced = SeekStripLayout.respacedAnchor(anchor, glide, old, new)
        val before = SeekStripLayout.tileIndices(anchor, glide, 5 * old, old)
            .map { screenStrides(anchor, it, glide, old) }.sorted()
        val after = SeekStripLayout.tileIndices(respaced, glide, 5 * new, new)
            .map { screenStrides(respaced, it, glide, new) }.sorted()
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (b, a) -> assertEquals(b, a, 1e-9) }
    }

    @Test
    fun respacedAnchor_staysNearTheGlide() {
        val glide = 6_000_000.0
        val respaced = SeekStripLayout.respacedAnchor(0.0, glide, 30_000.0, 180_000.0)
        assertTrue(abs(respaced - glide) <= 90_000.0)
    }

    @Test
    fun respacedAnchor_sameSpacingKeepsTheTiles() {
        val respaced = SeekStripLayout.respacedAnchor(15_000.0, 600_000.0, 30_000.0, 30_000.0)
        assertEquals(0.0, Math.IEEEremainder(respaced - 15_000.0, 30_000.0), 1e-9)
    }

    @Test
    fun tileIndices_coverTheReach() {
        val range = SeekStripLayout.tileIndices(0.0, 600_000.0, 100_000.0, 30_000.0)
        assertEquals(17L, range.first)
        assertEquals(23L, range.last)
        assertTrue(SeekStripLayout.tileIndices(0.0, 600_000.0, 100_000.0, 0.0).isEmpty())
    }

    @Test
    fun pictureMs_roundsToTheStoredGrid() {
        assertEquals(600_000L, SeekStripLayout.pictureMs(600_000.0, base))
        assertEquals(600_000L, SeekStripLayout.pictureMs(614_999.0, base))
        assertEquals(630_000L, SeekStripLayout.pictureMs(615_001.0, base))
        assertEquals(0L, SeekStripLayout.pictureMs(-14_000.0, base))
    }
}
