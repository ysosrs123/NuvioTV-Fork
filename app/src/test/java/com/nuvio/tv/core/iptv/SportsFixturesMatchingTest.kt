package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class SportsFixturesMatchingTest {
    private val hour = 60L * 60 * 1000
    private val start = 1_800_000_000_000L
    private val richmond = FixtureTeam("Richmond Tigers", "Richmond", "RICH", listOf("Tigers"))
    private val carlton = FixtureTeam("Carlton Blues", "Carlton", "CARL", listOf("Blues"))
    private val afl = SportsFixture("401", "afl", "australian-football", "Richmond Tigers v Carlton Blues", richmond, carlton, start, FixtureStatus.SCHEDULED,
        broadcasters = listOf("Seven Network", "Fox Footy", "Kayo"))

    private fun programme(title: String, from: Long = start - 10 * 60_000, to: Long? = start + 3 * hour, description: String? = null) =
        GuideProgramme("c", GuideTimestamp(from, 14, ""), to?.let { GuideTimestamp(it, 14, "") }, listOf(LocalizedGuideText(title, "en")),
            listOfNotNull(description?.let { LocalizedGuideText(it, "en") }))

    private fun match(fixture: SportsFixture, title: String, description: String? = null, from: Long = start - 10 * 60_000) =
        SportsFixtureMatching.guideMatch(fixture, programme(title, from, description = description), start)

    @Test fun bothTeamsWithSeparatorsAndAccentsMatch() {
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, match(afl, "AFL: Richmond v Carlton"))
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, match(afl, "LIVE Richmond Tigers vs. Carlton Blues"))
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, match(afl, "Carlton @ Richmond"))
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, match(afl, "Richmond - Carlton"))
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, match(afl, "Tigers v Blues", "Round 4 of the AFL Premiership"))
        assertNull(match(afl, "Tigers v Blues"))
        val atletico = SportsFixture("9", "la-liga", "soccer", "x", FixtureTeam("Atlético Madrid", "Atlético", "ATM"), FixtureTeam("Real Betis", "Betis", "BET"),
            start, FixtureStatus.SCHEDULED)
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, match(atletico, "LaLiga: Atletico Madrid v Real Betis"))
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, match(atletico, "ATLÉTICO – BETIS"))
    }

    @Test fun oneTeamPlusLeagueIsWeakerMatch() {
        assertEquals(FixtureLinkReason.GUIDE_LEAGUE, match(afl, "AFL Round 4: Richmond Tigers"))
        assertNull(match(afl, "Richmond Tigers"))
        assertNull(match(afl, "AFL: RICH"))
    }

    @Test fun timeMustOverlap() {
        assertNull(match(afl, "AFL: Richmond v Carlton", from = start + 5 * hour))
        assertNull(SportsFixtureMatching.guideMatch(afl, programme("AFL: Richmond v Carlton", start - 4 * hour, start - hour), start))
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, match(afl, "AFL: Richmond v Carlton", from = start + 30 * 60_000))
    }

    @Test fun highlightsRepeatsAndOtherLeaguesAreRejected() {
        assertNull(match(afl, "AFL Highlights: Richmond v Carlton"))
        assertNull(match(afl, "Replay: Richmond v Carlton"))
        val lions = SportsFixture("2", "afl", "australian-football", "x", FixtureTeam("Brisbane Lions", null, "BL", listOf("Brisbane", "Lions")),
            FixtureTeam("Sydney Swans", null, "SYD", listOf("Sydney", "Swans")), start, FixtureStatus.SCHEDULED)
        assertNull(match(lions, "Brisbane Roar v Sydney FC"))
        assertNull(match(lions, "A-League: Brisbane v Sydney"))
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, match(lions, "AFL: Brisbane v Sydney"))
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, match(lions, "Brisbane Lions v Sydney Swans"))
    }

    @Test fun womenAndYouthMarkersMustAgree() {
        val men = SportsFixture("3", "a-league-men", "soccer", "x", FixtureTeam("Melbourne Victory"), FixtureTeam("Sydney FC"), start, FixtureStatus.SCHEDULED)
        val women = men.copy(id = "4", league = "a-league-women")
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, match(men, "A-League Men: Melbourne Victory v Sydney FC"))
        assertNull(match(men, "A-League Women: Melbourne Victory v Sydney FC"))
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, match(women, "A-League Women: Melbourne Victory v Sydney FC"))
        assertNull(match(women, "A-League Men: Melbourne Victory v Sydney FC"))
        assertNull(match(men, "Melbourne Victory U21 v Sydney FC U21"))
        val youth = men.copy(home = FixtureTeam("Melbourne Victory U21"), away = FixtureTeam("Sydney FC U21"))
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, match(youth, "Melbourne Victory U21 v Sydney FC U21"))
        assertNull(match(youth, "Melbourne Victory v Sydney FC"))
    }

    @Test fun clubNameVariantsMatch() {
        val city = SportsFixture("5", "epl", "soccer", "x", FixtureTeam("Manchester City", "Man City", "MNC"), FixtureTeam("Manchester United", "Man United", "MAN"),
            start, FixtureStatus.SCHEDULED)
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, match(city, "Premier League: Man City v Man Utd"))
        assertEquals(listOf("arsenal fc", "arsenal"), SportsFixtureMatching.variants("Arsenal FC"))
        assertTrue("man utd" in SportsFixtureMatching.variants("Manchester United"))
    }

    @Test fun teamlessEventsNeedLeagueAndTitleWords() {
        val race = SportsFixture("6", "f1", "motorsport", "Louis Vuitton Australian Grand Prix", null, null, start, FixtureStatus.SCHEDULED)
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, match(race, "Formula 1: Australian Grand Prix - Race"))
        assertNull(match(race, "Formula 1: Japanese Grand Prix"))
        assertNull(match(race, "Australian Grand Prix Preview Show"))
        val ufc = SportsFixture("7", "ufc", "mma", "UFC 320: Pantoja vs. Asakura", null, null, start, FixtureStatus.SCHEDULED)
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, match(ufc, "UFC 320 Main Card"))
    }

    @Test fun broadcastersMatchChannelNamesWithNumbers() {
        assertTrue(SportsFixtureMatching.broadcasterMatches("Fox Footy", "AU: Fox Footy HD"))
        assertTrue(SportsFixtureMatching.broadcasterMatches("FOX FOOTY", "Fox Footy (Backup)"))
        assertFalse(SportsFixtureMatching.broadcasterMatches("Fox Footy", "Fox League HD"))
        assertTrue(SportsFixtureMatching.broadcasterMatches("beIN SPORTS 1", "beIN Sports 1 Australia"))
        assertFalse(SportsFixtureMatching.broadcasterMatches("beIN SPORTS 1", "beIN Sports 3"))
        assertTrue(SportsFixtureMatching.broadcasterMatches("beIN SPORTS", "beIN Sports 1"))
        assertFalse(SportsFixtureMatching.broadcasterMatches("beIN SPORTS", "beIN Sports 2"))
        assertTrue(SportsFixtureMatching.broadcasterMatches("Fox Sports 503", "FOX SPORTS 503 HD"))
        assertFalse(SportsFixtureMatching.broadcasterMatches("Fox Sports 503", "Fox Sports 505"))
        assertTrue(SportsFixtureMatching.broadcasterMatches("ESPN2", "ESPN 2 HD"))
        assertFalse(SportsFixtureMatching.broadcasterMatches("ESPN", "ESPN 2"))
        assertFalse(SportsFixtureMatching.broadcasterMatches("ESPN", "ESPN Deportes"))
        assertTrue(SportsFixtureMatching.broadcasterMatches("Sky Sports Main Event", "UK | Sky Sports Main Event FHD"))
        assertFalse(SportsFixtureMatching.broadcasterMatches("Sky Sports Main Event", "Sky Sports F1"))
        assertTrue(SportsFixtureMatching.broadcasterMatches("TNT Sports 1", "TNT Sports 1 HD"))
        assertTrue(SportsFixtureMatching.broadcasterMatches("Optus Sport", "Optus Sport 1"))
        assertTrue(SportsFixtureMatching.broadcasterMatches("Stan Sport", "Stan Sport HD"))
        assertTrue(SportsFixtureMatching.broadcasterMatches("DAZN 1", "DAZN 1 HD"))
        assertTrue(SportsFixtureMatching.broadcasterMatches("Kayo", "Kayo Sports"))
    }

    @Test fun freeToAirNetworksMatchExactly() {
        assertTrue(SportsFixtureMatching.broadcasterMatches("Seven Network", "7 HD Sydney"))
        assertTrue(SportsFixtureMatching.broadcasterMatches("Seven", "AU: Channel 7"))
        assertFalse(SportsFixtureMatching.broadcasterMatches("Seven Network", "7mate HD"))
        assertTrue(SportsFixtureMatching.broadcasterMatches("7mate", "7mate HD"))
        assertTrue(SportsFixtureMatching.broadcasterMatches("Nine Network", "Channel 9 Melbourne"))
        assertFalse(SportsFixtureMatching.broadcasterMatches("Nine", "9Gem"))
        assertTrue(SportsFixtureMatching.broadcasterMatches("Network 10", "10 HD"))
        assertFalse(SportsFixtureMatching.broadcasterMatches("Network 10", "10 Peach"))
        assertFalse(SportsFixtureMatching.broadcasterMatches("Ten", "Sky Sports 10"))
        assertTrue(SportsFixtureMatching.broadcasterMatches("SBS", "SBS HD"))
        assertFalse(SportsFixtureMatching.broadcasterMatches("SBS", "SBS Viceland"))
        assertTrue(SportsFixtureMatching.broadcasterMatches("Prime Video", "Prime Video"))
        assertFalse(SportsFixtureMatching.broadcasterMatches("Prime Video", "Prime"))
    }

    @Test fun linkRanksGuideMatchesFirstCapsAndSkipsHiddenChannels() {
        val channels = listOf(FixtureChannel("seven", "7 HD Sydney"), FixtureChannel("footy", "Fox Footy HD", "Sport"),
            FixtureChannel("footy2", "Fox Footy Backup", "Hidden sport"), FixtureChannel("kayo", "Kayo 1", hidden = true),
            FixtureChannel("guide", "Some Sport Channel"), FixtureChannel("league", "League channel"))
        val listings = listOf(FixtureListing("footy", programme("AFL: Richmond v Carlton")), FixtureListing("league", programme("AFL Live: Richmond Tigers")),
            FixtureListing("missing", programme("AFL: Richmond v Carlton")), FixtureListing("footy2", programme("AFL: Richmond v Carlton")),
            FixtureListing("guide", programme("Richmond v Carlton", description = "AFL")))
        val links = SportsFixtureMatching.link(listOf(afl), channels, listings, setOf("Hidden sport"), start).getValue("401")
        assertEquals(listOf("footy", "guide", "league", "seven"), links.map { it.channelId })
        assertEquals(listOf(FixtureLinkReason.GUIDE_TEAMS, FixtureLinkReason.GUIDE_TEAMS, FixtureLinkReason.GUIDE_LEAGUE, FixtureLinkReason.BROADCASTER), links.map { it.reason })
        assertEquals("Seven Network", links.last().broadcaster)
        assertEquals(2, SportsFixtureMatching.link(listOf(afl), channels, listings, setOf("Hidden sport"), start, max = 2).getValue("401").size)
        assertTrue(SportsFixtureMatching.link(listOf(afl.copy(broadcasters = emptyList())), emptyList(), listings, emptySet(), start).isEmpty())
    }

    @Test fun searchTermsForBroadcasters() {
        val terms = SportsFixtureMatching.searchTerms(listOf("Seven Network", "Fox Footy", "beIN SPORTS 2", "Prime Video", "!!"))
        assertTrue("seven" in terms && "7 hd" in terms)
        assertTrue("fox footy" in terms)
        assertTrue("bein" in terms)
        assertTrue("prime video" in terms)
        assertTrue(terms.size <= 12)
    }
}
