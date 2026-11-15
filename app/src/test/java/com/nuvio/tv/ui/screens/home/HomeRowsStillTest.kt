package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
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
}
