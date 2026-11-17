package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.torrent.TorrentState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * What to do when the torrent engine stops a torrent with an error. A torrent that was already
 * playing is removed and added again once; a second error moves on to the next source.
 */
internal object TorrentErrorRecovery {
    enum class Action { Recover, NextSource, ShowError }

    fun decide(firstFrameRendered: Boolean, recoveryUsed: Boolean, canRecover: Boolean): Action = when {
        recoveryUsed -> Action.NextSource
        !firstFrameRendered -> Action.ShowError
        canRecover -> Action.Recover
        else -> Action.NextSource
    }
}

/**
 * While a recovery start is running, the engine still reports the old Error until the new start
 * moves it on, so that stale Error is not a second failure.
 */
internal class TorrentRecoveryStartGuard {
    private var token = 0L
    @Volatile
    private var startingToken = 0L

    @Synchronized
    fun begin(): Long {
        token += 1
        startingToken = token
        return token
    }

    @Synchronized
    fun end(startToken: Long) {
        if (startingToken == startToken) startingToken = 0L
    }

    val starting: Boolean get() = startingToken != 0L

    fun accepts(state: TorrentState): Boolean {
        if (state is TorrentState.Error) return !starting
        if (starting) synchronized(this) { startingToken = 0L }
        return true
    }
}

internal suspend fun collectTorrentStates(
    states: Flow<TorrentState>,
    guard: TorrentRecoveryStartGuard,
    onState: (TorrentState) -> Unit
) {
    states.collectLatest { state ->
        if (guard.accepts(state)) onState(state)
    }
}
