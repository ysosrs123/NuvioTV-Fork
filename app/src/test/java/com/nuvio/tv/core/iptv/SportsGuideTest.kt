package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class SportsGuideTest {
    private fun sport(title: String, categories: List<String> = emptyList(), channel: Boolean = false) =
        SportsGuide.isSportsProgramme(listOf(LocalizedGuideText(title, "en")), categories, channel)

    @Test fun categoriesCompetitionsAndFixturesMarkSport() {
        assertTrue(sport("Saturday Afternoon", listOf("Sports")))
        assertTrue(sport("Fußball: Bayern gegen Dortmund", listOf("Fußball")))
        assertTrue(sport("Premier League: Liverpool v Arsenal"))
        assertTrue(sport("AFL Round 5: Carlton v Collingwood"))
        assertTrue(sport("Formula 1 Grand Prix of Monaco"))
        assertTrue(sport("LIVE: Melbourne Victory vs Sydney FC"))
        assertTrue(sport("Lakers @ Celtics", channel = true))
        assertTrue(sport("Live Tennis", channel = true))
        assertTrue(sport("Real Madrid vs. Barcelona", listOf("Fútbol")))
    }

    @Test fun ordinaryProgrammesHighlightsAndFilmsAreNotSport() {
        assertFalse(sport("Kramer vs. Kramer", listOf("Movie")))
        assertFalse(sport("Kramer vs. Kramer"))
        assertFalse(sport("Batman v Superman: Dawn of Justice"))
        assertFalse(sport("Premier League Highlights"))
        assertFalse(sport("NRL Tipping Show"))
        assertFalse(sport("Sports News", listOf("Sports")))
        assertFalse(sport("Boxing Day Sales Special"))
        assertFalse(sport("Formula 1: Drive to Survive", listOf("Documentary")))
        assertFalse(sport("The X Factor Live"))
        assertFalse(sport("MasterChef Australia", channel = true))
        assertFalse(sport("Saturday Night Live"))
        assertFalse(sport(""))
    }

    @Test fun sportsChannelsAreRecognisedByName() {
        assertTrue(SportsGuide.isSportsChannel(listOf(LocalizedGuideText("Fox Sports 503", null))))
        assertTrue(SportsGuide.isSportsChannel(listOf(LocalizedGuideText("beIN SPORTS 1 HD", null))))
        assertTrue(SportsGuide.isSportsChannel(listOf(LocalizedGuideText("ESPN2", null))) || SportsGuide.isSportsChannel(listOf(LocalizedGuideText("ESPN 2", null))))
        assertFalse(SportsGuide.isSportsChannel(listOf(LocalizedGuideText("ABC News", null))))
        assertFalse(SportsGuide.isSportsChannel(listOf(LocalizedGuideText("Matchmaker TV", null))))
    }

    @Test fun liveProgrammesComeFirstThenByStart() {
        fun programme(start: Long, title: String) = GuideProgramme("c", GuideTimestamp(start, 14, ""), null, listOf(LocalizedGuideText(title, null)), emptyList())
        val listings = listOf("later" to programme(5_000, "b"), "now" to programme(500, "a"), "soon" to programme(2_000, "c"), "earlier" to programme(100, "d"))
        assertEquals(listOf("earlier", "now", "soon", "later"), sportsOrder(listings, 1_000).map { it.first })
    }
}
