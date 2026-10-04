package com.nuvio.tv.core.player

import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test

class TrailerPlayerPoolOwnershipTest {
    private fun pool(player: ExoPlayer): TrailerPlayerPool {
        val settings = mockk<PlayerSettingsDataStore> {
            every { nuvioPerformanceModeEnabled } returns flowOf(false)
        }
        return TrailerPlayerPool(mockk(relaxed = true), settings).also {
            TrailerPlayerPool::class.java.getDeclaredField("_player").apply { isAccessible = true }.set(it, player)
        }
    }

    @Test fun `outgoing screen disposal cannot stop the incoming preview`() {
        val player = mockk<ExoPlayer>(relaxed = true)
        val pool = pool(player)
        val outgoing = Any(); val incoming = Any()
        assertSame(player, pool.acquire(outgoing))
        assertSame(player, pool.acquire(incoming))
        pool.stop(outgoing)
        verify(exactly = 0) { player.stop(); player.clearMediaItems() }
        assertTrue(pool.isOwner(incoming))
        pool.stop(incoming)
        verify(exactly = 1) { player.stop(); player.clearMediaItems() }
        assertFalse(pool.isOwner(incoming))
    }

    @Test fun `screensaver suppression refuses a new owner`() {
        val player = mockk<ExoPlayer>(relaxed = true)
        val pool = pool(player)
        pool.setScreensaverSuppressed(true)
        val owner = Any()
        assertNull(pool.acquire(owner))
        assertFalse(pool.isOwner(owner))
        pool.setScreensaverSuppressed(false)
        assertSame(player, pool.acquire(owner))
    }

    @Test fun `yield invalidates old screen cleanup`() {
        val player = mockk<ExoPlayer>(relaxed = true)
        val pool = pool(player)
        val owner = Any()
        pool.acquire(owner)
        pool.yield()
        assertFalse(pool.isOwner(owner))
        pool.stop(owner)
        verify(exactly = 1) { player.release(); player.stop() }
    }
}
