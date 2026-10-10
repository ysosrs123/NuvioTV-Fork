package com.nuvio.tv.core.iptv

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject

enum class SportsService { ESPN, THESPORTSDB }
enum class FixtureStatus { SCHEDULED, LIVE, FINAL }
enum class FixtureSection { LIVE, CLOSE, TODAY, TOMORROW, DAY, FINISHED }
enum class FixtureSide { HOME, AWAY }

data class FixtureTeam(val name: String, val shortName: String? = null, val abbreviation: String? = null, val alternatives: List<String> = emptyList(),
    val logo: String? = null, val colour: String? = null, val record: String? = null) {
    val strongNames: List<String> get() = listOfNotNull(name, shortName).filter(String::isNotBlank).distinct()
    val weakNames: List<String> get() = (alternatives + listOfNotNull(abbreviation)).filter(String::isNotBlank).distinct() - strongNames.toSet()
}

data class FixtureLine(val score: String? = null, val periods: List<String> = emptyList())

data class FixtureSituation(val downDistance: String? = null, val possession: FixtureSide? = null, val lastPlay: String? = null, val homeWinPercent: Int? = null)

data class SportsFixture(val id: String, val league: String, val sport: String, val title: String, val home: FixtureTeam?, val away: FixtureTeam?,
    val startMillis: Long, val status: FixtureStatus, val score: String? = null, val detail: String? = null, val broadcasters: List<String> = emptyList(),
    val venue: String? = null, val round: Int? = null, val period: Int? = null, val clock: String? = null, val homeLine: FixtureLine? = null,
    val awayLine: FixtureLine? = null, val situation: FixtureSituation? = null, val leagueLogo: String? = null, val sportDetail: SportsDetail? = null,
    val events: List<FixtureEvent> = emptyList(), val source: SportsService = SportsService.ESPN) {
    val teams: Boolean get() = home != null && away != null
    val key: String get() = if (source == SportsService.THESPORTSDB) "$league:sdb-$id" else "$league:$id"
}

data class SportsLeague(val id: String, val name: String, val sport: String, val espn: String?, val sportsDb: String?, val sportsDbId: String? = null,
    val aliases: List<String> = emptyList(), val women: Boolean = false, val durationMinutes: Int = 180) {
    val custom: Boolean get() = id.startsWith(SportsDbLeagues.CUSTOM_PREFIX)

    fun supports(service: SportsService): Boolean = when (service) {
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
        SportsLeague("a-league-women", "A-League Women", "soccer", "soccer/aus.w.1", "Australian A-League Women", "4805",
            listOf("a league women", "a leagues", "liberty a league"), women = true, durationMinutes = 135),
        SportsLeague("super-rugby", "Super Rugby Pacific", "rugby", "rugby/242041", "Super Rugby", "4551",
            listOf("super rugby", "super rugby pacific"), durationMinutes = 135),
        SportsLeague("big-bash", "Big Bash League", "cricket", null, "Australian Big Bash League", "4461",
            listOf("big bash", "bbl", "kfc bbl", "big bash league"), durationMinutes = 240),
        SportsLeague("epl", "Premier League", "soccer", "soccer/eng.1", "English Premier League", "4328",
            listOf("premier league", "epl", "english premier league"), durationMinutes = 135),
        SportsLeague("champions-league", "Champions League", "soccer", "soccer/uefa.champions", "UEFA Champions League", "4480",
            listOf("champions league", "uefa champions league", "ucl"), durationMinutes = 135),
        SportsLeague("la-liga", "La Liga", "soccer", "soccer/esp.1", "Spanish La Liga", "4335", listOf("la liga", "laliga", "laliga ea sports"), durationMinutes = 135),
        SportsLeague("serie-a", "Serie A", "soccer", "soccer/ita.1", "Italian Serie A", "4332", listOf("serie a"), durationMinutes = 135),
        SportsLeague("bundesliga", "Bundesliga", "soccer", "soccer/ger.1", "German Bundesliga", "4331", listOf("bundesliga"), durationMinutes = 135),
        SportsLeague("ligue-1", "Ligue 1", "soccer", "soccer/fra.1", "French Ligue 1", "4334", listOf("ligue 1", "french ligue 1", "ligue 1 mcdonalds"), durationMinutes = 135),
        SportsLeague("europa-league", "Europa League", "soccer", "soccer/uefa.europa", "UEFA Europa League", "4481",
            listOf("europa league", "uefa europa league", "uel"), durationMinutes = 135),
        SportsLeague("conference-league", "Conference League", "soccer", "soccer/uefa.europa.conf", "UEFA Conference League", "5071",
            listOf("conference league", "uefa conference league", "europa conference league", "uecl"), durationMinutes = 135),
        SportsLeague("championship", "EFL Championship", "soccer", "soccer/eng.2", "English League Championship", "4329",
            listOf("efl championship", "sky bet championship", "english championship"), durationMinutes = 135),
        SportsLeague("fa-cup", "FA Cup", "soccer", "soccer/eng.fa", "FA Cup", "4482", listOf("fa cup", "emirates fa cup"), durationMinutes = 135),
        SportsLeague("efl-cup", "EFL Cup", "soccer", "soccer/eng.league_cup", "EFL Cup", "4570", listOf("efl cup", "carabao cup"), durationMinutes = 135),
        SportsLeague("scottish-premiership", "Scottish Premiership", "soccer", "soccer/sco.1", "Scottish Premier League", "4330",
            listOf("scottish premiership", "spfl", "william hill premiership"), durationMinutes = 135),
        SportsLeague("eredivisie", "Eredivisie", "soccer", "soccer/ned.1", "Dutch Eredivisie", "4337", listOf("eredivisie", "dutch eredivisie"), durationMinutes = 135),
        SportsLeague("primeira-liga", "Primeira Liga", "soccer", "soccer/por.1", "Portuguese Primeira Liga", "4344",
            listOf("primeira liga", "liga portugal", "liga portugal betclic"), durationMinutes = 135),
        SportsLeague("super-lig", "Süper Lig", "soccer", "soccer/tur.1", "Turkish Super Lig", "4339", listOf("super lig", "turkish super lig", "trendyol super lig"),
            durationMinutes = 135),
        SportsLeague("saudi-pro-league", "Saudi Pro League", "soccer", "soccer/ksa.1", "Saudi-Arabian Pro League", "4668",
            listOf("saudi pro league", "roshn saudi league", "saudi professional league"), durationMinutes = 135),
        SportsLeague("mls", "MLS", "soccer", "soccer/usa.1", "American Major League Soccer", "4346", listOf("mls", "major league soccer"), durationMinutes = 135),
        SportsLeague("liga-mx", "Liga MX", "soccer", "soccer/mex.1", "Mexican Liga MX", "4350", listOf("liga mx", "liga bbva mx"), durationMinutes = 135),
        SportsLeague("wsl", "Women's Super League", "soccer", "soccer/eng.w.1", "English Womens Super League", "4849",
            listOf("womens super league", "wsl", "barclays wsl", "barclays womens super league"), women = true, durationMinutes = 135),
        SportsLeague("nwsl", "NWSL", "soccer", "soccer/usa.nwsl", "American NWSL", "4521", listOf("nwsl", "national womens soccer league"), women = true,
            durationMinutes = 135),
        SportsLeague("womens-champions-league", "Women's Champions League", "soccer", "soccer/uefa.wchampions", null, null,
            listOf("womens champions league", "uefa womens champions league", "uwcl"), women = true, durationMinutes = 135),
        SportsLeague("nba", "NBA", "basketball", "basketball/nba", "NBA", "4387", listOf("nba", "basketball"), durationMinutes = 165),
        SportsLeague("nbl", "NBL", "basketball", "basketball/nbl", null, null,
            listOf("nbl", "nbl basketball", "national basketball league", "hungry jacks nbl"), durationMinutes = 150),
        SportsLeague("wnba", "WNBA", "basketball", "basketball/wnba", null, null, listOf("wnba", "wnba basketball"), women = true, durationMinutes = 150),
        SportsLeague("g-league", "NBA G League", "basketball", "basketball/nba-development", "NBA G League", "4388", listOf("g league", "nba g league"),
            durationMinutes = 150),
        SportsLeague("womens-college-basketball", "Women's College Basketball", "basketball", "basketball/womens-college-basketball",
            "NCAA Division I Basketball Women", "5789", listOf("womens college basketball", "ncaa womens basketball", "ncaaw"), women = true, durationMinutes = 150),
        SportsLeague("nfl", "NFL", "american-football", "football/nfl", "NFL", "4391", listOf("nfl"), durationMinutes = 210),
        SportsLeague("college-football", "College Football", "american-football", "football/college-football", null, null,
            listOf("college football", "ncaaf", "ncaa football", "cfb"), durationMinutes = 225),
        SportsLeague("cfl", "CFL", "american-football", "football/cfl", "CFL", "4405", listOf("cfl", "canadian football league", "cfl football"), durationMinutes = 210),
        SportsLeague("ufl", "UFL", "american-football", "football/ufl", "UFL", "5434", listOf("ufl", "united football league"), durationMinutes = 210),
        SportsLeague("mlb", "MLB", "baseball", "baseball/mlb", "MLB", "4424", listOf("mlb", "baseball"), durationMinutes = 210),
        SportsLeague("nhl", "NHL", "ice-hockey", "hockey/nhl", "NHL", "4380", listOf("nhl", "ice hockey", "hockey"), durationMinutes = 180),
        SportsLeague("college-hockey", "College Hockey", "ice-hockey", "hockey/mens-college-hockey", "NCAA Division 1 Ice Hockey", "5346",
            listOf("college hockey", "ncaa hockey", "ncaa ice hockey"), durationMinutes = 180),
        SportsLeague("womens-college-hockey", "Women's College Hockey", "ice-hockey", "hockey/womens-college-hockey", null, null,
            listOf("womens college hockey", "ncaa womens hockey"), women = true, durationMinutes = 180),
        SportsLeague("f1", "Formula 1", "motorsport", "racing/f1", "Formula 1", "4370", listOf("formula 1", "formula one", "f1"), durationMinutes = 150),
        SportsLeague("ufc", "UFC", "mma", "mma/ufc", "UFC", "4443", listOf("ufc", "mma"), durationMinutes = 300),
        SportsLeague("pfl", "PFL", "mma", "mma/pfl", "Professional Fighters League", "5430", listOf("pfl", "professional fighters league"), durationMinutes = 300),
        SportsLeague("atp", "ATP", "tennis", "tennis/atp", null, null, listOf("atp", "atp tour", "atp tennis", "atp masters 1000", "atp 500", "atp 250"),
            durationMinutes = 150),
        SportsLeague("wta", "WTA", "tennis", "tennis/wta", null, null, listOf("wta", "wta tour", "wta tennis", "wta 1000", "wta 500", "wta 250"),
            women = true, durationMinutes = 120),
        SportsLeague("pga", "PGA Tour", "golf", "golf/pga", "PGA Tour", "4425", listOf("pga tour", "pga", "pga tour golf"), durationMinutes = 600),
        SportsLeague("lpga", "LPGA Tour", "golf", "golf/lpga", "LPGA Tour", "4553", listOf("lpga", "lpga tour"), women = true, durationMinutes = 600),
        SportsLeague("dp-world-tour", "DP World Tour", "golf", "golf/eur", "European Tour", "4426", listOf("dp world tour", "european tour"), durationMinutes = 600),
        SportsLeague("liv", "LIV Golf", "golf", "golf/liv", "LIV Golf", "5329", listOf("liv golf"), durationMinutes = 360),
        SportsLeague("nascar-cup", "NASCAR Cup", "motorsport", "racing/nascar-premier", "NASCAR Cup Series", "4393",
            listOf("nascar cup series", "nascar cup", "nascar"), durationMinutes = 240),
        SportsLeague("nascar-xfinity", "NASCAR Xfinity", "motorsport", "racing/nascar-secondary", null, null, listOf("nascar xfinity series", "xfinity series"),
            durationMinutes = 180),
        SportsLeague("nascar-truck", "NASCAR Truck Series", "motorsport", "racing/nascar-truck", "NASCAR Truck Series", "5093",
            listOf("nascar truck series", "craftsman truck series"), durationMinutes = 150),
        SportsLeague("indycar", "IndyCar", "motorsport", "racing/irl", "IndyCar Series", "4373", listOf("indycar", "indycar series", "ntt indycar series"),
            durationMinutes = 180),
        SportsLeague("urc", "United Rugby Championship", "rugby", "rugby/270557", "United Rugby Championship", "4446",
            listOf("united rugby championship", "urc", "vodacom urc", "bkt united rugby championship"), durationMinutes = 135),
        SportsLeague("premiership-rugby", "Premiership Rugby", "rugby", "rugby/267979", "English Prem Rugby", "4414",
            listOf("premiership rugby", "gallagher premiership", "prem rugby"), durationMinutes = 135),
        SportsLeague("top-14", "Top 14", "rugby", "rugby/270559", "French Top 14", "4430", listOf("top 14", "top14"), durationMinutes = 135),
        SportsLeague("champions-cup", "Champions Cup", "rugby", "rugby/271937", "European Rugby Champions Cup", "4550",
            listOf("investec champions cup", "european rugby champions cup", "champions cup rugby"), durationMinutes = 135),
        SportsLeague("six-nations", "Six Nations", "rugby", "rugby/180659", "Six Nations Championship", "4714", listOf("six nations", "guinness six nations", "6 nations"),
            durationMinutes = 135),
        SportsLeague("rugby-championship", "The Rugby Championship", "rugby", "rugby/244293", "Rugby Championship", "4986",
            listOf("the rugby championship", "rugby championship"), durationMinutes = 135),
    )
    val DEFAULTS = setOf("afl", "nrl", "a-league-men", "epl", "champions-league")
    private val byId = ALL.associateBy { it.id }
    @Volatile var custom: List<SportsLeague> = emptyList()

    fun byId(id: String): SportsLeague? = byId[id] ?: custom.firstOrNull { it.id == id }

    fun all(): List<SportsLeague> = ALL + custom

    fun chosen(ids: Set<String>): List<SportsLeague> = all().filter { it.id in ids }
}

object SportsDays {
    const val DEFAULT_DAYS = 3
    const val MAX_DAYS = 8
    const val NEAR_DAYS = 3
    const val FAR_REFRESH_MILLIS = 3L * 60 * 60 * 1000
    val ESPN_ZONE: ZoneId = ZoneId.of("America/New_York")
    val SPORTSDB_ZONE: ZoneId = ZoneOffset.UTC
    @Volatile private var days = DEFAULT_DAYS
    val DAYS: Int get() = days

    fun guideDays(future: Int) { days = (future + 1).coerceIn(DEFAULT_DAYS, MAX_DAYS) }

    fun window(nowMillis: Long, zone: ZoneId, count: Int = DAYS): Pair<Long, Long> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return today.atStartOfDay(zone).toInstant().toEpochMilli() to today.plusDays(count.coerceIn(1, MAX_DAYS).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun serviceDates(fromMillis: Long, untilMillis: Long, zone: ZoneId): List<LocalDate> {
        require(untilMillis > fromMillis)
        val first = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(untilMillis - 1).atZone(zone).toLocalDate()
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.take(MAX_DAYS + 2).toList()
    }

    fun far(date: LocalDate, nowMillis: Long, zone: ZoneId): Boolean = date.isAfter(Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().plusDays(NEAR_DAYS.toLong()))

    fun due(date: LocalDate, zone: ZoneId, entry: SportsCacheEntry?, nowMillis: Long): Boolean {
        if (!far(date, nowMillis, zone)) return true
        val fetched = entry?.fetchedAt ?: return true
        return nowMillis < fetched || nowMillis - fetched >= FAR_REFRESH_MILLIS
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

object SportsCatchup {
    const val WINDOW_MILLIS = 12L * 60 * 60 * 1000
    const val MAX_FIXTURES = 24

    fun recent(fixtures: List<SportsFixture>, nowMillis: Long, max: Int = MAX_FIXTURES): List<SportsFixture> =
        fixtures.filter { it.status == FixtureStatus.FINAL && it.startMillis <= nowMillis && nowMillis - minOf(end(it), nowMillis) <= WINDOW_MILLIS }
            .distinctBy { it.key }.sortedByDescending(::end).take(max)

    fun end(fixture: SportsFixture): Long = (fixture.sportDetail as? SportsDetail.Golf)?.endMillis ?: (fixture.startMillis + SportsRefresh.durationMillis(fixture))
}

data class FixtureRow(val section: FixtureSection, val fixtures: List<SportsFixture>, val day: LocalDate? = null)

object SportsFixtureSections {
    private const val STALE_MILLIS = 30L * 60 * 1000

    fun group(fixtures: List<SportsFixture>, nowMillis: Long, zone: ZoneId, showScores: Boolean = true, favourites: Set<String> = emptySet()): List<FixtureRow> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val order = SportsLeagues.ALL.withIndex().associate { it.value.id to it.index }
        val placed = fixtures.distinctBy { it.league to it.id }.mapNotNull { fixture ->
            val day = Instant.ofEpochMilli(fixture.startMillis).atZone(zone).toLocalDate()
            val section = when {
                fixture.status == FixtureStatus.FINAL -> FixtureSection.FINISHED.takeIf { showScores && day == today }
                fixture.status == FixtureStatus.LIVE -> FixtureSection.LIVE
                fixture.startMillis + SportsRefresh.durationMillis(fixture) + STALE_MILLIS < nowMillis -> null
                day == today || (day.isBefore(today) && fixture.startMillis <= nowMillis) -> FixtureSection.TODAY
                day == today.plusDays(1) -> FixtureSection.TOMORROW
                day.isAfter(today.plusDays(1)) && day.isBefore(today.plusDays(SportsDays.DAYS.toLong())) -> FixtureSection.DAY
                else -> null
            }
            section?.let { Triple(it, day, fixture) }
        }
        val ascending = compareBy<SportsFixture>({ !SportsFavourites.has(favourites, it) }, { it.startMillis }, { order[it.league] ?: Int.MAX_VALUE }, { it.title })
        fun of(section: FixtureSection) = placed.filter { it.first == section }.map { it.third }
        val live = of(FixtureSection.LIVE).sortedWith(ascending)
        val close = if (showScores) live.filter(::close) else emptyList()
        val rows = mutableListOf<FixtureRow>()
        if (live.isNotEmpty()) rows += FixtureRow(FixtureSection.LIVE, live)
        if (close.isNotEmpty() && close.size < live.size) rows += FixtureRow(FixtureSection.CLOSE, close)
        of(FixtureSection.TODAY).takeIf { it.isNotEmpty() }?.let { rows += FixtureRow(FixtureSection.TODAY, it.sortedWith(ascending), today) }
        of(FixtureSection.TOMORROW).takeIf { it.isNotEmpty() }?.let { rows += FixtureRow(FixtureSection.TOMORROW, it.sortedWith(ascending), today.plusDays(1)) }
        placed.filter { it.first == FixtureSection.DAY }.groupBy { it.second }.entries.sortedBy { it.key.toEpochDay() }.forEach { (day, items) ->
            rows += FixtureRow(FixtureSection.DAY, items.map { it.third }.sortedWith(ascending), day)
        }
        of(FixtureSection.FINISHED).takeIf { it.isNotEmpty() }?.let { finished ->
            rows += FixtureRow(FixtureSection.FINISHED, finished.sortedWith(compareBy<SportsFixture>({ !SportsFavourites.has(favourites, it) },
                { -it.startMillis }, { order[it.league] ?: Int.MAX_VALUE }, { it.title })), today)
        }
        return rows
    }

    fun close(fixture: SportsFixture): Boolean {
        if (fixture.status != FixtureStatus.LIVE) return false
        val margin = closeMargin(fixture.sport) ?: return false
        val (home, away) = SportsFixtureText.scores(fixture) ?: return false
        val a = home.toIntOrNull() ?: return false
        val b = away.toIntOrNull() ?: return false
        return kotlin.math.abs(a - b) <= margin
    }

    private fun closeMargin(sport: String): Int? = when (sport) {
        "soccer", "ice-hockey" -> 1
        "baseball" -> 2
        "basketball", "rugby-league" -> 6
        "rugby" -> 7
        "american-football" -> 8
        "australian-football" -> 12
        else -> null
    }
}

object SportsFavourites {
    const val MAX = 200

    fun key(league: String, team: FixtureTeam): String = "$league:${team.name.trim()}"

    fun has(favourites: Set<String>, fixture: SportsFixture): Boolean =
        favourites.isNotEmpty() && listOfNotNull(fixture.home, fixture.away).any { matching(favourites, fixture.league, it).isNotEmpty() }

    fun toggle(favourites: Set<String>, league: String, team: FixtureTeam): Set<String> {
        val found = matching(favourites, league, team)
        return if (found.isNotEmpty()) favourites - found else (favourites + key(league, team)).toList().takeLast(MAX).toSet()
    }

    fun matching(favourites: Set<String>, league: String, team: FixtureTeam): Set<String> {
        if (favourites.isEmpty()) return emptySet()
        val exact = key(league, team)
        if (exact in favourites) return setOf(exact)
        val names = (listOf(team.name) + listOfNotNull(team.shortName) + team.alternatives).map(::words).filter { it.isNotEmpty() }
        return favourites.filterTo(HashSet()) { favourite ->
            val (favouriteLeague, name) = parse(favourite) ?: return@filterTo false
            if (favouriteLeague != league) return@filterTo false
            val wanted = words(name)
            wanted.isNotEmpty() && names.any { it == wanted || (it.size > wanted.size && it.subList(0, wanted.size) == wanted) || (wanted.size > it.size && wanted.subList(0, it.size) == it) }
        }
    }

    private fun words(value: String): List<String> =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD).lowercase().replace(Regex("\\p{M}+"), "")
            .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    fun parse(key: String): Pair<String, String>? {
        val split = key.indexOf(':')
        if (split <= 0 || split == key.length - 1) return null
        return key.substring(0, split) to key.substring(split + 1)
    }
}

object SportsFixtureText {
    private val US_SPORTS = setOf("american-football", "basketball", "baseball", "ice-hockey")

    fun awayFirst(fixture: SportsFixture): Boolean = fixture.sport in US_SPORTS

    fun scores(fixture: SportsFixture): Pair<String, String>? {
        val home = fixture.homeLine?.score
        val away = fixture.awayLine?.score
        if (home != null && away != null) return home to away
        val parts = fixture.score?.split('–')?.map(String::trim)?.takeIf { it.size == 2 && it.all(String::isNotEmpty) } ?: return null
        return parts[0] to parts[1]
    }

    fun periodClock(fixture: SportsFixture): String? {
        if (fixture.status != FixtureStatus.LIVE) return null
        val label = fixture.period?.takeIf { it > 0 }?.let { periodLabel(fixture.sport, it) }
        val clock = fixture.clock?.trim()?.takeIf { it.isNotEmpty() && it != "0:00" && it != "0'" && it != "0" }
        return if (label != null && clock != null) "$label · $clock" else fixture.detail ?: label ?: clock
    }

    fun periodLabel(sport: String, period: Int): String? = when (sport) {
        "american-football", "basketball", "australian-football" -> if (period <= 4) "Q$period" else "OT"
        "ice-hockey" -> if (period <= 3) "P$period" else "OT"
        "soccer", "rugby", "rugby-league" -> if (period <= 2) "${period}H" else "ET"
        else -> null
    }

    fun bug(fixture: SportsFixture): SportsBugText = when (val detail = fixture.sportDetail) {
        is SportsDetail.Tennis -> tennis(fixture, detail)
        is SportsDetail.Golf -> golf(fixture, detail)
        is SportsDetail.Sessions -> sessions(fixture, detail)
        is SportsDetail.Card -> card(fixture, detail)
        is SportsDetail.Cricket -> cricket(fixture, detail) ?: teams(fixture)
        is SportsDetail.Baseball -> baseball(fixture, detail)
        null -> teams(fixture)
    }

    fun code(team: FixtureTeam): String = team.abbreviation?.takeIf { it.length <= 12 } ?: team.shortName ?: team.name

    private fun teams(fixture: SportsFixture): SportsBugText {
        val home = fixture.home
        val away = fixture.away
        val state = when (fixture.status) {
            FixtureStatus.LIVE -> if (fixture.sport == "soccer") fixture.clock?.trim()?.takeIf { it.isNotEmpty() && it != "0'" } ?: fixture.detail else periodClock(fixture)
            FixtureStatus.FINAL -> fixture.detail
            FixtureStatus.SCHEDULED -> null
        }
        if (home == null || away == null) return SportsBugText(fixture.title, state)
        val scores = scores(fixture)?.takeIf { fixture.status != FixtureStatus.SCHEDULED }
        val first = awayFirst(fixture)
        val primary = when {
            scores == null -> if (first) "${code(away)} @ ${code(home)}" else "${code(home)} v ${code(away)}"
            first -> "${code(away)} ${scores.second}–${scores.first} ${code(home)}"
            else -> "${code(home)} ${scores.first}–${scores.second} ${code(away)}"
        }
        val extra = fixture.situation?.downDistance?.takeIf { fixture.sport == "american-football" && fixture.status == FixtureStatus.LIVE }
        return SportsBugText(primary, state, extra)
    }

    private fun baseball(fixture: SportsFixture, detail: SportsDetail.Baseball): SportsBugText {
        val base = teams(fixture)
        if (fixture.status != FixtureStatus.LIVE) return base
        val inning = detail.inning
        val state = when {
            inning == null -> base.state
            detail.half == InningHalf.TOP -> "▲$inning"
            detail.half == InningHalf.BOTTOM -> "▼$inning"
            detail.half == InningHalf.MIDDLE -> "Mid $inning"
            detail.half == InningHalf.END -> "End $inning"
            else -> base.state
        }
        val outs = detail.outs?.let { if (it == 1) "1 out" else "$it outs" }
        val count = if (detail.balls != null && detail.strikes != null) "${detail.balls}–${detail.strikes}" else null
        return SportsBugText(base.primary, state, listOfNotNull(outs, count).joinToString(" · ").takeIf(String::isNotEmpty))
    }

    private fun tennis(fixture: SportsFixture, detail: SportsDetail.Tennis): SportsBugText {
        val home = fixture.home
        val away = fixture.away
        if (home == null || away == null) return SportsBugText(fixture.title, detail.round)
        if (fixture.status == FixtureStatus.SCHEDULED || detail.sets.isEmpty())
            return SportsBugText("${player(home)} v ${player(away)}", detail.round, detail.tournament)
        fun line(side: FixtureSide) = detail.sets.joinToString(" ") { set ->
            val games = (if (side == FixtureSide.HOME) set.home else set.away)?.toString() ?: "0"
            val tiebreak = (if (side == FixtureSide.HOME) set.homeTiebreak else set.awayTiebreak)?.takeIf { set.winner != null && set.winner != side }
            if (tiebreak != null) "$games($tiebreak)" else games
        }
        val primary = "${player(home)} ${line(FixtureSide.HOME)} / ${player(away)} ${line(FixtureSide.AWAY)}"
        if (fixture.status == FixtureStatus.FINAL) return SportsBugText(primary, fixture.detail ?: "Final", detail.round)
        val server = detail.server?.let { if (it == FixtureSide.HOME) home else away }?.let(::player)
        val extra = server?.let { if (detail.servingForSet) "$it serving for the set" else "$it serving" }
        return SportsBugText(primary, fixture.detail, extra)
    }

    private fun player(team: FixtureTeam): String = team.abbreviation ?: team.name.substringAfterLast(' ').uppercase()

    private fun golf(fixture: SportsFixture, detail: SportsDetail.Golf): SportsBugText {
        val state = detail.statusText ?: detail.round?.let { "Round $it" }
        val leader = detail.leaders.firstOrNull()?.takeIf { fixture.status != FixtureStatus.SCHEDULED } ?: return SportsBugText(detail.tournament, state)
        val primary = listOfNotNull(leader.shortName ?: leader.name, leader.toPar).joinToString(" ")
        val extra = leader.thru?.let { if (it == "F") "F" else "Thru $it" }
        return SportsBugText(primary, state, extra)
    }

    private fun sessions(fixture: SportsFixture, detail: SportsDetail.Sessions): SportsBugText {
        val session = detail.current ?: return SportsBugText(fixture.title, fixture.detail, detail.venue)
        val word = when (session.state) {
            FixtureStatus.LIVE -> "live"
            FixtureStatus.SCHEDULED -> "next"
            FixtureStatus.FINAL -> "finished"
        }
        return SportsBugText(fixture.title, "${session.name} · $word", detail.venue)
    }

    private fun card(fixture: SportsFixture, detail: SportsDetail.Card): SportsBugText {
        val bout = detail.live ?: detail.mainEvent ?: return SportsBugText(fixture.title, fixture.detail)
        val primary = "${fighter(bout.first)} v ${fighter(bout.second)}"
        return when {
            bout.state == FixtureStatus.LIVE -> SportsBugText(primary, "Round ${bout.round ?: 1}" + (bout.rounds?.let { " of $it" } ?: ""), bout.weightClass)
            fixture.status == FixtureStatus.FINAL -> SportsBugText(primary, fixture.detail ?: "Final", bout.weightClass)
            else -> SportsBugText(primary, bout.weightClass, bout.rounds?.let { "$it rounds" })
        }
    }

    private fun fighter(fighter: Fighter): String = fighter.shortName ?: fighter.name.substringAfterLast(' ')

    private fun cricket(fixture: SportsFixture, detail: SportsDetail.Cricket): SportsBugText? {
        if (detail.innings.isEmpty()) return null
        val latest = detail.innings.groupBy { it.team }.map { (team, list) -> list.last().let { "$team ${it.runs}" + if (it.wickets < 10) "/${it.wickets}" else "" } }
        val batting = detail.innings.lastOrNull { it.batting } ?: detail.innings.last()
        val state = when (fixture.status) {
            FixtureStatus.LIVE -> batting.overs?.let { "$it ov" } ?: fixture.detail
            else -> fixture.detail
        }
        val chase = detail.chase?.takeIf { fixture.status == FixtureStatus.LIVE }
        return SportsBugText(latest.joinToString(" · "), state, chase?.let { "Need ${it.runs} from ${it.balls}" })
    }
}

data class SportsBugText(val primary: String, val state: String? = null, val extra: String? = null)

object SportsFixtureCodec {
    private const val VERSION = 2

    fun encode(entry: SportsCacheEntry): String = JSONObject().apply {
        put("version", VERSION)
        entry.fetchedAt?.let { put("fetched", it) }
        entry.failedAt?.let { put("failed", it) }
        put("failures", entry.failures)
        put("fixtures", JSONArray().apply { entry.fixtures.forEach { put(encode(it)) } })
    }.toString()

    fun decode(text: String, source: SportsService = SportsService.ESPN): SportsCacheEntry? = runCatching {
        val json = JSONObject(text)
        val version = json.optInt("version")
        if (version != 1 && version != VERSION) return null
        val array = json.optJSONArray("fixtures") ?: JSONArray()
        SportsCacheEntry((0 until minOf(array.length(), MAX_FIXTURES)).mapNotNull { index -> array.optJSONObject(index)?.let { fixture(it, source) } },
            json.optLongOrNull("fetched").takeIf { version == VERSION }, json.optLongOrNull("failed"), json.optInt("failures").coerceIn(0, 20))
    }.getOrNull()

    private fun encode(fixture: SportsFixture) = JSONObject().apply {
        put("id", fixture.id); put("league", fixture.league); put("sport", fixture.sport); put("title", fixture.title)
        fixture.home?.let { put("home", team(it)) }; fixture.away?.let { put("away", team(it)) }
        put("start", fixture.startMillis); put("status", fixture.status.name)
        fixture.score?.let { put("score", it) }; fixture.detail?.let { put("detail", it) }
        put("broadcasters", JSONArray(fixture.broadcasters))
        fixture.venue?.let { put("venue", it) }; fixture.round?.let { put("round", it) }; fixture.period?.let { put("period", it) }
        fixture.clock?.let { put("clock", it) }; fixture.leagueLogo?.let { put("leagueLogo", it) }
        fixture.homeLine?.let { put("homeLine", line(it)) }; fixture.awayLine?.let { put("awayLine", line(it)) }
        fixture.sportDetail?.let { put("sportDetail", SportsDetails.encode(it)) }
        if (fixture.events.isNotEmpty()) put("events", JSONArray().apply { fixture.events.forEach { put(event(it)) } })
        put("source", fixture.source.name)
        fixture.situation?.let { situation ->
            put("situation", JSONObject().apply {
                situation.downDistance?.let { put("downDistance", it) }; situation.possession?.let { put("possession", it.name) }
                situation.lastPlay?.let { put("lastPlay", it) }; situation.homeWinPercent?.let { put("homeWin", it) }
            })
        }
    }

    private fun team(team: FixtureTeam) = JSONObject().apply {
        put("name", team.name); team.shortName?.let { put("short", it) }; team.abbreviation?.let { put("abbreviation", it) }
        put("alternatives", JSONArray(team.alternatives))
        team.logo?.let { put("logo", it) }; team.colour?.let { put("colour", it) }; team.record?.let { put("record", it) }
    }

    private fun event(event: FixtureEvent) = JSONObject().apply {
        put("kind", event.kind.name); event.side?.let { put("side", it.name) }; event.clock?.let { put("clock", it) }
        event.period?.let { put("period", it) }; event.player?.let { put("player", it) }
    }

    private fun events(json: JSONObject): List<FixtureEvent> = json.optJSONArray("events")?.let { array ->
        (0 until minOf(array.length(), SportsEvents.MAX_EVENTS)).mapNotNull { index -> array.optJSONObject(index) }.mapNotNull { item ->
            FixtureEventKind.entries.firstOrNull { it.name == item.text("kind") }?.let { kind ->
                FixtureEvent(kind, FixtureSide.entries.firstOrNull { it.name == item.text("side") }, item.text("clock"), item.optIntOrNull("period"), item.text("player"))
            }
        }
    }.orEmpty()

    private fun line(line: FixtureLine) = JSONObject().apply { line.score?.let { put("score", it) }; put("periods", JSONArray(line.periods)) }

    private fun fixture(json: JSONObject, source: SportsService): SportsFixture? {
        val id = json.text("id") ?: return null
        val league = json.text("league") ?: return null
        val status = FixtureStatus.entries.firstOrNull { it.name == json.text("status") } ?: return null
        if (!json.has("start")) return null
        val situation = json.optJSONObject("situation")?.let { item ->
            FixtureSituation(item.text("downDistance"), FixtureSide.entries.firstOrNull { it.name == item.text("possession") }, item.text("lastPlay"),
                item.optIntOrNull("homeWin")?.coerceIn(0, 100))
        }
        return SportsFixture(id, league, json.text("sport").orEmpty(), json.text("title").orEmpty(), json.optJSONObject("home")?.let(::team),
            json.optJSONObject("away")?.let(::team), json.getLong("start"), status, json.text("score"), json.text("detail"), json.strings("broadcasters"),
            json.text("venue"), json.optIntOrNull("round"), json.optIntOrNull("period"), json.text("clock"), json.optJSONObject("homeLine")?.let(::line),
            json.optJSONObject("awayLine")?.let(::line), situation, json.text("leagueLogo"), json.optJSONObject("sportDetail")?.let(SportsDetails::decode), events(json),
            SportsService.entries.firstOrNull { it.name == json.text("source") } ?: source)
    }

    private fun team(json: JSONObject): FixtureTeam? =
        json.text("name")?.let { FixtureTeam(it, json.text("short"), json.text("abbreviation"), json.strings("alternatives"), json.text("logo"), json.text("colour"), json.text("record")) }

    private fun line(json: JSONObject) = FixtureLine(json.text("score"), json.strings("periods"))

    private fun JSONObject.optIntOrNull(key: String): Int? = if (!has(key) || isNull(key)) null else optInt(key)
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf(String::isNotEmpty)
    private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)
    private fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { array ->
        (0 until minOf(array.length(), 32)).mapNotNull { index -> if (array.isNull(index)) null else array.optString(index).takeIf(String::isNotBlank) }
    }.orEmpty()

    const val MAX_FIXTURES = 400
}
