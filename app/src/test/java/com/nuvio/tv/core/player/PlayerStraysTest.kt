package com.nuvio.tv.core.player

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerStraysTest {
    @Test
    fun `playback threads are counted by name`() {
        val names = listOf("main", "ExoPlayer:Playback", "ExoPlayer:Playback", "ExoPlayer:Loader:ProgressiveMediaPeriod", "OkHttp Dispatcher")
        assertEquals(2, PlayerStrays.count(names, "ExoPlayer:Playback"))
    }

    @Test
    fun `nothing to wait for when no player is closing`() = runTest {
        assertEquals(0L, PlayerStrays.awaitGone())
    }

    @Test
    fun `the snapshot line names how many players are alive and where they wait`() {
        val blocked = listOf("android.media.MediaCodec.native_stop", "android.media.MediaCodec.stop")
        val line = PlayerStrays.describe(15, 5, List(9) { blocked } + listOf(listOf("java.lang.Object.wait")) + listOf(emptyList()))
        assertEquals(
            "Players alive: 15 playback threads, 5 video decoders; waiting in " +
                "9x android.media.MediaCodec.native_stop < android.media.MediaCodec.stop; 1x java.lang.Object.wait",
            line
        )
    }
}
