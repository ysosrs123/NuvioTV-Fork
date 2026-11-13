package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutsideFailureStreakTest {
    @Test
    fun onlyProvenOutsideCausesBlockSend() {
        assertTrue(OutsideFailureStreak.blocksSend(PlayerErrorCause.ProviderNotice))
        assertTrue(OutsideFailureStreak.blocksSend(PlayerErrorCause.ServerRefused))
        assertTrue(OutsideFailureStreak.blocksSend(PlayerErrorCause.Limit))
        assertTrue(OutsideFailureStreak.blocksSend(PlayerErrorCause.ServerUnreachable))
        assertFalse(OutsideFailureStreak.blocksSend(PlayerErrorCause.ServerSilent))
        assertFalse(OutsideFailureStreak.blocksSend(PlayerErrorCause.BoxCannotDecode))
    }

    @Test
    fun sendComesBackAfterThreeDifferentSourcesInARow() {
        val streak = OutsideFailureStreak()
        assertFalse(streak.record(PlayerErrorCause.ServerRefused, "a"))
        assertFalse(streak.record(PlayerErrorCause.ServerRefused, "a"))
        assertFalse(streak.record(PlayerErrorCause.ServerRefused, "b"))
        assertTrue(streak.record(PlayerErrorCause.ServerRefused, "c"))
    }

    @Test
    fun anotherCauseOrAPlayingSourceStartsAgain() {
        val streak = OutsideFailureStreak()
        streak.record(PlayerErrorCause.ServerRefused, "a")
        streak.record(PlayerErrorCause.ServerRefused, "b")
        assertFalse(streak.record(PlayerErrorCause.Limit, "c"))
        streak.record(PlayerErrorCause.Limit, "d")
        streak.reset()
        assertFalse(streak.record(PlayerErrorCause.Limit, "e"))
    }
}
