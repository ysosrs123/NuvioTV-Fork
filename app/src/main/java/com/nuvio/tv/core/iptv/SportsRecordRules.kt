package com.nuvio.tv.core.iptv

data class SportsRecordTarget(val sourceId: String, val channelId: String, val programmeStartMillis: Long? = null, val programmeStopMillis: Long? = null,
    val programmeTitle: String? = null)

data class SportsRecordPlan(val fixture: SportsFixture, val target: SportsRecordTarget, val window: RecordingWindow, val final: Boolean)

data class SportsRecordOutcome(val plans: List<SportsRecordPlan> = emptyList(), val waiting: List<SportsFixture> = emptyList(),
    val missed: List<SportsFixture> = emptyList())

object SportsRecordRules {
    const val FINAL_EXTRA_MILLIS = 30L * 60 * 1000
    const val MAX_RULES = 50
    private val FINAL = Regex("\\b(grand final|finals?|semi[- ]?finals?|quarter[- ]?finals?|preliminary final|qualifying final|elimination final|" +
        "play[- ]?offs?|wild ?card|knockout|championship game|super bowl|world series|stanley cup final|decider)\\b", RegexOption.IGNORE_CASE)

    fun wanted(rules: Set<String>, fixture: SportsFixture): Boolean =
        rules.isNotEmpty() && fixture.status != FixtureStatus.FINAL && listOfNotNull(fixture.home, fixture.away).any { SportsFavourites.key(fixture.league, it) in rules }

    fun plan(rules: Set<String>, fixtures: List<SportsFixture>, links: Map<String, SportsRecordTarget>, scheduled: Set<String>, earlyMillis: Long,
        lateMillis: Long, nowMillis: Long): SportsRecordOutcome {
        if (rules.isEmpty()) return SportsRecordOutcome()
        val plans = mutableListOf<SportsRecordPlan>()
        val waiting = mutableListOf<SportsFixture>()
        val missed = mutableListOf<SportsFixture>()
        fixtures.distinctBy { it.key }.sortedBy { it.startMillis }.forEach { fixture ->
            if (!wanted(rules, fixture) || fixture.key in scheduled) return@forEach
            if (fixture.startMillis + SportsRefresh.durationMillis(fixture) <= nowMillis) return@forEach
            val target = links[fixture.key]
            when {
                target != null -> window(fixture, target, earlyMillis, lateMillis, nowMillis)?.let { plans += SportsRecordPlan(fixture, target, it, final(fixture, target.programmeTitle)) }
                nowMillis >= fixture.startMillis -> missed += fixture
                else -> waiting += fixture
            }
        }
        return SportsRecordOutcome(plans, waiting, missed)
    }

    fun window(fixture: SportsFixture, target: SportsRecordTarget, earlyMillis: Long, lateMillis: Long, nowMillis: Long): RecordingWindow? {
        require(earlyMillis >= 0 && lateMillis >= 0)
        val duration = SportsRefresh.durationMillis(fixture)
        val expected = fixture.startMillis + duration
        val broadcast = target.programmeStopMillis?.takeIf { it > fixture.startMillis + duration / 2 && it <= expected + duration }
        val end = maxOf(expected, broadcast ?: expected) + lateMillis + if (final(fixture, target.programmeTitle)) FINAL_EXTRA_MILLIS else 0
        val start = maxOf(fixture.startMillis - earlyMillis, nowMillis)
        if (end <= start) return null
        return RecordingWindow(start, minOf(end, start + RecordingPlan.MAX_DURATION_MILLIS))
    }

    fun final(fixture: SportsFixture, programmeTitle: String? = null): Boolean {
        val round = (fixture.sportDetail as? SportsDetail.Tennis)?.round
        return listOfNotNull(fixture.title, round, programmeTitle, fixture.detail?.takeIf { fixture.status == FixtureStatus.SCHEDULED })
            .any { text -> FINAL.containsMatchIn(text) }
    }
}

data class SportsTeamGames(val key: String, val league: String, val name: String, val team: FixtureTeam?, val current: SportsFixture?,
    val upcoming: List<SportsFixture>, val last: SportsFixture?)

object SportsTeams {
    fun games(key: String, fixtures: List<SportsFixture>, nowMillis: Long): SportsTeamGames? {
        val (league, name) = SportsFavourites.parse(key) ?: return null
        val mine = fixtures.distinctBy { it.key }.filter { it.league == league && listOfNotNull(it.home, it.away).any { team -> team.name.trim() == name } }
            .sortedBy { it.startMillis }
        val team = mine.firstNotNullOfOrNull { fixture -> listOfNotNull(fixture.home, fixture.away).firstOrNull { it.name.trim() == name } }
        val live = mine.lastOrNull { it.status == FixtureStatus.LIVE }
        val upcoming = mine.filter { it.status == FixtureStatus.SCHEDULED && it.startMillis + SportsRefresh.durationMillis(it) > nowMillis }
        return SportsTeamGames(key, league, name, team, live ?: upcoming.firstOrNull(), if (live != null) upcoming else upcoming.drop(1),
            mine.lastOrNull { it.status == FixtureStatus.FINAL })
    }

    fun home(games: SportsTeamGames, fixture: SportsFixture): Boolean? = when (games.name) {
        fixture.home?.name?.trim() -> true
        fixture.away?.name?.trim() -> false
        else -> null
    }
}
