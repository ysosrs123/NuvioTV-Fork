package com.nuvio.tv.core.iptv

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject

enum class SportsService { OFF, ESPN, THESPORTSDB }
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
    val awayLine: FixtureLine? = null, val situation: FixtureSituation? = null, val leagueLogo: String? = null, val sportDetail: SportsDetail? = null) {
    val teams: Boolean get() = home != null && away != null
    val key: String get() = "$league:$id"
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
        SportsLeague("atp", "ATP", "tennis", "tennis/atp", null, null, listOf("atp", "atp tour", "atp tennis", "atp masters 1000", "atp 500", "atp 250"),
            durationMinutes = 150),
        SportsLeague("wta", "WTA", "tennis", "tennis/wta", null, null, listOf("wta", "wta tour", "wta tennis", "wta 1000", "wta 500", "wta 250"),
            women = true, durationMinutes = 120),
        SportsLeague("pga", "PGA Tour", "golf", "golf/pga", "PGA Tour", null, listOf("pga tour", "pga", "pga tour golf"), durationMinutes = 600),
        SportsLeague("nascar-cup", "NASCAR Cup", "motorsport", "racing/nascar-premier", "NASCAR Cup Series", null,
            listOf("nascar cup series", "nascar cup", "nascar"), durationMinutes = 240),
        SportsLeague("urc", "United Rugby Championship", "rugby", "rugby/270557", "United Rugby Championship", null,
            listOf("united rugby championship", "urc", "vodacom urc", "bkt united rugby championship"), durationMinutes = 135),
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
        favourites.isNotEmpty() && listOfNotNull(fixture.home, fixture.away).any { key(fixture.league, it) in favourites }

    fun toggle(favourites: Set<String>, league: String, team: FixtureTeam): Set<String> {
        val key = key(league, team)
        return if (key in favourites) favourites - key else (favourites + key).toList().takeLast(MAX).toSet()
    }

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

    fun decode(text: String): SportsCacheEntry? = runCatching {
        val json = JSONObject(text)
        val version = json.optInt("version")
        if (version != 1 && version != VERSION) return null
        val array = json.optJSONArray("fixtures") ?: JSONArray()
        SportsCacheEntry((0 until minOf(array.length(), MAX_FIXTURES)).mapNotNull { index -> array.optJSONObject(index)?.let(::fixture) },
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

    private fun line(line: FixtureLine) = JSONObject().apply { line.score?.let { put("score", it) }; put("periods", JSONArray(line.periods)) }

    private fun fixture(json: JSONObject): SportsFixture? {
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
            json.optJSONObject("awayLine")?.let(::line), situation, json.text("leagueLogo"), json.optJSONObject("sportDetail")?.let(SportsDetails::decode))
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
