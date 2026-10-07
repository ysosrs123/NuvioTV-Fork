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
        val events = JSONObject(json).optJSONArray("events") ?: return emptyList()
        return objects(events).take(SportsFixtureCodec.MAX_FIXTURES).mapNotNull { event -> runCatching { fixture(event, league) }.getOrNull() }
    }

    private fun fixture(event: JSONObject, league: SportsLeague): SportsFixture? {
        val id = event.text("id") ?: return null
        val competitions = event.optJSONArray("competitions")?.let(::objects).orEmpty()
        val competition = competitions.firstOrNull()
        val start = (competition?.text("date") ?: event.text("date"))?.let(::sportsInstant) ?: return null
        val type = (competition?.optJSONObject("status") ?: event.optJSONObject("status"))?.optJSONObject("type")
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
        val homeTeam = home?.optJSONObject("team")?.let(::team)
        val awayTeam = away?.optJSONObject("team")?.let(::team)
        val paired = homeTeam != null && awayTeam != null
        val title = if (paired) "${homeTeam!!.name} v ${awayTeam!!.name}" else event.text("name") ?: event.text("shortName") ?: return null
        val score = if (paired && status != FixtureStatus.SCHEDULED) listOf(home, away).map { score(it) }
            .takeIf { it.all { value -> value != null } }?.joinToString("–") else null
        val broadcasters = (competitions + event).flatMap(::broadcasters).distinctBy { SportsGuide.normalise(it) }.take(12)
        return SportsFixture(id, league.id, league.sport, title, homeTeam.takeIf { paired }, awayTeam.takeIf { paired }, start, status, score,
            type?.text("shortDetail") ?: type?.text("detail"), broadcasters)
    }

    private fun team(json: JSONObject): FixtureTeam? {
        val name = json.text("displayName") ?: json.text("name") ?: return null
        val short = json.text("shortDisplayName")?.takeIf { it != name }
        val alternatives = listOfNotNull(json.text("location"), json.text("name"), json.text("nickname"), json.text("alternateDisplayName"))
            .filter { it != name && it != short }.distinct()
        return FixtureTeam(name, short, json.text("abbreviation"), alternatives)
    }

    private fun score(competitor: JSONObject?): String? {
        competitor ?: return null
        if (competitor.isNull("score")) return null
        val value = competitor.opt("score")
        return when (value) {
            is JSONObject -> value.text("displayValue") ?: value.text("value")
            else -> competitor.text("score")
        }?.takeIf { it.length <= 24 }
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
        val home = event.text("strHomeTeam")?.let { FixtureTeam(it, event.text("strHomeTeamShort"), null, emptyList()) }
        val away = event.text("strAwayTeam")?.let { FixtureTeam(it, event.text("strAwayTeamShort"), null, emptyList()) }
        val paired = home != null && away != null
        val title = if (paired) "${home!!.name} v ${away!!.name}" else event.text("strEvent") ?: return null
        val score = if (paired && status != FixtureStatus.SCHEDULED && homeScore != null && awayScore != null) "$homeScore–$awayScore" else null
        val stations = event.text("strTVStation")?.split(',', ';', '/', '|')?.map(String::trim)?.filter { it.isNotEmpty() && it.length <= 80 }.orEmpty()
        return SportsFixture(id, league.id, league.sport, title, home.takeIf { paired }, away.takeIf { paired }, start, status, score,
            raw.takeIf { status == FixtureStatus.LIVE && it.isNotEmpty() && it.length <= 24 }, stations.distinct().take(12))
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
    private val SKIPPED = setOf("postponed", "pst", "canc", "cancelled", "canceled", "abandoned", "abd", "awd", "wo", "susp", "suspended", "int", "interrupted")
}

internal fun sportsInstant(value: String): Long? =
    runCatching { Instant.parse(value).toEpochMilli() }.getOrNull() ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()

private fun objects(array: JSONArray): List<JSONObject> = (0 until array.length()).mapNotNull { array.optJSONObject(it) }

private fun JSONObject.text(key: String): String? = if (isNull(key)) null else when (val value = opt(key)) {
    is String -> value.trim().takeIf(String::isNotEmpty)
    is Number, is Boolean -> value.toString()
    else -> null
}
