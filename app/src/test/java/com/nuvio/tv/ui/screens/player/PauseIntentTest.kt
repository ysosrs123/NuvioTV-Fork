package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseIntentTest {

    @Test
    fun `the thumbnails hold keeps the player paused but is not the user's pause`() {
        val p = PauseIntent()
        p.holdForThumbnails()
        assertTrue(p.paused)
        assertFalse(p.pausedByUser)
    }

    @Test
    fun `playing again after the thumbnails ends the hold and the pause flag`() {
        val p = PauseIntent()
        p.holdForThumbnails()
        p.onPlayWhenReady(false)
        assertTrue(p.paused)
        p.onPlayWhenReady(true)
        assertFalse(p.paused)
        assertFalse(p.heldForThumbnails)
    }

    @Test
    fun `an error between the let-go and the first frame rebuilds a playing player`() {
        val p = PauseIntent()
        p.holdForThumbnails()
        // The player is asked to play, then fails before it ever reports playing.
        p.onPlayWhenReady(true)
        assertFalse(p.pausedByUser)
    }

    @Test
    fun `an error while still held rebuilds a playing player`() {
        val p = PauseIntent()
        p.holdForThumbnails()
        assertFalse(p.pausedByUser)
    }

    @Test
    fun `a user pause stays a user pause through play requests the user did not make`() {
        val p = PauseIntent()
        p.paused = true
        p.onPlayWhenReady(true)
        assertTrue(p.pausedByUser)
    }

    @Test
    fun `the user's own pause or play during the hold is the user's from then on`() {
        val p = PauseIntent()
        p.holdForThumbnails()
        p.paused = true
        assertTrue(p.pausedByUser)
        p.holdForThumbnails()
        p.paused = false
        assertFalse(p.paused)
        assertFalse(p.heldForThumbnails)
    }

    @Test
    fun `a paused start is the user's`() {
        val p = PauseIntent()
        p.holdForThumbnails()
        p.paused = false
        p.paused = true
        assertTrue(p.pausedByUser)
    }
}
