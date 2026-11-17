package com.nuvio.tv.ui.components

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class UnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) {}
    override fun insertBottomUp(index: Int, instance: Unit) {}
    override fun remove(index: Int, count: Int) {}
    override fun move(from: Int, to: Int, count: Int) {}
    override fun onClear() {}
}

private const val FRAME_MS = 16L

class CardFocusDrawTest {
    // The old card: border = if (focused || pressed) focusedBorder else None, a transparent border while focused
    // and fast scrolling; glow = if (focused && !pressed) focusedGlow else None, and None while fast scrolling.
    @Test fun `border and glow show exactly when the old card showed them`() {
        // focused, pressed, hidden -> border, glow
        val table = listOf(
            listOf(false, false, false) to (false to false),
            listOf(false, false, true) to (false to false),
            listOf(false, true, false) to (true to false),
            listOf(false, true, true) to (true to false),
            listOf(true, false, false) to (true to true),
            listOf(true, false, true) to (false to false),
            listOf(true, true, false) to (true to false),
            listOf(true, true, true) to (false to false)
        )
        for ((input, expected) in table) {
            val (focused, pressed, hidden) = input
            assertEquals("border $input", expected.first, cardFocusBorderShows(focused, pressed, hidden))
            assertEquals("glow $input", expected.second, cardFocusGlowShows(focused, pressed, hidden))
        }
    }

    private class Host { var runs = 0; var dim: State<Float>? = null }

    @Composable
    private fun DimHost(focused: State<Boolean>, host: Host) {
        host.runs++
        CompositionLocalProvider(LocalDimWatchedPosters provides true) {
            host.dim = watchedPosterDim(isWatched = true, focused = focused)
        }
    }

    private suspend fun CoroutineScope.withComposition(
        content: @Composable () -> Unit,
        block: suspend (settle: suspend (Long) -> Unit) -> Unit
    ) {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            val recomposer = Recomposer(coroutineContext)
            val runner = launch { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(UnitApplier(), recomposer)
            composition.setContent(content)
            // Frame times come from this counter, not the wall clock, so animation progress is exact.
            var frameNanos = 0L
            block { ms ->
                repeat((ms / FRAME_MS).toInt()) {
                    Snapshot.sendApplyNotifications()
                    yield()
                    frameNanos += FRAME_MS * 1_000_000
                    clock.sendFrame(frameNanos)
                    yield()
                }
            }
            composition.dispose()
            recomposer.cancel()
            runner.cancel()
        }
    }

    @Test fun `watched dim follows focus without recomposing the card`() = runBlocking {
        val focused = mutableStateOf(false)
        val host = Host()
        withComposition({ DimHost(focused, host) }) { settle ->
            settle(100)
            assertEquals(1f, host.dim!!.value, 0f)
            val runs = host.runs

            focused.value = true
            settle(400)
            assertEquals(0f, host.dim!!.value, 0f)

            focused.value = false
            settle(80)
            val midway = host.dim!!.value
            assertTrue("animates, not snaps: $midway", midway > 0.1f && midway < 0.9f)
            settle(400)
            assertEquals(1f, host.dim!!.value, 0f)

            assertEquals(runs, host.runs)
        }
    }

    @Test fun `a focused card starts focused`() = runBlocking {
        val focused = mutableStateOf(true)
        val host = Host()
        withComposition({ DimHost(focused, host) }) { settle ->
            settle(50)
            assertEquals(0f, host.dim!!.value, 0f)
        }
    }

    @Test fun `unwatched posters get no animation`() = runBlocking {
        val focused = mutableStateOf(false)
        var dim: State<Float>? = null
        withComposition({
            CompositionLocalProvider(LocalDimWatchedPosters provides true) {
                dim = watchedPosterDim(isWatched = false, focused = focused)
            }
        }) { settle ->
            settle(50)
            focused.value = true
            settle(50)
            assertEquals(0f, dim!!.value, 0f)
            assertFalse(dim is androidx.compose.runtime.MutableState<*>)
        }
    }
}
