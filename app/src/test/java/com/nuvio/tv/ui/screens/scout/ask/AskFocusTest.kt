package com.nuvio.tv.ui.screens.scout.ask

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskFocusTest {
    private fun spots(hasMic: Boolean, asked: Int): List<AskSpot> = buildList {
        if (hasMic) add(AskSpot.Mic)
        add(AskSpot.Field)
        add(AskSpot.ChangeAi)
        repeat(ASK_STARTERS) { add(AskSpot.Starter(it)) }
        if (asked > 0) add(AskSpot.Clear)
        repeat(asked) { add(AskSpot.Asked(it)) }
    }

    private fun row(spot: AskSpot) = when (spot) {
        AskSpot.Mic, AskSpot.Field, AskSpot.ChangeAi -> 0
        is AskSpot.Starter -> 1
        AskSpot.Clear -> 2
        is AskSpot.Asked -> 3
    }

    @Test
    fun everyPlaceCanBeReachedFromTheFirst() {
        for (hasMic in listOf(true, false)) for (asked in listOf(0, 1, 4, 12)) {
            val all = spots(hasMic, asked)
            val seen = mutableSetOf(all.first())
            var grew = true
            while (grew) {
                grew = false
                for (spot in seen.toList()) for (move in AskMove.entries) {
                    askFocusNext(spot, move, hasMic, asked)?.let { grew = seen.add(it) || grew }
                }
            }
            assertEquals("mic=$hasMic asked=$asked", all.toSet(), seen)
        }
    }

    @Test
    fun downNeverStaysOnTheSameRowOrGoesUp() {
        for (spot in spots(true, 6)) {
            val next = askFocusNext(spot, AskMove.DOWN, true, 6) ?: continue
            assertTrue("$spot went to $next", row(next) > row(spot))
        }
    }

    @Test
    fun upNeverStaysOnTheSameRowOrGoesDown() {
        for (spot in spots(true, 6)) {
            val next = askFocusNext(spot, AskMove.UP, true, 6) ?: continue
            assertTrue("$spot went to $next", row(next) < row(spot))
        }
    }

    @Test
    fun sideMovesStayOnTheirRowAndComeBack() {
        for (spot in spots(true, 6)) {
            val next = askFocusNext(spot, AskMove.END, true, 6) ?: continue
            assertEquals(row(spot), row(next))
            assertEquals(spot, askFocusNext(next, AskMove.START, true, 6))
        }
    }

    @Test
    fun noPressLeadsNowhereValid() {
        for (hasMic in listOf(true, false)) for (asked in listOf(0, 3)) {
            val all = spots(hasMic, asked).toSet()
            for (spot in all) for (move in AskMove.entries) {
                askFocusNext(spot, move, hasMic, asked)?.let { assertTrue("$spot $move gave $it", it in all) }
            }
        }
    }

    @Test
    fun edgesStayPut() {
        assertNull(askFocusNext(AskSpot.Starter(0), AskMove.START, true, 2))
        assertNull(askFocusNext(AskSpot.Starter(3), AskMove.END, true, 2))
        assertNull(askFocusNext(AskSpot.Asked(1), AskMove.END, true, 2))
        assertNull(askFocusNext(AskSpot.Starter(2), AskMove.DOWN, true, 0))
    }

    @Test
    fun focusAfterRemoval() {
        assertEquals(AskSpot.Mic, askSpotAfterRemoval(0, 0, AskSpot.Mic))
        assertEquals(AskSpot.Asked(1), askSpotAfterRemoval(2, 2, AskSpot.Mic))
        assertNull(askSpotAfterRemoval(0, 2, AskSpot.Mic))
    }

    @Test
    fun micHearsOnlyWhenTheLevelRises() {
        assertFalse(askMicHears(-2.12f, -2.12f))
        assertFalse(askMicHears(-1.5f, -2.12f))
        assertTrue(askMicHears(0f, -2.12f))
    }

    @Test
    fun onDeviceFallsBackOnlyWhenItCannotWork() {
        assertTrue(askOnDeviceGivesUp(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
        assertFalse(askOnDeviceGivesUp(SpeechRecognizer.ERROR_NO_MATCH))
    }

    @Test
    fun longWordsShowTheirEnd() {
        assertEquals("short", askWordsTail("short", 10))
        assertEquals("…three four", askWordsTail("one two three four", 12))
    }
}
