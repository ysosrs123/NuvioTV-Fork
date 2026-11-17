package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.IntState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

private class NoApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) {}
    override fun insertBottomUp(index: Int, instance: Unit) {}
    override fun remove(index: Int, count: Int) {}
    override fun move(from: Int, to: Int, count: Int) {}
    override fun onClear() {}
}

class FocusedCardWorkTest {
    private class Item { var runs = 0; var started = 0; var cancelled = 0 }

    @Composable
    private fun CardItem(focused: State<Boolean>, eventId: IntState, item: Item) {
        item.runs++
        FocusedCardWork(focused, eventId, "key") {
            item.started++
            try {
                awaitCancellation()
            } finally {
                item.cancelled++
            }
        }
    }

    @Test fun `focus work runs once per focus and stops when focus leaves, without recomposing the item`() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            val recomposer = Recomposer(coroutineContext)
            val runner = launch { recomposer.runRecomposeAndApplyChanges() }
            val focused = mutableStateOf(false)
            val eventId = mutableIntStateOf(0)
            val item = Item()
            val composition = Composition(NoApplier(), recomposer)
            composition.setContent { CardItem(focused, eventId, item) }
            suspend fun settle() {
                repeat(10) {
                    Snapshot.sendApplyNotifications()
                    clock.sendFrame(System.nanoTime())
                    delay(5)
                }
            }
            settle()
            val runs = item.runs
            assertEquals(0, item.started)

            focused.value = true
            eventId.intValue++
            settle()
            assertEquals(1, item.started)

            focused.value = false
            settle()
            assertEquals(1, item.cancelled)

            focused.value = true
            eventId.intValue++
            settle()
            assertEquals(2, item.started)

            assertEquals(runs, item.runs)
            composition.dispose()
            recomposer.cancel()
            runner.cancel()
        }
    }
}
