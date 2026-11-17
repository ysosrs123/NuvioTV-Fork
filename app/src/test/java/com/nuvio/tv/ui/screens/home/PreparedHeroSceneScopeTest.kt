package com.nuvio.tv.ui.screens.home

import android.content.Context
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class UnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) {}
    override fun insertBottomUp(index: Int, instance: Unit) {}
    override fun remove(index: Int, count: Int) {}
    override fun move(from: Int, to: Int, count: Int) {}
    override fun onClear() {}
}

class PreparedHeroSceneScopeTest {
    private fun scene(owner: String) = ModernHeroSceneState(
        artwork = heroArtworkSelection(owner),
        heroBackdrop = null,
        preview = null,
        enrichmentActive = false,
        shouldPlayTrailer = false,
        trailerFirstFrameRendered = false,
        trailerUrl = null,
        trailerAudioUrl = null,
        trailerPlaybackKey = null,
        trailerMuted = true,
        fullScreenBackdrop = false
    )

    private class Caller { var runs = 0; var prepared: State<ModernHeroSceneState>? = null }

    @Composable
    private fun HeroCaller(requested: State<ModernHeroSceneState>, caller: Caller) {
        caller.runs++
        caller.prepared = rememberPreparedHeroScene(requested, 1920, 1080)
    }

    @Test fun `title steps do not rebuild the hero that asks for the scene`() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            val recomposer = Recomposer(coroutineContext)
            val runner = launch { recomposer.runRecomposeAndApplyChanges() }
            val requested = mutableStateOf(scene("first"))
            val caller = Caller()
            val composition = Composition(UnitApplier(), recomposer)
            composition.setContent {
                CompositionLocalProvider(LocalContext provides mockk<Context>(relaxed = true)) {
                    HeroCaller(requested, caller)
                }
            }
            suspend fun settle(ms: Long) {
                val end = System.currentTimeMillis() + ms
                while (System.currentTimeMillis() < end) {
                    Snapshot.sendApplyNotifications()
                    clock.sendFrame(System.nanoTime())
                    delay(10)
                }
            }
            settle(700)
            assertEquals("first", caller.prepared!!.value.artwork.ownerKey)
            val runsBefore = caller.runs

            for (owner in listOf("second", "third", "fourth")) {
                requested.value = scene(owner)
                settle(700)
                assertEquals(owner, caller.prepared!!.value.artwork.ownerKey)
            }

            assertEquals(runsBefore, caller.runs)
            assertTrue(runsBefore >= 1)
            composition.dispose()
            recomposer.cancel()
            runner.cancel()
        }
    }
}
