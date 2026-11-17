package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.TorrentErrorRecovery.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class TorrentErrorRecoveryTest {

    @Test
    fun `first error after the first frame adds the torrent again`() {
        assertEquals(
            Action.Recover,
            TorrentErrorRecovery.decide(firstFrameRendered = true, recoveryUsed = false, canRecover = true)
        )
    }

    @Test
    fun `second error moves on to the next source`() {
        assertEquals(
            Action.NextSource,
            TorrentErrorRecovery.decide(firstFrameRendered = true, recoveryUsed = true, canRecover = true)
        )
        assertEquals(
            Action.NextSource,
            TorrentErrorRecovery.decide(firstFrameRendered = false, recoveryUsed = true, canRecover = true)
        )
    }

    @Test
    fun `error before the first frame keeps the error card`() {
        assertEquals(
            Action.ShowError,
            TorrentErrorRecovery.decide(firstFrameRendered = false, recoveryUsed = false, canRecover = true)
        )
        assertEquals(
            Action.ShowError,
            TorrentErrorRecovery.decide(firstFrameRendered = false, recoveryUsed = false, canRecover = false)
        )
    }

    @Test
    fun `moves on when the torrent cannot be added again`() {
        assertEquals(
            Action.NextSource,
            TorrentErrorRecovery.decide(firstFrameRendered = true, recoveryUsed = false, canRecover = false)
        )
    }
}
