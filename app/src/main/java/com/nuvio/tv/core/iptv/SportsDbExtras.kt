package com.nuvio.tv.core.iptv

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class SportsDbLiveScore(val eventId: String, val status: String, val progress: String? = null, val homeScore: String? = null, val awayScore: String? = null)

object SportsDbLive {
    const val MAX_SPORTS = 3
    const val FRESH_MILLIS = 10L * 60 * 1000
    private const val MAX_SCORES = 500
    private val PATHS = mapOf("soccer" to "Soccer", "basketball" to "Basketball", "ice-hockey" to "Ice_Hockey", "baseball" to "Baseball",
        "american-football" to "American_Football")
    private val SKIPPED = SportsDbEvents.SKIPPED + "post"
    private val MINUTE = Regex("[0-9]{1,3}(\\+[0-9]{1,2})?")
    val SPORTS: Set<String> = PATHS.keys

    fun path(sport: String): String? = PATHS[sport]

    fun wanted(fixtures: List<SportsFixture>, nowMillis: Long): List<String> =
        fixtures.filter { it.source == SportsService.THESPORTSDB && it.sport in PATHS && SportsRefresh.active(listOf(it), nowMillis) }
            .sortedBy { it.startMillis }.map { it.sport }.distinct().take(MAX_SPORTS)

    fun parse(json: String): List<SportsDbLiveScore> {
        val root = JSONObject(json)
        val array = listOf("livescore", "livescores", "events").firstNotNullOfOrNull { root.optJSONArray(it) } ?: return emptyList()
        return (0 until minOf(array.length(), MAX_SCORES)).mapNotNull { array.optJSONObject(it) }.mapNotNull { item ->
            val id = item.value("idEvent")?.takeIf { it.length <= 20 } ?: return@mapNotNull null
            SportsDbLiveScore(id, item.value("strStatus").orEmpty(), item.value("strProgress"), item.value("intHomeScore"), item.value("intAwayScore"))
        }
    }

    fun merge(fixtures: List<SportsFixture>, scores: Map<String, SportsDbLiveScore>, nowMillis: Long): List<SportsFixture> =
        if (scores.isEmpty()) fixtures else fixtures.map { fixture ->
            scores[fixture.id]?.takeIf { fixture.source == SportsService.THESPORTSDB && fixture.status != FixtureStatus.FINAL }?.let { apply(fixture, it, nowMillis) } ?: fixture
        }

    private fun apply(fixture: SportsFixture, score: SportsDbLiveScore, nowMillis: Long): SportsFixture {
        val raw = score.status.trim()
        val code = SportsGuide.normalise(raw).ifEmpty { SportsGuide.normalise(score.progress.orEmpty()).takeIf { it in SportsDbEvents.FINISHED }.orEmpty() }
        if (code in SKIPPED || code in SportsDbEvents.NOT_STARTED || (code.isEmpty() && fixture.startMillis > nowMillis)) return fixture
        val status = if (code in SportsDbEvents.FINISHED) FixtureStatus.FINAL else FixtureStatus.LIVE
        val live = status == FixtureStatus.LIVE
        val home = score.homeScore?.trim()?.takeIf { it.isNotEmpty() && it.length <= 8 }
        val away = score.awayScore?.trim()?.takeIf { it.isNotEmpty() && it.length <= 8 }
        val both = fixture.teams && home != null && away != null
        val progress = score.progress?.trim()?.takeIf { live && it.isNotEmpty() && it.length <= 8 }?.let { if (it.matches(MINUTE)) "$it'" else it }
        return fixture.copy(status = status, score = if (both) "$home–$away" else fixture.score, detail = raw.takeIf { live && it.isNotEmpty() && it.length <= 24 },
            period = if (live) SportsDbEvents.PERIODS[code] ?: fixture.period else null, clock = if (live) progress ?: fixture.clock else null,
            homeLine = if (both) FixtureLine(home) else fixture.homeLine, awayLine = if (both) FixtureLine(away) else fixture.awayLine)
    }
}

data class SportsTvChannel(val name: String, val country: String? = null)

object SportsTv {
    const val TTL_MILLIS = 24L * 60 * 60 * 1000
    const val MATCH_TTL_MILLIS = 12L * 60 * 60 * 1000
    const val MAX_FIXTURES = 12
    const val MAX_CHANNELS = 8
    private const val MATCH_WINDOW_MILLIS = 6L * 60 * 60 * 1000
    private val ALIASES = mapOf("US" to listOf("USA", "United States of America"), "GB" to listOf("UK", "England", "Scotland", "Wales", "Northern Ireland",
        "Great Britain"), "IE" to listOf("Republic of Ireland"), "KR" to listOf("Korea"), "NL" to listOf("Holland", "The Netherlands"), "CZ" to listOf("Czechia", "Czech Republic"),
        "TR" to listOf("Turkey", "Türkiye"))

    fun parse(json: String): List<SportsTvChannel> {
        val root = JSONObject(json)
        val array = root.keys().asSequence().mapNotNull { root.optJSONArray(it) }.firstOrNull() ?: return emptyList()
        return (0 until minOf(array.length(), 200)).mapNotNull { array.optJSONObject(it) }.mapNotNull { item ->
            item.value("strChannel")?.takeIf { it.length <= 80 }?.let { SportsTvChannel(it, item.value("strCountry")?.take(60)) }
        }.distinct().take(64)
    }

    fun names(channels: List<SportsTvChannel>, country: String?): List<String> {
        val wanted = countryNames(country)
        val local = channels.filter { channel -> channel.country?.let { SportsGuide.normalise(it) in wanted } == true }
        return local.ifEmpty { channels }.map { it.name }.distinct().take(MAX_CHANNELS)
    }

    fun countryNames(code: String?): Set<String> {
        val iso = code?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 && it.all { char -> char in 'A'..'Z' } } ?: return emptySet()
        val english = runCatching { Locale.Builder().setRegion(iso).build().getDisplayCountry(Locale.ENGLISH) }.getOrNull()
        return (listOfNotNull(english) + ALIASES[iso].orEmpty()).map(SportsGuide::normalise).filter { it.length >= 2 }.toSet()
    }

    fun wanted(fixtures: List<SportsFixture>, favourites: Set<String>, nowMillis: Long): List<SportsFixture> = fixtures.filter {
        it.status != FixtureStatus.FINAL && it.teams && SportsFavourites.has(favourites, it) && it.startMillis + SportsRefresh.durationMillis(it) > nowMillis
    }.sortedBy { it.startMillis }.distinctBy { it.key }.take(MAX_FIXTURES)

    fun match(fixture: SportsFixture, events: List<SportsFixture>): SportsFixture? {
        val home = fixture.home ?: return null
        val away = fixture.away ?: return null
        return events.filter { event ->
            val h = event.home
            val a = event.away
            event.source == SportsService.THESPORTSDB && h != null && a != null && kotlin.math.abs(event.startMillis - fixture.startMillis) <= MATCH_WINDOW_MILLIS &&
                ((same(home, h) && same(away, a)) || (same(home, a) && same(away, h)))
        }.minByOrNull { kotlin.math.abs(it.startMillis - fixture.startMillis) }
    }

    internal fun same(a: FixtureTeam, b: FixtureTeam): Boolean {
        val left = (a.strongNames + a.alternatives).flatMap(SportsFixtureMatching::variants).toSet()
        val right = (b.strongNames + b.alternatives).flatMap(SportsFixtureMatching::variants).toSet()
        return left.any { l -> right.any { r -> l == r || (minOf(l.length, r.length) >= 4 && (SportsFixtureMatching.has(l, r) || SportsFixtureMatching.has(r, l))) } }
    }

    fun add(fixtures: List<SportsFixture>, channels: Map<String, List<String>>): List<SportsFixture> = if (channels.isEmpty()) fixtures else fixtures.map { fixture ->
        channels[fixture.key]?.takeIf { it.isNotEmpty() }?.let { fixture.copy(broadcasters = (fixture.broadcasters + it).distinct().take(12)) } ?: fixture
    }
}

data class SportsDbLeague(val id: String, val name: String, val sport: String, val alternate: String? = null)

object SportsDbLeagues {
    const val CUSTOM_PREFIX = "sdb-"
    const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
    const val MAX_CUSTOM = 40
    private const val MAX_LEAGUES = 5000
    private val ID = Regex("[0-9]{1,10}")
    private val DURATIONS = mapOf("soccer" to 135, "basketball" to 165, "american-football" to 210, "baseball" to 210, "ice-hockey" to 180, "rugby" to 135,
        "rugby-league" to 135, "australian-football" to 180, "cricket" to 240, "motorsport" to 150, "mma" to 300, "tennis" to 150)

    fun parse(json: String): List<SportsDbLeague> {
        val root = JSONObject(json)
        val array = listOf("leagues", "all").firstNotNullOfOrNull { root.optJSONArray(it) }
            ?: root.keys().asSequence().mapNotNull { root.optJSONArray(it) }.firstOrNull() ?: return emptyList()
        return (0 until minOf(array.length(), MAX_LEAGUES)).mapNotNull { array.optJSONObject(it) }.mapNotNull { item ->
            val id = item.value("idLeague")?.takeIf { it.matches(ID) } ?: return@mapNotNull null
            val name = item.value("strLeague")?.takeUnless { it.startsWith('_') }?.take(120) ?: return@mapNotNull null
            SportsDbLeague(id, name, item.value("strSport")?.take(60).orEmpty(), item.value("strLeagueAlternate")?.take(240))
        }.distinctBy { it.id }
    }

    fun encode(fetchedAt: Long, leagues: List<SportsDbLeague>): String = JSONObject().put("fetched", fetchedAt).put("leagues", JSONArray().apply {
        leagues.forEach { league ->
            put(JSONObject().put("idLeague", league.id).put("strLeague", league.name).put("strSport", league.sport).apply { league.alternate?.let { put("strLeagueAlternate", it) } })
        }
    }).toString()

    fun decode(text: String): Pair<Long, List<SportsDbLeague>>? = runCatching { JSONObject(text).getLong("fetched") to parse(text) }.getOrNull()

    fun search(leagues: List<SportsDbLeague>, query: String, max: Int = 40): List<SportsDbLeague> {
        val wanted = SportsGuide.normalise(query)
        if (wanted.length < 2) return emptyList()
        return leagues.mapNotNull { league ->
            val name = SportsGuide.normalise(league.name)
            val all = SportsGuide.normalise(listOfNotNull(league.name, league.alternate, league.sport).joinToString(" "))
            val rank = when {
                name == wanted -> 0
                name.startsWith(wanted) -> 1
                SportsFixtureMatching.has(all, wanted) -> 2
                all.contains(wanted) -> 3
                else -> return@mapNotNull null
            }
            rank to league
        }.sortedWith(compareBy({ it.first }, { it.second.name.lowercase(Locale.ROOT) })).map { it.second }.take(max)
    }

    fun sport(name: String): String = when (val plain = SportsGuide.normalise(name)) {
        "ice hockey" -> "ice-hockey"
        "american football" -> "american-football"
        "australian football" -> "australian-football"
        "fighting" -> "mma"
        else -> plain.replace(' ', '-').ifEmpty { "other" }
    }

    fun builtIn(league: SportsDbLeague): SportsLeague? = SportsLeagues.ALL.firstOrNull { it.sportsDbId == league.id }

    fun league(item: SportsDbLeague): SportsLeague {
        val sport = sport(item.sport)
        val aliases = (listOf(item.name) + item.alternate?.split(',').orEmpty()).map(SportsGuide::normalise).filter { it.length >= 3 }.distinct().take(8)
        return SportsLeague(CUSTOM_PREFIX + item.id, item.name, sport, null, item.name, item.id, aliases, durationMinutes = DURATIONS[sport] ?: 180)
    }

    fun encodeCustom(league: SportsLeague): String = JSONObject().put("id", league.sportsDbId).put("name", league.name).put("sport", league.sport)
        .put("aliases", JSONArray(league.aliases)).put("minutes", league.durationMinutes).toString()

    fun decodeCustom(text: String): SportsLeague? = runCatching {
        val json = JSONObject(text)
        val id = json.getString("id").takeIf { it.matches(ID) } ?: return null
        val name = json.getString("name").trim().take(120).takeIf { it.isNotEmpty() } ?: return null
        val aliases = json.optJSONArray("aliases")?.let { array -> (0 until minOf(array.length(), 8)).mapNotNull { array.optString(it).takeIf(String::isNotBlank) } }.orEmpty()
        SportsLeague(CUSTOM_PREFIX + id, name, json.optString("sport").take(60).ifEmpty { "other" }, null, name, id, aliases,
            durationMinutes = json.optInt("minutes", 180).coerceIn(30, 720))
    }.getOrNull()
}

private fun JSONObject.value(key: String): String? = if (isNull(key)) null else when (val value = opt(key)) {
    is String -> value.trim().takeIf(String::isNotEmpty)
    is Number, is Boolean -> value.toString()
    else -> null
}
