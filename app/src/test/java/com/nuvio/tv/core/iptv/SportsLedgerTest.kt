package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class SportsLedgerTest {
    private val card = 0xFF141414.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val grey = 0xFFB3B3B3.toInt()

    @Test fun parsesEspnColours() {
        assertEquals(0xFFE03A3E.toInt(), TeamColours.parse("e03a3e"))
        assertEquals(0xFFE03A3E.toInt(), TeamColours.parse("#E03A3E"))
        assertNull(TeamColours.parse(null))
        assertNull(TeamColours.parse("fff"))
        assertNull(TeamColours.parse("zzzzzz"))
    }

    @Test fun stripeFallsBackAndLightensDarkColours() {
        assertEquals(TeamColours.NEUTRAL, TeamColours.stripe(null, card))
        assertEquals(0xFFE03A3E.toInt(), TeamColours.stripe("e03a3e", card))
        val navy = TeamColours.stripe("000000", card)
        assertNotEquals(0xFF000000.toInt(), navy)
        assertTrue(TeamColours.contrast(navy, card) >= TeamColours.STRIPE_CONTRAST)
        assertTrue(TeamColours.contrast(TeamColours.stripe("0c2340", card), card) >= TeamColours.STRIPE_CONTRAST)
        assertTrue(TeamColours.contrast(TeamColours.NEUTRAL, card) >= TeamColours.STRIPE_CONTRAST)
    }

    @Test fun bandKeepsTextReadable() {
        listOf("e03a3e", "ffcd00", "ffffff", "c4ced4", "006bb6", "000000").forEach { hex ->
            val colour = TeamColours.stripe(hex, card)
            val alpha = TeamColours.bandAlpha(colour, card, grey)
            assertTrue(alpha in 0f..0.34f)
            assertTrue(hex, TeamColours.contrast(grey, TeamColours.blend(colour, alpha, card)) >= TeamColours.TEXT_CONTRAST)
            assertTrue(hex, TeamColours.contrast(white, TeamColours.blend(colour, alpha, card)) >= TeamColours.TEXT_CONTRAST)
        }
        assertTrue(TeamColours.bandAlpha(TeamColours.parse("e03a3e")!!, card, grey) > 0.1f)
        assertTrue(TeamColours.bandAlpha(white, card, grey) < TeamColours.bandAlpha(TeamColours.parse("e03a3e")!!, card, grey))
    }

    @Test fun blendAndContrastBasics() {
        assertEquals(card, TeamColours.blend(white, 0f, card))
        assertEquals(white, TeamColours.blend(white, 1f, card))
        assertEquals(21.0, TeamColours.contrast(white, 0xFF000000.toInt()), 0.01)
    }

    @Test fun splitsCityFromNickname() {
        assertEquals("Atlanta" to "Hawks", SportsLedger.names(FixtureTeam("Atlanta Hawks", "Hawks")))
        assertEquals("San Antonio" to "Spurs", SportsLedger.names(FixtureTeam("San Antonio Spurs", "Spurs")))
        assertEquals(null to "Arsenal", SportsLedger.names(FixtureTeam("Arsenal", "Arsenal")))
        assertEquals(null to "Leeds United", SportsLedger.names(FixtureTeam("Leeds United", "Leeds")))
        assertEquals(null to "Melbourne", SportsLedger.names(FixtureTeam("Melbourne")))
    }

    @Test fun codesAndLeader() {
        assertEquals("ATL", SportsLedger.code(FixtureTeam("Atlanta Hawks", abbreviation = "atl")))
        assertNull(SportsLedger.code(FixtureTeam("Somewhere", abbreviation = "TOOLONG")))
        assertNull(SportsLedger.code(FixtureTeam("Somewhere")))
        assertEquals(0, SportsLedger.leader("110", "109"))
        assertEquals(1, SportsLedger.leader("0", "2"))
        assertNull(SportsLedger.leader("1", "1"))
        assertNull(SportsLedger.leader("245/6", "120/3"))
        assertNull(SportsLedger.leader(null, "1"))
    }

    @Test fun clocksAndCurrentPeriod() {
        assertTrue(SportsLedger.countsDown("basketball"))
        assertFalse(SportsLedger.countsDown("soccer"))
        val live = SportsFixture("1", "nba", "basketball", "A at B", null, null, 0, FixtureStatus.LIVE, period = 4)
        assertEquals(3, SportsLedger.currentPeriod(live, 4))
        assertEquals(4, SportsLedger.currentPeriod(live.copy(period = 6), 5))
        assertEquals(1, SportsLedger.currentPeriod(live.copy(period = null), 2))
        assertNull(SportsLedger.currentPeriod(live.copy(status = FixtureStatus.FINAL), 4))
        assertNull(SportsLedger.currentPeriod(live, 0))
    }

    @Test fun namesPeriods() {
        assertEquals(PeriodName.QUARTER, SportsLedger.periodName("basketball", 4))
        assertEquals(PeriodName.OVERTIME, SportsLedger.periodName("basketball", 5))
        assertEquals(PeriodName.PERIOD, SportsLedger.periodName("ice-hockey", 3))
        assertEquals(PeriodName.OVERTIME, SportsLedger.periodName("ice-hockey", 4))
        assertEquals(PeriodName.FIRST_HALF, SportsLedger.periodName("soccer", 1))
        assertEquals(PeriodName.SECOND_HALF, SportsLedger.periodName("rugby", 2))
        assertEquals(PeriodName.EXTRA_TIME, SportsLedger.periodName("soccer", 3))
        assertNull(SportsLedger.periodName("baseball", 5))
        assertNull(SportsLedger.periodName("soccer", null))
        assertNull(SportsLedger.periodName("soccer", 0))
    }
}
