package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteadySlowSourceTest {
    private val normal = 256L * 1024L

    @Test
    fun `the normal stall rate holds until a source is known to be slow`() {
        val source = SteadySlowSource()
        assertFalse(source.known)
        assertEquals(normal, source.stallRateBps(normal))
    }

    @Test
    fun `a restart that arrives no faster marks the source as slow`() {
        val source = SteadySlowSource()
        assertTrue(source.noteAfterRestart(previousRateBps = 110_000, rateBps = 95_000))
        assertTrue(source.known)
        assertEquals(95_000L, source.rateBps)
        assertEquals(95_000L / 4, source.stallRateBps(normal))
    }

    @Test
    fun `a hung connection is not taken for a slow source`() {
        val source = SteadySlowSource()
        assertFalse(source.noteAfterRestart(previousRateBps = 0, rateBps = 120_000))
        assertFalse(source.noteAfterRestart(previousRateBps = 120_000, rateBps = 2_000))
        assertFalse(source.known)
        assertEquals(normal, source.stallRateBps(normal))
    }

    @Test
    fun `a slow source never raises the stall rate above the normal one`() {
        val source = SteadySlowSource()
        source.noteAfterRestart(previousRateBps = 240L * 1024L, rateBps = 250L * 1024L)
        assertEquals(60L * 1024L, source.stallRateBps(normal))
        assertEquals(32L * 1024L, source.stallRateBps(32L * 1024L))
    }
}
