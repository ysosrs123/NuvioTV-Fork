package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldErrorCardTest {

    private val notice = PlayerErrorCard(PlayerErrorCause.ProviderNotice, "Notice", "notice message", null)
    private val watchdogCard =
        PlayerErrorCard(PlayerErrorCause.ServerSilent, "Silent", "no data message", null, fromWatchdog = true)

    @Test
    fun `watchdog may write over an empty screen`() {
        assertTrue(PlayerUiState().startupWatchdogMayWrite())
    }

    @Test
    fun `watchdog does not write while a card waits behind the source list`() {
        assertFalse(PlayerUiState(errorCardBeforeSources = notice).startupWatchdogMayWrite())
    }

    @Test
    fun `watchdog does not write over an error on screen`() {
        assertFalse(PlayerUiState(error = notice.message, errorCard = notice).startupWatchdogMayWrite())
    }

    @Test
    fun `closing the sources brings the held card back over an empty screen`() {
        val restored = PlayerUiState(errorCardBeforeSources = notice).withHeldErrorCardRestored(errorFromWatchdog = false)
        assertEquals(notice.message, restored.error)
        assertEquals(notice, restored.errorCard)
        assertNull(restored.errorCardBeforeSources)
    }

    @Test
    fun `held card wins over a watchdog card`() {
        val state = PlayerUiState(
            error = watchdogCard.message,
            errorCard = watchdogCard,
            errorCardBeforeSources = notice
        )
        val restored = state.withHeldErrorCardRestored(errorFromWatchdog = true)
        assertEquals(notice.message, restored.error)
        assertEquals(notice, restored.errorCard)
        assertNull(restored.errorCardBeforeSources)
    }

    @Test
    fun `held card wins over a plain watchdog message`() {
        val state = PlayerUiState(error = "no data in time", errorCardBeforeSources = notice)
        val restored = state.withHeldErrorCardRestored(errorFromWatchdog = true)
        assertEquals(notice.message, restored.error)
        assertEquals(notice, restored.errorCard)
    }

    @Test
    fun `a newer real error stays and the held card is dropped`() {
        val other = PlayerErrorCard(PlayerErrorCause.NotVideo, "Other", "other message", null)
        val state = PlayerUiState(error = other.message, errorCard = other, errorCardBeforeSources = notice)
        val restored = state.withHeldErrorCardRestored(errorFromWatchdog = false)
        assertEquals(other.message, restored.error)
        assertEquals(other, restored.errorCard)
        assertNull(restored.errorCardBeforeSources)
    }

    @Test
    fun `a new stream start drops the held card so the watchdog guards the new stream`() {
        val state = PlayerUiState(errorCardBeforeSources = notice, showSourcesPanel = false)
        assertFalse(state.startupWatchdogMayWrite())
        val started = state.withHeldErrorCardDropped()
        assertNull(started.errorCardBeforeSources)
        assertTrue(started.startupWatchdogMayWrite())
    }

    @Test
    fun `after a new stream start closing the sources brings nothing back`() {
        val started = PlayerUiState(errorCardBeforeSources = notice).withHeldErrorCardDropped()
        val closed = started.withHeldErrorCardRestored(errorFromWatchdog = false)
        assertNull(closed.error)
        assertNull(closed.errorCard)
    }

    @Test
    fun `dropping with nothing held leaves the state alone`() {
        val state = PlayerUiState(error = "x")
        assertTrue(state === state.withHeldErrorCardDropped())
    }

    @Test
    fun `nothing held leaves the state alone`() {
        val state = PlayerUiState(error = "x")
        assertEquals(state, state.withHeldErrorCardRestored(errorFromWatchdog = true))
    }
}
