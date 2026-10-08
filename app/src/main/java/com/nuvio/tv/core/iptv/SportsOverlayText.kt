package com.nuvio.tv.core.iptv

enum class SportsHeadline { GOAL, TEAM_SCORED, SCORED, KICK_OFF, STARTED, FULL_TIME, FINAL }

data class SportsAflScore(val goals: Int, val behinds: Int, val total: Int)

data class SportsTickerPage(val league: String, val fixtures: List<SportsFixture>)

object SportsOverlayText {
    const val PAGE_SIZE = 4
    private val GOAL_SPORTS = setOf("soccer", "ice-hockey")
    private val KICK_OFF_SPORTS = setOf("soccer", "rugby", "rugby-league", "australian-football", "american-football")
    private val FULL_TIME_SPORTS = setOf("soccer", "rugby", "rugby-league", "australian-football")
    private val AFL = Regex("""^(\d{1,3})\.(\d{1,3})\s*\((\d{1,4})\)$""")

    fun headline(change: SportsChange): SportsHeadline = when (change.kind) {
        SportsChangeKind.SCORED -> when {
            change.fixture.sport in GOAL_SPORTS -> SportsHeadline.GOAL
            scorer(change) != null -> SportsHeadline.TEAM_SCORED
            else -> SportsHeadline.SCORED
        }
        SportsChangeKind.STARTED -> if (change.fixture.sport in KICK_OFF_SPORTS) SportsHeadline.KICK_OFF else SportsHeadline.STARTED
        SportsChangeKind.FINISHED -> if (change.fixture.sport in FULL_TIME_SPORTS) SportsHeadline.FULL_TIME else SportsHeadline.FINAL
    }

    fun scorer(change: SportsChange): FixtureTeam? = when (change.side) {
        FixtureSide.HOME -> change.fixture.home
        FixtureSide.AWAY -> change.fixture.away
        null -> null
    }

    fun name(team: FixtureTeam): String = team.shortName?.takeIf(String::isNotBlank) ?: team.name

    fun match(fixture: SportsFixture): String {
        val home = fixture.home ?: return fixture.title
        val away = fixture.away ?: return fixture.title
        return if (SportsFixtureText.awayFirst(fixture)) "${name(away)} @ ${name(home)}" else "${name(home)} v ${name(away)}"
    }

    fun scoreLine(fixture: SportsFixture): String {
        val home = fixture.home
        val away = fixture.away
        if (home == null || away == null || fixture.sportDetail is SportsDetail.Tennis) return SportsFixtureText.bug(fixture).primary
        val (h, a) = SportsFixtureText.scores(fixture)?.takeIf { fixture.status != FixtureStatus.SCHEDULED } ?: return match(fixture)
        return if (SportsFixtureText.awayFirst(fixture)) "${name(away)} ${total(a)}–${total(h)} ${name(home)}" else "${name(home)} ${total(h)}–${total(a)} ${name(away)}"
    }

    fun score(fixture: SportsFixture): String? {
        val (h, a) = SportsFixtureText.scores(fixture)?.takeIf { fixture.status != FixtureStatus.SCHEDULED } ?: return null
        return if (SportsFixtureText.awayFirst(fixture)) "${total(a)}–${total(h)}" else "${total(h)}–${total(a)}"
    }

    fun afl(score: String?): SportsAflScore? {
        val match = AFL.matchEntire(score?.trim() ?: return null) ?: return null
        val (goals, behinds, total) = match.destructured
        return SportsAflScore(goals.toInt(), behinds.toInt(), total.toInt())
    }

    fun total(score: String): String = afl(score)?.total?.toString() ?: score

    fun minutesUntil(startMillis: Long, nowMillis: Long): Int = if (startMillis <= nowMillis) 0 else ((startMillis - nowMillis + 59_999) / 60_000).toInt()

    fun pages(fixtures: List<SportsFixture>, size: Int = PAGE_SIZE): List<SportsTickerPage> =
        fixtures.distinctBy { it.key }.groupBy { it.league }.flatMap { (league, items) -> items.chunked(size.coerceAtLeast(1)).map { SportsTickerPage(league, it) } }

    fun tennisName(team: FixtureTeam): String = team.abbreviation ?: team.name.substringAfterLast(' ').uppercase()

    fun tennisGames(detail: SportsDetail.Tennis, side: FixtureSide): List<String> = detail.sets.map { set ->
        ((if (side == FixtureSide.HOME) set.home else set.away) ?: 0).toString()
    }

    fun golfFollowed(detail: SportsDetail.Golf, league: String, favourites: Set<String>): GolfPlayer? {
        if (favourites.isEmpty()) return null
        return detail.leaders.drop(1).firstOrNull { player -> "$league:${player.name.trim()}" in favourites }
    }

    fun golfLine(player: GolfPlayer): String = listOfNotNull(player.position, (player.shortName ?: player.name.substringAfterLast(' ')).uppercase(),
        player.toPar).joinToString(" ") + (player.thru?.let { if (it == "F") " · F" else " · thru $it" } ?: "")

    fun nextSession(detail: SportsDetail.Sessions): RaceSession? {
        val current = detail.current ?: return null
        val index = detail.sessions.indexOf(current)
        return detail.sessions.drop(index + 1).firstOrNull { it.state == FixtureStatus.SCHEDULED }
    }

    fun mainEventLive(detail: SportsDetail.Card): Boolean = detail.live?.let { it == detail.mainEvent } ?: false
}
