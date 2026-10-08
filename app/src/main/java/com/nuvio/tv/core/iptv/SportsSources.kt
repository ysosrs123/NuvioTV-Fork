package com.nuvio.tv.core.iptv

enum class SportsLogos { ESPN, ALL, OFF }

object SportsSources {
    const val FALLBACK_FAILURES = 2

    fun enabled(stored: Boolean?, oldService: String?): Boolean = stored ?: (oldService == "ESPN" || oldService == "THESPORTSDB")

    fun needsKey(league: SportsLeague): Boolean = league.espn == null && league.sportsDb != null

    fun available(league: SportsLeague, hasKey: Boolean): Boolean = league.espn != null || (league.sportsDb != null && hasKey)

    fun espnFailing(entries: List<SportsCacheEntry?>): Boolean {
        val known = entries.filterNotNull()
        return known.isNotEmpty() && known.count { it.failures >= FALLBACK_FAILURES } * 2 >= known.size
    }

    fun fallback(league: SportsLeague, hasKey: Boolean, espn: List<SportsCacheEntry?>): Boolean =
        league.espn != null && league.sportsDb != null && hasKey && espnFailing(espn)

    fun pick(league: SportsLeague, hasKey: Boolean, espn: List<SportsCacheEntry?>, sportsDb: List<SportsCacheEntry?>): SportsService? = when {
        league.espn == null -> SportsService.THESPORTSDB.takeIf { league.sportsDb != null && hasKey }
        fallback(league, hasKey, espn) && sportsDb.any { it?.fetchedAt != null } -> SportsService.THESPORTSDB
        else -> SportsService.ESPN
    }

    fun logos(fixtures: List<SportsFixture>, mode: SportsLogos): List<SportsFixture> = if (mode == SportsLogos.ALL) fixtures else fixtures.map { fixture ->
        if (mode == SportsLogos.ESPN && fixture.source == SportsService.ESPN) fixture
        else fixture.copy(home = fixture.home?.copy(logo = null), away = fixture.away?.copy(logo = null), leagueLogo = null)
    }
}
