package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.SeekingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekSettleTest {

    private val duration = 7_200_000L

    @Test
    fun quickTaps_addUpFromThePendingTarget() {
        val settle = SeekSettle()
        var pending: Long? = null
        val playback = 600_000L
        repeat(4) {
            settle.press(playback)
            pending = SeekSettle.target(pending, playback, 10_000L, duration)
        }
        assertEquals(640_000L, pending)
        assertEquals(4, settle.presses)
        assertEquals(playback, settle.fromMs)
    }

    @Test
    fun withoutAPendingTarget_theStepStartsWherePlaybackIs() {
        assertEquals(590_000L, SeekSettle.target(null, 600_000L, -10_000L, duration))
    }

    @Test
    fun theRunKeepsWherePlaybackStoodAtTheFirstPress() {
        val settle = SeekSettle()
        settle.press(600_000L)
        settle.press(600_400L)
        assertEquals(600_000L, settle.fromMs)
    }

    @Test
    fun backAndForth_cancelOut() {
        var pending: Long? = null
        val playback = 600_000L
        for (delta in listOf(10_000L, 10_000L, -10_000L, -10_000L)) {
            pending = SeekSettle.target(pending, playback, delta, duration)
        }
        assertEquals(playback, pending)
    }

    @Test
    fun target_staysInsideTheFilm() {
        assertEquals(0L, SeekSettle.target(5_000L, 600_000L, -10_000L, duration))
        assertEquals(duration, SeekSettle.target(duration - 3_000L, 600_000L, 10_000L, duration))
        assertEquals(0L, SeekSettle.target(null, -500L, 0L, duration))
    }

    @Test
    fun target_followsTheThumbnailGridWhenThereIsOne() {
        val grid: (Long, Long) -> Long? = { base, delta -> (base / 10_000L) * 10_000L + delta }
        assertEquals(620_000L, SeekSettle.target(null, 613_400L, 10_000L, duration, grid))
        assertEquals(630_000L, SeekSettle.target(620_000L, 613_400L, 10_000L, duration, grid))
    }

    @Test
    fun end_reportsTheRunAndClosesIt() {
        val settle = SeekSettle()
        assertFalse(settle.open)
        settle.press(100_000L)
        settle.press(100_000L)
        settle.press(100_000L)
        assertTrue(settle.open)
        val run = settle.end()
        assertEquals(100_000L, run?.fromMs)
        assertEquals(3, run?.presses)
        assertFalse(settle.open)
        assertEquals(0, settle.presses)
        assertNull(settle.end())
    }

    @Test
    fun aBacklogPress_isTakenBackFromTheCount() {
        val settle = SeekSettle()
        settle.press(100_000L)
        settle.press(100_000L)
        settle.undoPress()
        assertEquals(1, settle.presses)
        settle.undoPress()
        settle.undoPress()
        assertEquals(0, settle.presses)
        assertTrue(settle.open)
    }

    @Test
    fun aNewRun_startsFresh() {
        val settle = SeekSettle()
        settle.press(100_000L)
        settle.end()
        settle.press(250_000L)
        assertEquals(250_000L, settle.fromMs)
        assertEquals(1, settle.presses)
    }

    @Test
    fun aTapWaitsLessThanASecond_aHoldTwoSeconds() {
        assertEquals(800L, SeekSettle.waitMs(afterHold = false))
        assertEquals(2_000L, SeekSettle.waitMs(afterHold = true))
    }

    @Test
    fun theWaitOnlyComesWhileThumbnailsRun() {
        assertTrue(SeekSettle.applies(open = false, userPaused = false, inParty = false, playing = true, thumbnails = true))
        assertFalse(SeekSettle.applies(open = false, userPaused = false, inParty = false, playing = true, thumbnails = false))
    }

    @Test
    fun pausedOrInAParty_theSeekGoesOnRelease() {
        assertFalse(SeekSettle.applies(open = false, userPaused = true, inParty = false, playing = true, thumbnails = true))
        assertFalse(SeekSettle.applies(open = false, userPaused = false, inParty = true, playing = true, thumbnails = true))
        assertFalse(SeekSettle.applies(open = false, userPaused = false, inParty = false, playing = false, thumbnails = true))
    }

    @Test
    fun anOpenRun_goesOnEvenWhenThumbnailsStop() {
        assertTrue(SeekSettle.applies(open = true, userPaused = false, inParty = false, playing = true, thumbnails = false))
    }

    @Test
    fun eachChoiceWaitsItsOwnTime() {
        assertEquals(2_000L, SeekSettle.waitMs(afterHold = false, mode = SeekingMode.GENTLE))
        assertEquals(2_000L, SeekSettle.waitMs(afterHold = true, mode = SeekingMode.GENTLE))
        assertEquals(800L, SeekSettle.waitMs(afterHold = false, mode = SeekingMode.BALANCED))
        assertEquals(2_000L, SeekSettle.waitMs(afterHold = true, mode = SeekingMode.BALANCED))
        assertEquals(0L, SeekSettle.waitMs(afterHold = false, mode = SeekingMode.FAST))
        assertEquals(0L, SeekSettle.waitMs(afterHold = true, mode = SeekingMode.FAST))
    }

    @Test
    fun fast_seeksOnReleaseLikeTheOfficialApp() {
        assertFalse(SeekSettle.applies(open = false, userPaused = false, inParty = false, playing = true,
            thumbnails = true, mode = SeekingMode.FAST))
        assertTrue(SeekSettle.applies(open = false, userPaused = false, inParty = false, playing = true,
            thumbnails = true, mode = SeekingMode.GENTLE))
    }

    @Test
    fun confirm_waitsForOkPausedOrPlayingWithOrWithoutThumbnails() {
        for (paused in listOf(false, true)) for (playing in listOf(false, true)) for (thumbs in listOf(false, true)) {
            assertTrue(SeekSettle.applies(open = false, userPaused = paused, inParty = false, playing = playing,
                thumbnails = thumbs, mode = SeekingMode.CONFIRM))
        }
        assertTrue(SeekSettle.waitsWhilePaused(SeekingMode.CONFIRM))
        assertFalse(SeekSettle.waitsWhilePaused(SeekingMode.BALANCED))
    }

    @Test
    fun confirm_inAPartyStillSeeksOnRelease() {
        assertFalse(SeekSettle.applies(open = false, userPaused = false, inParty = true, playing = true,
            thumbnails = true, mode = SeekingMode.CONFIRM))
    }

    @Test
    fun confirm_aForgottenRunEndsWithoutASeek() {
        assertEquals(SeekSettle.CONFIRM_IDLE_MS, SeekSettle.waitMs(afterHold = false, mode = SeekingMode.CONFIRM))
        assertEquals(SeekSettle.CONFIRM_IDLE_MS, SeekSettle.waitMs(afterHold = true, mode = SeekingMode.CONFIRM))
        assertFalse(SeekSettle.seeksAfterWait(SeekingMode.CONFIRM))
        for (mode in listOf(SeekingMode.GENTLE, SeekingMode.BALANCED, SeekingMode.FAST)) {
            assertTrue(SeekSettle.seeksAfterWait(mode))
        }
    }

    @Test
    fun confirm_aForgottenRunWhilePlayingEndsSooner() {
        assertEquals(SeekSettle.CONFIRM_PLAYING_IDLE_MS, SeekSettle.waitMs(afterHold = false, mode = SeekingMode.CONFIRM, playing = true))
        assertEquals(SeekSettle.CONFIRM_PLAYING_IDLE_MS, SeekSettle.waitMs(afterHold = true, mode = SeekingMode.CONFIRM, playing = true))
        assertEquals(SeekSettle.CONFIRM_IDLE_MS, SeekSettle.waitMs(afterHold = false, mode = SeekingMode.CONFIRM, playing = false))
        assertEquals(5_000L, SeekSettle.CONFIRM_PLAYING_IDLE_MS)
    }

    @Test
    fun otherModesWaitTheSameWhetherPlayingOrNot() {
        for (mode in listOf(SeekingMode.GENTLE, SeekingMode.BALANCED, SeekingMode.FAST)) {
            for (afterHold in listOf(false, true)) {
                assertEquals(
                    SeekSettle.waitMs(afterHold, mode, playing = false),
                    SeekSettle.waitMs(afterHold, mode, playing = true)
                )
            }
        }
    }

    @Test
    fun confirm_keepsTheFilmPlayingWhileTheMarkerMoves() {
        assertFalse(SeekSettle.holdsDuringWait(SeekingMode.CONFIRM))
        for (mode in listOf(SeekingMode.GENTLE, SeekingMode.BALANCED, SeekingMode.FAST)) {
            assertTrue(SeekSettle.holdsDuringWait(mode))
        }
    }
}
