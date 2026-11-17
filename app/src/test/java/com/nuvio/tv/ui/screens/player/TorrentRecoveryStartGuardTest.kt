package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.torrent.TorrentState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TorrentRecoveryStartGuardTest {

    private fun streaming() = TorrentState.Streaming(
        localUrl = "http://127.0.0.1/stream",
        downloadSpeed = 0L,
        uploadSpeed = 0L,
        peers = 1,
        seeds = 1,
        bufferProgress = 0f,
        totalProgress = 0f
    )

    @Test
    fun `stale error is skipped when a recovery start subscribes`() = runTest(UnconfinedTestDispatcher()) {
        val states = MutableStateFlow<TorrentState>(TorrentState.Error("engine stopped"))
        val guard = TorrentRecoveryStartGuard()
        val seen = mutableListOf<TorrentState>()

        val token = guard.begin()
        val job = launch { collectTorrentStates(states, guard) { seen += it } }
        assertTrue(seen.isEmpty())

        states.value = TorrentState.Idle
        states.value = TorrentState.Connecting()
        states.value = streaming()
        guard.end(token)

        assertEquals(listOf(TorrentState.Idle, TorrentState.Connecting(), streaming()), seen)
        job.cancel()
    }

    @Test
    fun `a new error after the restart moved on is reported`() = runTest(UnconfinedTestDispatcher()) {
        val states = MutableStateFlow<TorrentState>(TorrentState.Error("engine stopped"))
        val guard = TorrentRecoveryStartGuard()
        val seen = mutableListOf<TorrentState>()

        guard.begin()
        val job = launch { collectTorrentStates(states, guard) { seen += it } }
        states.value = TorrentState.Connecting()
        states.value = TorrentState.Error("engine stopped")

        assertEquals(listOf(TorrentState.Connecting(), TorrentState.Error("engine stopped")), seen)
        job.cancel()
    }

    @Test
    fun `errors are reported when no recovery start is running`() = runTest(UnconfinedTestDispatcher()) {
        val states = MutableStateFlow<TorrentState>(TorrentState.Idle)
        val guard = TorrentRecoveryStartGuard()
        val seen = mutableListOf<TorrentState>()

        val job = launch { collectTorrentStates(states, guard) { seen += it } }
        states.value = TorrentState.Error("engine stopped")

        assertEquals(listOf(TorrentState.Idle, TorrentState.Error("engine stopped")), seen)
        job.cancel()
    }

    @Test
    fun `ending the start clears the guard even if the state never moved`() {
        val guard = TorrentRecoveryStartGuard()
        val token = guard.begin()
        assertFalse(guard.accepts(TorrentState.Error("x")))
        guard.end(token)
        assertTrue(guard.accepts(TorrentState.Error("x")))
    }

    @Test
    fun `an older start ending does not clear a newer one`() {
        val guard = TorrentRecoveryStartGuard()
        val first = guard.begin()
        guard.begin()
        guard.end(first)
        assertTrue(guard.starting)
    }
}
