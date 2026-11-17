package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldFailoverCardTest {
    private val link = "https://cdn.example/a.mp4"
    private val detail = "HTTP 403 Forbidden"

    private fun card(cause: PlayerErrorCause, message: String = "card text") =
        PlayerErrorCard(cause, "title", message, facts = null)

    @Test
    fun aRefusalAfterTheSearchForAnotherSourceLosesSend() {
        val streak = OutsideFailureStreak()
        val held = HeldFailoverCard(link, detail, card(PlayerErrorCause.ServerRefused))

        val shown = held.forEnd(link, detail, streak)

        assertEquals(PlayerErrorCause.ServerRefused, shown?.cause)
        assertFalse(shown!!.sendAllowed)
    }

    @Test
    fun aSilentServerAfterTheSearchKeepsSend() {
        val streak = OutsideFailureStreak()
        val held = HeldFailoverCard(link, detail, card(PlayerErrorCause.ServerSilent))

        val shown = held.forEnd(link, detail, streak)

        assertEquals(PlayerErrorCause.ServerSilent, shown?.cause)
        assertTrue(shown!!.sendAllowed)
    }

    @Test
    fun theLimitAndUnreachableCardsLoseSendToo() {
        listOf(PlayerErrorCause.Limit, PlayerErrorCause.ServerUnreachable).forEach { cause ->
            assertFalse(HeldFailoverCard(link, detail, card(cause)).forEnd(link, detail, OutsideFailureStreak())!!.sendAllowed)
        }
    }

    @Test
    fun theCardIsOnlyShownForTheSameLinkAndError() {
        val held = HeldFailoverCard(link, detail, card(PlayerErrorCause.ServerRefused))

        assertNull(held.forEnd("https://cdn.example/b.mp4", detail, OutsideFailureStreak()))
        assertNull(held.forEnd(link, "Torrent engine stopped", OutsideFailureStreak()))
    }

    @Test
    fun everySourceInOneSearchCountsTowardsSend() {
        val streak = OutsideFailureStreak()
        val refused = card(PlayerErrorCause.ServerRefused)
        var held = HeldFailoverCard.hold("one", detail, refused, streak)
        held = HeldFailoverCard.hold("two", detail, refused, streak)
        assertFalse(held.forEnd("two", detail, streak)!!.sendAllowed)

        val longer = OutsideFailureStreak()
        listOf("a", "b", "c").forEach { held = HeldFailoverCard.hold(it, detail, card(PlayerErrorCause.Limit), longer) }
        held = HeldFailoverCard.hold("d", detail, card(PlayerErrorCause.Limit), longer)
        assertTrue(held.forEnd("d", detail, longer)!!.sendAllowed)
    }

    @Test
    fun aSilentServerOnTheWayDoesNotCount() {
        val streak = OutsideFailureStreak()
        HeldFailoverCard.hold("one", detail, card(PlayerErrorCause.ServerSilent), streak)
        val held = HeldFailoverCard.hold("two", detail, card(PlayerErrorCause.ServerRefused), streak)
        assertFalse(held.forEnd("two", detail, streak)!!.sendAllowed)
    }

    @Test
    fun aLongLimitKeepsItsOwnTextWithTheCard() {
        val held = HeldFailoverCard(link, "HTTP 429", card(PlayerErrorCause.Limit))
        val renamed = held.shownAs("HTTP 429", "Try again in 12 minutes")!!

        val shown = renamed.forEnd(link, "Try again in 12 minutes", OutsideFailureStreak())
        assertEquals("Try again in 12 minutes", shown?.message)
        assertFalse(shown!!.sendAllowed)
        assertNull(held.shownAs("another error", "Try again in 12 minutes"))
    }

    @Test
    fun sendComesBackAfterTheSameRefusalOnThreeSources() {
        val streak = OutsideFailureStreak()
        val refused = card(PlayerErrorCause.ServerRefused)

        assertFalse(refused.applySendRule("one", streak).sendAllowed)
        assertFalse(refused.applySendRule("two", streak).sendAllowed)
        assertTrue(HeldFailoverCard("three", detail, refused).forEnd("three", detail, streak)!!.sendAllowed)
    }
}
