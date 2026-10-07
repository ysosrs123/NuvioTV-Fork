package com.nuvio.tv.core.iptv

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject

enum class SportsService { OFF, ESPN, THESPORTSDB }
enum class FixtureStatus { SCHEDULED, LIVE, FINAL }
enum class FixtureSection { LIVE, TODAY, TOMORROW, LATER }

data class FixtureTeam(val name: String, val shortName: String? = null, val abbreviation: String? = null, val alternatives: List<String> = emptyList()) {
    val strongNames: List<String> get() = listOfNotNull(name, shortName).filter(String::isNotBlank).distinct()
    val weakNames: List<String> get() = (alternatives + listOfNotNull(abbreviation)).filter(String::isNotBlank).distinct() - strongNames.toSet()
}

data class SportsFixture(val id: String, val league: String, val sport: String, val title: String, val home: FixtureTeam?, val away: FixtureTeam?,
    val startMillis: Long, val status: FixtureStatus, val score: String? = null, val detail: String? = null, val broadcasters: List<String> = emptyList()) {
    val teams: Boolean get() = home != null && away != null
}

data class SportsLeague(val id: String, val name: String, val sport: String, val espn: String?, val sportsDb: String?, val sportsDbId: String? = null,
    val aliases: List<String> = emptyList(), val women: Boolean = false, val durationMinutes: Int = 180) {
    fun supports(service: SportsService): Boolean = when (service) {
        SportsService.OFF -> false
        SportsService.ESPN -> espn != null
        SportsService.THESPORTSDB -> sportsDb != null
    }
}

object SportsLeagues {
    val ALL = listOf(
        SportsLeague("afl", "AFL", "australian-football", "australian-football/afl", "Australian AFL", "4456",
            listOf("afl", "afl premiership", "australian football league", "aussie rules", "footy"), durationMinutes = 180),
        SportsLeague("nrl", "NRL", "rugby-league", "rugby-league/3", "Australian National Rugby League", "4416",
            listOf("nrl", "nrl premiership", "national rugby league", "rugby league"), durationMinutes = 135),
        SportsLeague("a-league-men", "A-League Men", "soccer", "soccer/aus.1", "Australian A-League", "4356",
            listOf("a league", "a league men", "a leagues", "isuzu ute a league"), durationMinutes = 135),
        SportsLeague("a-league-women", "A-League Women", "soccer", "soccer/aus.w.1", "Australian A-League Women", null,
            listOf("a league women", "a leagues", "liberty a league"), women = true, durationMinutes = 135),
        SportsLeague("super-rugby", "Super Rugby Pacific", "rugby", "rugby/242041", "Super Rugby", null,
            listOf("super rugby", "super rugby pacific"), durationMinutes = 135),
        SportsLeague("big-bash", "Big Bash League", "cricket", null, "Australian Big Bash League", null,
            listOf("big bash", "bbl", "kfc bbl", "big bash league"), durationMinutes = 240),
        SportsLeague("epl", "Premier League", "soccer", "soccer/eng.1", "English Premier League", "4328",
            listOf("premier league", "epl", "english premier league"), durationMinutes = 135),
        SportsLeague("champions-league", "Champions League", "soccer", "soccer/uefa.champions", "UEFA Champions League", "4480",
            listOf("champions league", "uefa champions league", "ucl"), durationMinutes = 135),
        SportsLeague("la-liga", "La Liga", "soccer", "soccer/esp.1", "Spanish La Liga", "4335", listOf("la liga", "laliga", "laliga ea sports"), durationMinutes = 135),
        SportsLeague("serie-a", "Serie A", "soccer", "soccer/ita.1", "Italian Serie A", "4332", listOf("serie a"), durationMinutes = 135),
        SportsLeague("bundesliga", "Bundesliga", "soccer", "soccer/ger.1", "German Bundesliga", "4331", listOf("bundesliga"), durationMinutes = 135),
        SportsLeague("nba", "NBA", "basketball", "basketball/nba", "NBA", "4387", listOf("nba", "basketball"), durationMinutes = 165),
        SportsLeague("nfl", "NFL", "american-football", "football/nfl", "NFL", "4391", listOf("nfl"), durationMinutes = 210),
        SportsLeague("mlb", "MLB", "baseball", "baseball/mlb", "MLB", "4424", listOf("mlb", "baseball"), durationMinutes = 210),
        SportsLeague("nhl", "NHL", "ice-hockey", "hockey/nhl", "NHL", "4380", listOf("nhl", "ice hockey", "hockey"), durationMinutes = 180),
        SportsLeague("f1", "Formula 1", "motorsport", "racing/f1", "Formula 1", "4370", listOf("formula 1", "formula one", "f1"), durationMinutes = 150),
        SportsLeague("ufc", "UFC", "mma", "mma/ufc", "UFC", "4443", listOf("ufc", "mma"), durationMinutes = 300),
    )
    val DEFAULTS = setOf("afl", "nrl", "a-league-men", "epl", "champions-league")
    private val byId = ALL.associateBy { it.id }

    fun byId(id: String): SportsLeague? = byId[id]

    fun chosen(ids: Set<String>, service: SportsService): List<SportsLeague> = ALL.filter { it.id in ids && it.supports(service) }
}

object SportsDays {
    const val DAYS = 3
    val ESPN_ZONE: ZoneId = ZoneId.of("America/New_York")
    val SPORTSDB_ZONE: ZoneId = ZoneOffset.UTC

    fun window(nowMillis: Long, zone: ZoneId): Pair<Long, Long> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return today.atStartOfDay(zone).toInstant().toEpochMilli() to today.plusDays(DAYS.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun serviceDates(fromMillis: Long, untilMillis: Long, zone: ZoneId): List<LocalDate> {
        require(untilMillis > fromMillis)
        val first = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(untilMillis - 1).atZone(zone).toLocalDate()
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.take(8).toList()
    }

    fun zone(service: SportsService): ZoneId = if (service == SportsService.THESPORTSDB) SPORTSDB_ZONE else ESPN_ZONE
}

data class SportsCacheEntry(val fixtures: List<SportsFixture>, val fetchedAt: Long?, val failedAt: Long? = null, val failures: Int = 0)

object SportsRefresh {
    const val FIXTURES_MILLIS = 30L * 60 * 1000
    const val LIVE_MILLIS = 2L * 60 * 1000
    const val MAX_BACKOFF_MILLIS = 60L * 60 * 1000
    private const val STARTING_MILLIS = 15L * 60 * 1000

    fun active(fixtures: List<SportsFixture>, nowMillis: Long): Boolean = fixtures.any { fixture ->
        fixture.status == FixtureStatus.LIVE || (fixture.status == FixtureStatus.SCHEDULED && fixture.startMillis <= nowMillis + STARTING_MILLIS &&
            fixture.startMillis + durationMillis(fixture) > nowMillis)
    }

    fun backoff(failures: Int): Long = if (failures <= 0) 0 else minOf(MAX_BACKOFF_MILLIS, LIVE_MILLIS shl minOf(failures - 1, 10))

    fun due(entry: SportsCacheEntry?, nowMillis: Long): Boolean {
        if (entry == null) return true
        val failedAt = entry.failedAt
        if (entry.failures > 0 && failedAt != null && nowMillis - failedAt in 0 until backoff(entry.failures)) return false
        val fetched = entry.fetchedAt ?: return true
        if (nowMillis < fetched) return true
        return nowMillis - fetched >= if (active(entry.fixtures, nowMillis)) LIVE_MILLIS else FIXTURES_MILLIS
    }

    fun succeeded(fixtures: List<SportsFixture>, nowMillis: Long) = SportsCacheEntry(fixtures, nowMillis)

    fun failed(previous: SportsCacheEntry?, nowMillis: Long) =
        SportsCacheEntry(previous?.fixtures.orEmpty(), previous?.fetchedAt, nowMillis, minOf((previous?.failures ?: 0) + 1, 20))

    fun durationMillis(fixture: SportsFixture): Long = (SportsLeagues.byId(fixture.league)?.durationMinutes ?: 180) * 60_000L
}

object SportsFixtureSections {
    private const val STALE_MILLIS = 30L * 60 * 1000

    fun group(fixtures: List<SportsFixture>, nowMillis: Long, zone: ZoneId): List<Pair<FixtureSection, List<SportsFixture>>> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val order = SportsLeagues.ALL.withIndex().associate { it.value.id to it.index }
        val sections = fixtures.distinctBy { it.league to it.id }.mapNotNull { fixture ->
            val day = Instant.ofEpochMilli(fixture.startMillis).atZone(zone).toLocalDate()
            val section = when {
                fixture.status == FixtureStatus.FINAL -> null
                fixture.status == FixtureStatus.LIVE -> FixtureSection.LIVE
                fixture.startMillis + SportsRefresh.durationMillis(fixture) + STALE_MILLIS < nowMillis -> null
                day == today || (day.isBefore(today) && fixture.startMillis <= nowMillis) -> FixtureSection.TODAY
                day == today.plusDays(1) -> FixtureSection.TOMORROW
                day.isAfter(today.plusDays(1)) && day.isBefore(today.plusDays(SportsDays.DAYS.toLong())) -> FixtureSection.LATER
                else -> null
            }
            section?.let { it to fixture }
        }
        return FixtureSection.entries.mapNotNull { section ->
            sections.filter { it.first == section }.map { it.second }
                .sortedWith(compareBy({ it.startMillis }, { order[it.league] ?: Int.MAX_VALUE }, { it.title }))
                .takeIf { it.isNotEmpty() }?.let { section to it }
        }
    }
}

object SportsFixtureCodec {
    fun encode(entry: SportsCacheEntry): String = JSONObject().apply {
        put("version", 1)
        entry.fetchedAt?.let { put("fetched", it) }
        entry.failedAt?.let { put("failed", it) }
        put("failures", entry.failures)
        put("fixtures", JSONArray().apply { entry.fixtures.forEach { put(encode(it)) } })
    }.toString()

    fun decode(text: String): SportsCacheEntry? = runCatching {
        val json = JSONObject(text)
        if (json.optInt("version") != 1) return null
        val array = json.optJSONArray("fixtures") ?: JSONArray()
        SportsCacheEntry((0 until minOf(array.length(), MAX_FIXTURES)).mapNotNull { index -> array.optJSONObject(index)?.let(::fixture) },
            json.optLongOrNull("fetched"), json.optLongOrNull("failed"), json.optInt("failures").coerceIn(0, 20))
    }.getOrNull()

    private fun encode(fixture: SportsFixture) = JSONObject().apply {
        put("id", fixture.id); put("league", fixture.league); put("sport", fixture.sport); put("title", fixture.title)
        fixture.home?.let { put("home", team(it)) }; fixture.away?.let { put("away", team(it)) }
        put("start", fixture.startMillis); put("status", fixture.status.name)
        fixture.score?.let { put("score", it) }; fixture.detail?.let { put("detail", it) }
        put("broadcasters", JSONArray(fixture.broadcasters))
    }

    private fun team(team: FixtureTeam) = JSONObject().apply {
        put("name", team.name); team.shortName?.let { put("short", it) }; team.abbreviation?.let { put("abbreviation", it) }
        put("alternatives", JSONArray(team.alternatives))
    }

    private fun fixture(json: JSONObject): SportsFixture? {
        val id = json.text("id") ?: return null
        val league = json.text("league") ?: return null
        val status = FixtureStatus.entries.firstOrNull { it.name == json.text("status") } ?: return null
        if (!json.has("start")) return null
        return SportsFixture(id, league, json.text("sport").orEmpty(), json.text("title").orEmpty(), json.optJSONObject("home")?.let(::team),
            json.optJSONObject("away")?.let(::team), json.getLong("start"), status, json.text("score"), json.text("detail"), json.strings("broadcasters"))
    }

    private fun team(json: JSONObject): FixtureTeam? =
        json.text("name")?.let { FixtureTeam(it, json.text("short"), json.text("abbreviation"), json.strings("alternatives")) }

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf(String::isNotEmpty)
    private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)
    private fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { array ->
        (0 until minOf(array.length(), 32)).mapNotNull { index -> if (array.isNull(index)) null else array.optString(index).takeIf(String::isNotBlank) }
    }.orEmpty()

    const val MAX_FIXTURES = 400
}
