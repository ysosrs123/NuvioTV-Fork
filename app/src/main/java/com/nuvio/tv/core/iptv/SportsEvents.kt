package com.nuvio.tv.core.iptv

enum class FixtureEventKind { GOAL, YELLOW, RED, TRY, OTHER }

data class FixtureEvent(val kind: FixtureEventKind, val side: FixtureSide?, val clock: String?, val period: Int? = null, val player: String? = null)

object SportsEvents {
    const val MAX_EVENTS = 40
    private const val RECENT_TRY_SECONDS = 120

    fun reds(fixture: SportsFixture, side: FixtureSide): Int =
        if (fixture.sport != "soccer" || fixture.status == FixtureStatus.SCHEDULED) 0 else fixture.events.count { it.kind == FixtureEventKind.RED && it.side == side }

    fun recentTry(fixture: SportsFixture): FixtureEvent? {
        if (fixture.status != FixtureStatus.LIVE || fixture.sport !in TRY_SPORTS) return null
        val event = fixture.events.lastOrNull { it.kind == FixtureEventKind.TRY } ?: return null
        val at = event.clock?.let(SportsMarkers::seconds) ?: return null
        val now = fixture.clock?.let(SportsMarkers::seconds) ?: return null
        return event.takeIf { now - at in 0..RECENT_TRY_SECONDS }
    }

    fun moments(fixture: SportsFixture): List<SummaryMoment> = fixture.events.mapNotNull { event ->
        val kind = when (event.kind) {
            FixtureEventKind.GOAL -> MomentKind.GOAL
            FixtureEventKind.YELLOW -> MomentKind.CARD_YELLOW
            FixtureEventKind.RED -> MomentKind.CARD_RED
            FixtureEventKind.TRY -> MomentKind.TRY
            FixtureEventKind.OTHER -> return@mapNotNull null
        }
        SummaryMoment(kind, event.side, event.period, event.clock, listOfNotNull(event.clock, event.player).joinToString(" "))
    }

    private val TRY_SPORTS = setOf("rugby-league", "rugby")
}
