package com.nuvio.tv.core.iptv

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject

object EspnScoreboard {
    fun parse(json: String, league: SportsLeague): List<SportsFixture> {
        val root = JSONObject(json)
        val events = root.optJSONArray("events") ?: return emptyList()
        val leagueLogo = root.optJSONArray("leagues")?.let(::objects)?.firstOrNull()?.let(::logo)
        return objects(events).take(SportsFixtureCodec.MAX_FIXTURES).mapNotNull { event -> runCatching { fixture(event, league, leagueLogo) }.getOrNull() }
    }

    private fun fixture(event: JSONObject, league: SportsLeague, leagueLogo: String?): SportsFixture? {
        val id = event.text("id") ?: return null
        val competitions = event.optJSONArray("competitions")?.let(::objects).orEmpty()
        val competition = competitions.firstOrNull()
        val start = (competition?.text("date") ?: event.text("date"))?.let(::sportsInstant) ?: return null
        val statusJson = competition?.optJSONObject("status") ?: event.optJSONObject("status")
        val type = statusJson?.optJSONObject("type")
        val name = type?.text("name").orEmpty().uppercase()
        if (SKIPPED.any { name.contains(it) }) return null
        val status = when {
            type?.optBoolean("completed") == true || type?.text("state") == "post" -> FixtureStatus.FINAL
            type?.text("state") == "in" -> FixtureStatus.LIVE
            else -> FixtureStatus.SCHEDULED
        }
        val competitors = competition?.optJSONArray("competitors")?.let(::objects).orEmpty()
        val teams = competitors.takeIf { list -> list.size == 2 && list.all { it.optJSONObject("team") != null } }
        val home = teams?.firstOrNull { it.text("homeAway") == "home" } ?: teams?.getOrNull(0)
        val away = teams?.firstOrNull { it.text("homeAway") == "away" && it !== home } ?: teams?.firstOrNull { it !== home }
        val homeTeam = home?.let(::team)
        val awayTeam = away?.let(::team)
        val paired = homeTeam != null && awayTeam != null
        val title = if (paired) "${homeTeam!!.name} v ${awayTeam!!.name}" else event.text("name") ?: event.text("shortName") ?: return null
        val started = paired && status != FixtureStatus.SCHEDULED
        val homeScore = if (started) score(home) else null
        val awayScore = if (started) score(away) else null
        val score = if (homeScore != null && awayScore != null) "$homeScore–$awayScore" else null
        val broadcasters = (competitions + event).flatMap(::broadcasters).distinctBy { SportsGuide.normalise(it) }.take(12)
        val venue = (competition?.optJSONObject("venue") ?: event.optJSONObject("venue"))?.let { it.text("fullName") ?: it.text("displayName") }?.take(MAX_TEXT)
        val round = (event.optJSONObject("week") ?: competition?.optJSONObject("week"))?.optIntOrNull("number")?.takeIf { it in 1..99 }
        val period = statusJson?.optIntOrNull("period")?.takeIf { it in 0..30 && status == FixtureStatus.LIVE }
        val clock = statusJson?.text("displayClock")?.takeIf { status == FixtureStatus.LIVE && it.length <= 12 }
        val homeLine = if (started) FixtureLine(homeScore, periods(home)) else null
        val awayLine = if (started) FixtureLine(awayScore, periods(away)) else null
        val situation = if (paired && status == FixtureStatus.LIVE) competition?.optJSONObject("situation")?.let { situation(it, home!!, away!!) } else null
        return SportsFixture(id, league.id, league.sport, title, homeTeam.takeIf { paired }, awayTeam.takeIf { paired }, start, status, score,
            type?.text("shortDetail") ?: type?.text("detail"), broadcasters, venue, round, period, clock, homeLine, awayLine, situation, leagueLogo)
    }

    private fun team(competitor: JSONObject): FixtureTeam? {
        val json = competitor.optJSONObject("team") ?: return null
        val name = json.text("displayName") ?: json.text("name") ?: return null
        val short = json.text("shortDisplayName")?.takeIf { it != name }
        val alternatives = listOfNotNull(json.text("location"), json.text("name"), json.text("nickname"), json.text("alternateDisplayName"))
            .filter { it != name && it != short }.distinct()
        val records = competitor.optJSONArray("records")?.let(::objects).orEmpty()
        val record = (records.firstOrNull { it.text("type") == "total" || it.text("name")?.lowercase() == "overall" } ?: records.firstOrNull())
            ?.text("summary")?.takeIf { it.length <= 24 }
        val colour = json.text("color")?.removePrefix("#")?.takeIf { COLOUR.matches(it) }?.lowercase()
        return FixtureTeam(name, short, json.text("abbreviation"), alternatives, json.text("logo")?.let(::sportsImage) ?: logo(json), colour, record)
    }

    private fun logo(json: JSONObject): String? = json.optJSONArray("logos")?.let(::objects)?.firstNotNullOfOrNull { it.text("href")?.let(::sportsImage) }

    private fun score(competitor: JSONObject?): String? {
        competitor ?: return null
        if (competitor.isNull("score")) return null
        val value = competitor.opt("score")
        return when (value) {
            is JSONObject -> value.text("displayValue") ?: value.text("value")?.let(::wholeNumber)
            else -> competitor.text("score")
        }?.takeIf { it.length <= 24 }
    }

    private fun periods(competitor: JSONObject?): List<String> = competitor?.optJSONArray("linescores")?.let(::objects).orEmpty().take(MAX_PERIODS).mapNotNull { line ->
        (line.text("displayValue") ?: line.text("value")?.let(::wholeNumber))?.takeIf { it.length <= 8 }
    }

    private fun situation(json: JSONObject, home: JSONObject, away: JSONObject): FixtureSituation? {
        val short = json.text("shortDownDistanceText")
        val spot = json.text("possessionText")
        val downDistance = (if (short != null && spot != null) "$short · $spot" else json.text("downDistanceText") ?: short)?.take(MAX_TEXT)
        val owner = json.text("possession")
        fun ids(competitor: JSONObject) = setOfNotNull(competitor.text("id"), competitor.optJSONObject("team")?.text("id"))
        val possession = when {
            owner == null -> null
            owner in ids(home) -> FixtureSide.HOME
            owner in ids(away) -> FixtureSide.AWAY
            else -> null
        }
        val last = json.optJSONObject("lastPlay")
        val lastPlay = last?.text("text")?.take(MAX_PLAY)
        val probability = last?.optJSONObject("probability") ?: json.optJSONObject("probability")
        val homeWin = probability?.let { if (it.isNull("homeWinPercentage")) null else it.optDouble("homeWinPercentage", Double.NaN) }
            ?.takeIf { !it.isNaN() && it >= 0 }?.let { if (it <= 1.0) it * 100 else it }?.takeIf { it <= 100.0 }?.let { Math.round(it).toInt() }
        if (downDistance == null && possession == null && lastPlay == null && homeWin == null) return null
        return FixtureSituation(downDistance, possession, lastPlay, homeWin)
    }

    private fun broadcasters(json: JSONObject): List<String> {
        val names = mutableListOf<String>()
        json.optJSONArray("broadcasts")?.let(::objects)?.forEach { broadcast ->
            broadcast.optJSONArray("names")?.let { array -> (0 until array.length()).forEach { index -> array.optString(index).takeIf(String::isNotBlank)?.let(names::add) } }
            broadcast.optJSONObject("media")?.text("shortName")?.let(names::add)
        }
        json.optJSONArray("geoBroadcasts")?.let(::objects)?.forEach { broadcast ->
            broadcast.optJSONObject("media")?.let { media -> (media.text("shortName") ?: media.text("name"))?.let(names::add) }
        }
        return names.map(String::trim).filter { it.isNotEmpty() && it.length <= 80 }
    }

    private val SKIPPED = listOf("POSTPONED", "CANCELED", "CANCELLED", "ABANDONED", "FORFEIT", "SUSPENDED", "DELAYED")
    private val COLOUR = Regex("[0-9A-Fa-f]{6}")
    private const val MAX_PERIODS = 12
}

object SportsDbEvents {
    fun parse(json: String, league: SportsLeague, nowMillis: Long): List<SportsFixture> {
        val events = JSONObject(json).optJSONArray("events") ?: return emptyList()
        return objects(events).take(SportsFixtureCodec.MAX_FIXTURES).mapNotNull { event -> runCatching { fixture(event, league, nowMillis) }.getOrNull() }
    }

    private fun fixture(event: JSONObject, league: SportsLeague, nowMillis: Long): SportsFixture? {
        val id = event.text("idEvent") ?: return null
        val leagueId = event.text("idLeague")
        val leagueName = event.text("strLeague")
        if (leagueId != null || leagueName != null) {
            val sameId = league.sportsDbId != null && leagueId == league.sportsDbId
            val sameName = leagueName != null && SportsGuide.normalise(leagueName) == SportsGuide.normalise(league.sportsDb.orEmpty())
            if (!sameId && !sameName) return null
        }
        if (event.text("strPostponed")?.equals("yes", ignoreCase = true) == true) return null
        val start = start(event) ?: return null
        val raw = event.text("strStatus").orEmpty().trim()
        val code = SportsGuide.normalise(raw)
        if (code in SKIPPED) return null
        val homeScore = event.text("intHomeScore")
        val awayScore = event.text("intAwayScore")
        val status = when {
            code in FINISHED -> FixtureStatus.FINAL
            code in NOT_STARTED || code.isEmpty() -> if (code.isEmpty() && homeScore != null && start < nowMillis - 4L * 60 * 60 * 1000) FixtureStatus.FINAL else FixtureStatus.SCHEDULED
            start > nowMillis -> FixtureStatus.SCHEDULED
            else -> FixtureStatus.LIVE
        }
        val home = event.text("strHomeTeam")?.let { FixtureTeam(it, event.text("strHomeTeamShort"), null, emptyList(), event.text("strHomeTeamBadge")?.let(::sportsImage)) }
        val away = event.text("strAwayTeam")?.let { FixtureTeam(it, event.text("strAwayTeamShort"), null, emptyList(), event.text("strAwayTeamBadge")?.let(::sportsImage)) }
        val paired = home != null && away != null
        val title = if (paired) "${home!!.name} v ${away!!.name}" else event.text("strEvent") ?: return null
        val score = if (paired && status != FixtureStatus.SCHEDULED && homeScore != null && awayScore != null) "$homeScore–$awayScore" else null
        val stations = event.text("strTVStation")?.split(',', ';', '/', '|')?.map(String::trim)?.filter { it.isNotEmpty() && it.length <= 80 }.orEmpty()
        val started = paired && status != FixtureStatus.SCHEDULED
        val live = status == FixtureStatus.LIVE
        val period = PERIODS[code]?.takeIf { live }
        val progress = event.text("strProgress")?.takeIf { live && it.length <= 8 }?.let { if (it.all(Char::isDigit)) "$it'" else it }
        return SportsFixture(id, league.id, league.sport, title, home.takeIf { paired }, away.takeIf { paired }, start, status, score,
            raw.takeIf { live && it.isNotEmpty() && it.length <= 24 }, stations.distinct().take(12), event.text("strVenue")?.take(MAX_TEXT),
            event.text("intRound")?.toIntOrNull()?.takeIf { it in 1..99 }, period, progress, if (started) FixtureLine(homeScore) else null,
            if (started) FixtureLine(awayScore) else null, null, event.text("strLeagueBadge")?.let(::sportsImage))
    }

    private fun start(event: JSONObject): Long? {
        event.text("strTimestamp")?.let { sportsInstant(it) ?: parseLocal(it) }?.let { return it }
        val date = event.text("dateEvent")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val time = event.text("strTime")?.let { value ->
            runCatching { OffsetDateTime.parse("${date}T$value").toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { LocalDateTime.of(date, LocalTime.parse(value)).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        }
        return time
    }

    private fun parseLocal(value: String): Long? = runCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()

    private val FINISHED = setOf("match finished", "ft", "aet", "pen", "ft pen", "aot", "final", "finished", "after over time", "after extra time", "after penalties", "ended", "full time")
    private val NOT_STARTED = setOf("not started", "ns", "tbd", "time to be defined", "scheduled")
    private val PERIODS = mapOf("1h" to 1, "2h" to 2, "et" to 3, "q1" to 1, "q2" to 2, "q3" to 3, "q4" to 4, "p1" to 1, "p2" to 2, "p3" to 3)
    private val SKIPPED = setOf("postponed", "pst", "canc", "cancelled", "canceled", "abandoned", "abd", "awd", "wo", "susp", "suspended", "int", "interrupted")
}

internal fun sportsImage(value: String): String? = value.trim().takeIf { url ->
    url.length <= 1024 && (url.startsWith("https://") || url.startsWith("http://")) && url.none { it.isWhitespace() || it == '"' || it == '<' }
}

private const val MAX_TEXT = 120
private const val MAX_PLAY = 240

private fun wholeNumber(value: String): String = value.toDoubleOrNull()?.takeIf { it == Math.floor(it) && kotlin.math.abs(it) < 1e9 }?.toLong()?.toString() ?: value

internal fun sportsInstant(value: String): Long? =
    runCatching { Instant.parse(value).toEpochMilli() }.getOrNull() ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()

private fun objects(array: JSONArray): List<JSONObject> = (0 until array.length()).mapNotNull { array.optJSONObject(it) }

private fun JSONObject.text(key: String): String? = if (isNull(key)) null else when (val value = opt(key)) {
    is String -> value.trim().takeIf(String::isNotEmpty)
    is Number, is Boolean -> value.toString()
    else -> null
}

private fun JSONObject.optIntOrNull(key: String): Int? = if (!has(key) || isNull(key)) null else when (val value = opt(key)) {
    is Number -> value.toInt()
    is String -> value.trim().toIntOrNull()
    else -> null
}
