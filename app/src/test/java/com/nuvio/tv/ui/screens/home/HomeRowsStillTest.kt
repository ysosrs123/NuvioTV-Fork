package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeRowsStillTest {
    @Test fun settledWorkWaitsUntilTheRowsStopSliding() = runTest {
        val scrolling = MutableStateFlow(true)
        var ran = false
        launch { awaitRowsStill(scrolling); ran = true }
        runCurrent()
        assertFalse(ran)
        scrolling.value = false
        runCurrent()
        assertTrue(ran)
    }

    @Test fun settledWorkRunsAtOnceWhenTheRowsAreStill() = runTest {
        val scrolling = MutableStateFlow(false)
        var ran = false
        launch { awaitRowsStill(scrolling); ran = true }
        runCurrent()
        assertTrue(ran)
    }

    @Test fun cardThatLostFocusWhileTheRowsSlidSkipsItsWork() = runTest {
        val scrolling = MutableStateFlow(true)
        var focused = true
        var result: Boolean? = null
        launch { result = stillFocusedOnceRowsStill(scrolling) { focused } }
        runCurrent()
        assertNull(result)
        focused = false
        scrolling.value = false
        runCurrent()
        assertEquals(false, result)
    }

    @Test fun cardStillFocusedRunsItsWork() = runTest {
        val scrolling = MutableStateFlow(true)
        var result: Boolean? = null
        launch { result = stillFocusedOnceRowsStill(scrolling) { true } }
        runCurrent()
        scrolling.value = false
        runCurrent()
        assertEquals(true, result)
    }

    @Test fun waitsOnTheRowsScrollStateAsTheCardsReadIt() = runTest {
        val scrollInProgress = mutableStateOf(true)
        val rowsScrolling = derivedStateOf { scrollInProgress.value }
        var result: Boolean? = null
        launch { result = stillFocusedOnceRowsStill(snapshotFlow { rowsScrolling.value }) { true } }
        runCurrent()
        assertNull(result)
        Snapshot.withMutableSnapshot { scrollInProgress.value = false }
        runCurrent()
        assertEquals(true, result)
    }

    @Test fun cardBeingLeftWaitsOutAPressWhileTheRowsStandStill() = runTest {
        val scrollInProgress = mutableStateOf(false)
        val move = VerticalMoveInFlight()
        val rowsMoving = derivedStateOf { scrollInProgress.value || move.active.value }
        var oldCardFocused = true
        var result: Boolean? = null
        var token = 0
        Snapshot.withMutableSnapshot { token = move.begin() }
        launch { result = stillFocusedOnceRowsStill(snapshotFlow { rowsMoving.value }) { oldCardFocused } }
        runCurrent()
        assertNull(result)
        oldCardFocused = false
        Snapshot.withMutableSnapshot { move.end(token) }
        runCurrent()
        assertEquals(false, result)
    }

    @Test fun rowsCountAsMovingWhileAPressIsOnItsWay() {
        val move = VerticalMoveInFlight()
        assertFalse(homeRowsMoving(listScrolling = false, move = move))
        assertTrue(homeRowsMoving(listScrolling = true, move = move))
        val token = move.begin()
        assertTrue(homeRowsMoving(listScrolling = false, move = move))
        move.end(token)
        assertFalse(homeRowsMoving(listScrolling = false, move = move))
    }

    @Test fun clearingEndsAPressThatNeverFinished() {
        val move = VerticalMoveInFlight()
        val token = move.begin()
        move.clear()
        assertFalse(move.active.value)
        move.end(token)
        assertFalse(move.active.value)
    }

    @Test fun cancelledPressDoesNotEndTheNextOne() {
        val move = VerticalMoveInFlight()
        val first = move.begin()
        val second = move.begin()
        move.end(first)
        assertTrue(move.active.value)
        move.end(second)
        assertFalse(move.active.value)
    }

    @Test fun cardWithinAPixelOfItsPlaceStays() {
        assertEquals(0f, verticalRowScrollDistance(offset = 40.6f, insetPx = 40f, canScrollBackward = true), 0.001f)
    }

    @Test fun rowAboveTheTopScrollsBackDown() {
        assertEquals(-340f, verticalRowScrollDistance(offset = -300f, insetPx = 40f, canScrollBackward = true), 0.001f)
    }

    @Test fun rowBelowItsPlaceScrollsUp() {
        assertEquals(298f, verticalRowScrollDistance(offset = 338f, insetPx = 40f, canScrollBackward = true), 0.001f)
    }

    @Test fun firstRowIsNotPulledPastItsStart() {
        assertEquals(0f, verticalRowScrollDistance(offset = 10f, insetPx = 40f, canScrollBackward = false), 0.001f)
    }
}
