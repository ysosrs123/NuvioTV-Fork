package com.nuvio.tv.core.iptv

import org.json.JSONArray
import org.json.JSONObject

enum class MomentKind { GOAL, POINTS, TRY, TOUCHDOWN, CARD_YELLOW, CARD_RED, SUBSTITUTION, RUN, OTHER }

data class SummaryMoment(val kind: MomentKind, val side: FixtureSide?, val period: Int?, val clock: String?, val text: String, val homeScore: Int? = null,
    val awayScore: Int? = null, val wallclockMillis: Long? = null, val bottom: Boolean? = null)

data class SummaryLine(val score: String?, val periods: List<String> = emptyList(), val breakdown: String? = null)

data class SummaryStat(val label: String, val home: String, val away: String)

data class SummaryLeader(val side: FixtureSide, val category: String, val name: String, val stat: String?)

data class SummaryPlayer(val jersey: String?, val name: String, val position: String?, val starter: Boolean, val captain: Boolean = false,
    val subbedIn: Boolean = false, val subbedOut: Boolean = false)

data class SummaryRoster(val side: FixtureSide, val starters: List<SummaryPlayer>, val subs: List<SummaryPlayer>)

data class SummaryStanding(val team: String, val side: FixtureSide?, val stats: List<Pair<String, String>>)

data class SummaryTable(val name: String?, val rows: List<SummaryStanding>)

data class SummaryMeeting(val dateMillis: Long?, val homeScore: String?, val awayScore: String?, val score: String?, val opponent: String?, val result: String?,
    val side: FixtureSide?)

data class SummaryCount(val balls: Int?, val strikes: Int?, val outs: Int?, val onFirst: Boolean, val onSecond: Boolean, val onThird: Boolean,
    val inning: Int?, val bottom: Boolean?, val pitcher: String?, val batter: String?)

data class SummaryStrength(val text: String, val powerPlay: FixtureSide?)

data class SummaryPlay(val text: String, val period: Int?, val clock: String?)

data class SummaryRun(val side: FixtureSide, val points: Int) {
    val text: String get() = "$points–0"
}

data class SummaryInnings(val side: FixtureSide?, val number: Int, val runs: Int?, val wickets: Int?, val overs: String?, val target: Int?)

data class SportsSummary(val eventId: String?, val sport: String, val status: FixtureStatus, val period: Int? = null, val clock: String? = null,
    val detail: String? = null, val homeName: String? = null, val awayName: String? = null, val homeLine: SummaryLine? = null, val awayLine: SummaryLine? = null,
    val moments: List<SummaryMoment> = emptyList(), val stats: List<SummaryStat> = emptyList(), val leaders: List<SummaryLeader> = emptyList(),
    val rosters: List<SummaryRoster> = emptyList(), val tables: List<SummaryTable> = emptyList(), val headToHead: List<SummaryMeeting> = emptyList(),
    val winProbability: List<Float> = emptyList(), val count: SummaryCount? = null, val strength: SummaryStrength? = null,
    val lastPlays: List<SummaryPlay> = emptyList(), val run: SummaryRun? = null, val innings: List<SummaryInnings> = emptyList())

object EspnSummary {
    const val MAX_MOMENTS = 80
    const val MAX_STATS = 8
    const val MAX_PROBABILITY = 400
    private const val MAX_PLAYS = 2000
    private const val MAX_ROSTER = 40
    private const val MAX_TABLES = 8
    private const val MAX_ROWS = 40
    private const val MAX_TEXT = 240

    private class Sides(val home: Set<String>, val away: Set<String>, val homeNames: Set<String>, val awayNames: Set<String>) {
        fun of(json: JSONObject?): FixtureSide? {
            json ?: return null
            when (json.str("homeAway")) { "home" -> return FixtureSide.HOME; "away" -> return FixtureSide.AWAY }
            return byId(setOfNotNull(json.optJSONObject("team")?.str("id"), json.str("teamId")))
        }
        fun byId(ids: Set<String>): FixtureSide? = when {
            ids.any { it in home } -> FixtureSide.HOME
            ids.any { it in away } -> FixtureSide.AWAY
            else -> null
        }
        fun byName(name: String?): FixtureSide? = name?.let(SportsGuide::normalise)?.let { when (it) { in homeNames -> FixtureSide.HOME; in awayNames -> FixtureSide.AWAY; else -> null } }
    }

    fun parse(json: String, league: SportsLeague): SportsSummary = parse(JSONObject(json), league.sport)

    fun parse(root: JSONObject, sport: String): SportsSummary {
        val header = root.optJSONObject("header")
        val competition = header?.optJSONArray("competitions")?.let(::objs)?.firstOrNull()
        val competitors = competition?.optJSONArray("competitors")?.let(::objs).orEmpty()
        val home = competitors.firstOrNull { it.str("homeAway") == "home" } ?: competitors.getOrNull(0)
        val away = competitors.firstOrNull { it.str("homeAway") == "away" && it !== home } ?: competitors.firstOrNull { it !== home && it !== competitors.getOrNull(0) }
        fun ids(c: JSONObject?) = setOfNotNull(c?.str("id"), c?.optJSONObject("team")?.str("id"))
        fun names(c: JSONObject?) = c?.optJSONObject("team")?.let { t -> listOfNotNull(t.str("displayName"), t.str("shortDisplayName"), t.str("name"), t.str("abbreviation"),
            t.str("location")).map(SportsGuide::normalise).toSet() }.orEmpty()
        val sides = Sides(ids(home), ids(away), names(home), names(away))
        val statusJson = competition?.optJSONObject("status") ?: header?.optJSONObject("status")
        val type = statusJson?.optJSONObject("type")
        val status = when {
            type?.optBoolean("completed") == true || type?.str("state") == "post" -> FixtureStatus.FINAL
            type?.str("state") == "in" -> FixtureStatus.LIVE
            else -> FixtureStatus.SCHEDULED
        }
        val plays = root.optJSONArray("plays")?.let { array -> objs(array).takeLast(MAX_PLAYS) }.orEmpty()
        val teamStats = teamStats(root, sides)
        val cricket = sport == "cricket"
        val homeLine = if (cricket) null else home?.let { line(it, sport, teamStats[FixtureSide.HOME]) }
        val awayLine = if (cricket) null else away?.let { line(it, sport, teamStats[FixtureSide.AWAY]) }
        val names = athletes(root)
        return SportsSummary(header?.str("id") ?: competition?.str("id"), sport, status,
            statusJson?.int("period")?.takeIf { it in 0..30 }, statusJson?.str("displayClock")?.takeIf { it.length <= 12 },
            (type?.str("shortDetail") ?: type?.str("detail"))?.take(80), team(home), team(away), homeLine, awayLine,
            moments(root, competition, plays, sport, sides), stats(teamStats, sport), leaders(root, sides), rosters(root, sides, sport),
            tables(root, sides), headToHead(root, sides), probability(root), if (sport == "baseball") count(root, plays, names) else null,
            if (sport == "ice-hockey") strength(plays, sides) else null, lastPlays(root, plays), if (sport == "basketball") run(plays) else null,
            if (cricket) innings(competitors, sides) else emptyList())
    }

    private fun team(c: JSONObject?): String? = c?.optJSONObject("team")?.let { it.str("displayName") ?: it.str("name") }?.take(80)

    private fun line(c: JSONObject, sport: String, stats: Map<String, String>?): SummaryLine {
        val score = c.opt("score").let { value -> if (value is JSONObject) value.str("displayValue") ?: value.str("value")?.let(::whole) else c.str("score")?.let(::whole) }
            ?.takeIf { it.length <= 24 }
        val lines = c.optJSONArray("linescores")?.let(::objs).orEmpty().filter { (it.int("period") ?: 1) in 1..12 }.sortedBy { it.int("period") ?: 0 }.take(12)
        val afl = sport == "australian-football"
        val split = afl && lines.isNotEmpty() && lines.all { it.has("goals") && it.has("behinds") }
        val periods = if (score == null) emptyList() else if (split) lines.map { "${it.int("goals") ?: 0}.${it.int("behinds") ?: 0}" }
        else SportsLines.perPeriod(lines.mapNotNull { (it.str("displayValue") ?: it.str("value")?.let(::whole))?.takeIf { v -> v.length <= 8 } }, score)
        val breakdown = if (afl) stats?.let { s -> val g = s["goals"]; val b = s["behinds"]; if (g != null && b != null) "$g.$b" else null }
            ?: lines.takeIf { split }?.let { "${it.sumOf { l -> l.int("goals") ?: 0 }}.${it.sumOf { l -> l.int("behinds") ?: 0 }}" } else null
        return SummaryLine(score, periods, breakdown)
    }

    private fun moments(root: JSONObject, competition: JSONObject?, plays: List<JSONObject>, sport: String, sides: Sides): List<SummaryMoment> {
        val keyEvents = root.optJSONArray("keyEvents")?.let(::objs).orEmpty()
        val details = competition?.optJSONArray("details")?.let(::objs).orEmpty()
        val list = when {
            sport == "soccer" && keyEvents.isNotEmpty() -> events(keyEvents, sport, sides)
            (sport == "soccer" || sport == "rugby-league" || sport == "rugby") && details.isNotEmpty() -> events(details, sport, sides)
            else -> plays.filter { it.optBoolean("scoringPlay") || sport == "australian-football" && it.optJSONObject("type")?.let { t -> t.str("type") ?: t.str("text") }?.lowercase() in AFL_SCORES }
                .mapNotNull { play(it, sport, sides) }.ifEmpty { events(details, sport, sides) }
        }
        return list.takeLast(MAX_MOMENTS)
    }

    private fun play(play: JSONObject, sport: String, sides: Sides): SummaryMoment? {
        val typeText = play.optJSONObject("type")?.str("text").orEmpty()
        val text = (play.str("text") ?: play.str("shortText") ?: typeText.takeIf(String::isNotEmpty))?.take(MAX_TEXT) ?: return null
        val kind = scoringKind(sport, typeText, text, play.int("scoreValue"))
        val period = play.optJSONObject("period")
        return SummaryMoment(kind, sides.of(play), period?.int("number"), clock(play), text, play.int("homeScore"), play.int("awayScore"),
            play.str("wallclock")?.let(::sportsInstant), period?.str("type")?.let(::bottom))
    }

    private fun events(items: List<JSONObject>, sport: String, sides: Sides): List<SummaryMoment> {
        val rugby = sport == "rugby-league" || sport == "rugby"
        var home = 0
        var away = 0
        return items.mapNotNull { item ->
            val typeText = item.optJSONObject("type")?.str("text").orEmpty()
            val lower = typeText.lowercase()
            val scoring = item.optBoolean("scoringPlay")
            val kind = when {
                item.optBoolean("redCard") || "red card" in lower || "second yellow" in lower -> MomentKind.CARD_RED
                item.optBoolean("yellowCard") || "yellow card" in lower -> MomentKind.CARD_YELLOW
                "substitution" in lower -> MomentKind.SUBSTITUTION
                scoring -> scoringKind(sport, typeText, typeText, item.int("scoreValue"))
                rugby && "try" in lower -> MomentKind.TRY
                rugby && ("conversion" in lower || "goal" in lower) -> MomentKind.POINTS
                "penalty" in lower || item.optBoolean("penaltyKick") -> MomentKind.OTHER
                sport == "soccer" && "goal" in lower && "kick" !in lower -> MomentKind.GOAL
                sport != "soccer" && "try" in lower -> MomentKind.TRY
                else -> null
            } ?: return@mapNotNull null
            val side = sides.of(item)
            if (scoring && side != null) {
                val value = item.int("scoreValue") ?: if (sport == "soccer") 1 else 0
                if (side == FixtureSide.HOME) home += value else away += value
            }
            val people = (item.optJSONArray("athletesInvolved") ?: item.optJSONArray("participants"))?.let(::objs).orEmpty()
                .mapNotNull { (it.optJSONObject("athlete") ?: it).let { a -> a.str("displayName") ?: a.str("shortName") } }.take(2)
            val own = item.optBoolean("ownGoal")
            val built = listOfNotNull(typeText.takeIf(String::isNotEmpty)?.replaceFirstChar(Char::uppercaseChar)?.let { if (own && "own" !in lower) "$it (own goal)" else it },
                people.joinToString(", ").takeIf(String::isNotEmpty)).joinToString(" – ")
            val text = (item.str("shortText") ?: built.takeIf(String::isNotEmpty) ?: item.str("text"))?.take(MAX_TEXT) ?: return@mapNotNull null
            val withScore = scoring && side != null
            SummaryMoment(kind, side, item.optJSONObject("period")?.int("number"), clock(item), text,
                item.int("homeScore") ?: home.takeIf { withScore }, item.int("awayScore") ?: away.takeIf { withScore },
                item.str("wallclock")?.let(::sportsInstant))
        }
    }

    private fun scoringKind(sport: String, typeText: String, text: String, value: Int?): MomentKind {
        val lower = typeText.lowercase()
        return when (sport) {
            "american-football" -> if ("touchdown" in lower || "touchdown" in text.lowercase()) MomentKind.TOUCHDOWN else MomentKind.POINTS
            "baseball" -> MomentKind.RUN
            "soccer", "ice-hockey" -> MomentKind.GOAL
            "australian-football" -> when { "behind" in lower -> MomentKind.POINTS; "goal" in lower || value == 6 -> MomentKind.GOAL; else -> MomentKind.POINTS }
            "rugby-league", "rugby" -> if ("try" in lower) MomentKind.TRY else MomentKind.POINTS
            else -> MomentKind.POINTS
        }
    }

    private fun clock(json: JSONObject): String? = when (val value = json.opt("clock")) {
        is JSONObject -> value.str("displayValue")
        is String -> value.trim().takeIf(String::isNotEmpty)
        else -> null
    }?.take(16)

    private fun bottom(value: String): Boolean? = when (value.lowercase()) { "bottom", "bot", "end" -> true; "top", "mid", "middle" -> false; else -> null }

    private fun teamStats(root: JSONObject, sides: Sides): Map<FixtureSide, Map<String, String>> {
        val teams = root.optJSONObject("boxscore")?.optJSONArray("teams")?.let(::objs).orEmpty()
        val out = mutableMapOf<FixtureSide, Map<String, String>>()
        teams.forEachIndexed { index, team ->
            val side = sides.of(team) ?: (if (index == 0) FixtureSide.HOME else FixtureSide.AWAY).takeIf { teams.size == 2 && sides.home.isEmpty() } ?: return@forEachIndexed
            val map = LinkedHashMap<String, String>()
            fun add(stat: JSONObject) {
                val name = stat.str("name") ?: return
                val value = stat.str("displayValue") ?: stat.str("value")?.let(::whole) ?: return
                map.putIfAbsent(name, value.take(24))
                stat.str("label")?.let { map.putIfAbsent("label:$name", it.take(32)) } ?: stat.str("displayName")?.let { map.putIfAbsent("label:$name", it.take(32)) }
            }
            team.optJSONArray("statistics")?.let(::objs)?.take(80)?.forEach { stat ->
                val nested = stat.optJSONArray("stats")
                if (nested != null) objs(nested).take(80).forEach(::add) else add(stat)
            }
            out[side] = map
        }
        return out
    }

    private fun stats(teams: Map<FixtureSide, Map<String, String>>, sport: String): List<SummaryStat> {
        val home = teams[FixtureSide.HOME] ?: return emptyList()
        val away = teams[FixtureSide.AWAY] ?: return emptyList()
        val preferred = PREFERRED[sport].orEmpty()
        val names = home.keys.filter { !it.startsWith("label:") && it in away }.filter { name ->
            val values = listOf(home.getValue(name), away.getValue(name))
            values.any { it != "-" } && (preferred.any { it.first == name } || values.any { it.toDoubleOrNull() != 0.0 })
        }
        val picked = preferred.map { it.first }.filter { it in names }
        val chosen = (if (picked.size >= 4) picked else picked + names.filterNot { it in picked }).take(MAX_STATS)
        return chosen.map { name ->
            val label = preferred.firstOrNull { it.first == name }?.second ?: home["label:$name"] ?: name
            val percent = name.endsWith("Pct") || name.endsWith("Percentage")
            fun value(v: String) = if (percent && sport == "soccer" && !v.endsWith("%")) "$v%" else v
            SummaryStat(label, value(home.getValue(name)), value(away.getValue(name)))
        }
    }

    private fun leaders(root: JSONObject, sides: Sides): List<SummaryLeader> =
        root.optJSONArray("leaders")?.let(::objs).orEmpty().flatMap { team ->
            val side = sides.of(team) ?: return@flatMap emptyList()
            team.optJSONArray("leaders")?.let(::objs).orEmpty().mapNotNull { category ->
                val top = category.optJSONArray("leaders")?.let(::objs)?.firstOrNull() ?: return@mapNotNull null
                val name = top.optJSONObject("athlete")?.let { it.str("displayName") ?: it.str("shortName") } ?: return@mapNotNull null
                val main = top.optJSONObject("mainStat")
                val stat = main?.str("value")?.let { v -> listOfNotNull(v, main.str("label")).joinToString(" ") } ?: top.str("displayValue") ?: top.str("summary")
                SummaryLeader(side, (category.str("displayName") ?: category.str("name") ?: return@mapNotNull null).take(40), name.take(60), stat?.take(40))
            }.take(2)
        }

    private fun rosters(root: JSONObject, sides: Sides, sport: String): List<SummaryRoster> {
        if (sport != "soccer" && sport != "rugby-league" && sport != "rugby") return emptyList()
        return root.optJSONArray("rosters")?.let(::objs).orEmpty().mapNotNull { team ->
            val side = sides.of(team) ?: return@mapNotNull null
            val entries = team.optJSONArray("roster")?.let(::objs).orEmpty().take(MAX_ROSTER)
            val flagged = entries.any { it.optBoolean("starter") }
            val players = entries.mapNotNull { entry ->
                val athlete = entry.optJSONObject("athlete") ?: return@mapNotNull null
                val name = athlete.str("displayName") ?: athlete.str("shortName") ?: return@mapNotNull null
                val json = entry.optJSONObject("position")
                val position = json?.let { it.str("abbreviation") ?: it.str("displayName") }
                val starter = if (flagged || sport == "soccer") entry.optBoolean("starter") else json?.str("name")?.lowercase()?.let { it !in BENCH } ?: false
                SummaryPlayer(entry.str("jersey") ?: athlete.str("jersey"), name.take(60), position?.take(24), starter,
                    entry.optBoolean("captain"), subbed(entry.opt("subbedIn")), subbed(entry.opt("subbedOut")))
            }.let { list -> if (sport == "soccer") list else list.sortedBy { it.jersey?.toIntOrNull() ?: Int.MAX_VALUE } }
            if (players.isEmpty()) null else SummaryRoster(side, players.filter { it.starter }, players.filter { !it.starter })
        }.distinctBy { it.side }
    }

    private fun subbed(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is JSONObject -> value.optBoolean("didSub")
        else -> false
    }

    private fun tables(root: JSONObject, sides: Sides): List<SummaryTable> {
        val found = mutableListOf<SummaryTable>()
        fun visit(node: JSONObject, depth: Int) {
            if (depth > 4 || found.size >= MAX_TABLES) return
            for (key in listOf("groups", "children")) node.optJSONArray(key)?.let(::objs)?.forEach { group ->
                val entries = group.optJSONObject("standings")?.optJSONArray("entries")?.let(::objs).orEmpty()
                if (entries.isNotEmpty() && found.size < MAX_TABLES) {
                    val rows = entries.take(MAX_ROWS).mapNotNull { row(it, sides) }
                    if (rows.isNotEmpty()) found += SummaryTable((group.str("header") ?: group.str("name") ?: group.str("abbreviation"))?.take(80), rows)
                }
                visit(group, depth + 1)
                group.optJSONObject("standings")?.let { visit(it, depth + 1) }
            }
        }
        root.optJSONObject("standings")?.let { visit(it, 0) }
        return found.sortedBy { table -> if (table.rows.any { it.side != null }) 0 else 1 }
    }

    private fun row(entry: JSONObject, sides: Sides): SummaryStanding? {
        val teamValue = entry.opt("team")
        val teamJson = teamValue as? JSONObject
        val name = (if (teamValue is String) teamValue.trim() else teamJson?.let { it.str("displayName") ?: it.str("name") })?.takeIf(String::isNotEmpty) ?: return null
        val side = sides.byId(setOfNotNull(entry.str("id"), teamJson?.str("id"))) ?: sides.byName(name)
        val stats = entry.optJSONArray("stats")?.let(::objs).orEmpty().mapNotNull { stat ->
            val key = (if (stat.str("name") in POINTS) "PTS" else stat.str("abbreviation") ?: stat.str("shortDisplayName") ?: return@mapNotNull null).take(8)
            val value = stat.str("displayValue") ?: stat.str("value")?.let(::whole) ?: return@mapNotNull null
            key to value.take(12)
        }
        val wanted = TABLE_STATS.mapNotNull { key -> stats.firstOrNull { it.first.equals(key, ignoreCase = true) } }
        return SummaryStanding(name.take(80), side, (wanted.ifEmpty { stats }).distinctBy { it.first }.take(6))
    }

    private fun headToHead(root: JSONObject, sides: Sides): List<SummaryMeeting> {
        val group = root.optJSONArray("headToHeadGames")?.let(::objs)?.firstOrNull() ?: return emptyList()
        val side = sides.of(group)
        return group.optJSONArray("events")?.let(::objs).orEmpty().take(5).map { event ->
            val opponent = event.opt("opponent").let { if (it is JSONObject) it.str("displayName") ?: it.str("abbreviation") else event.str("opponent") }
            SummaryMeeting(event.str("gameDate")?.let(::sportsInstant), event.str("homeTeamScore"), event.str("awayTeamScore"), event.str("score")?.take(24),
                opponent?.take(80), event.str("gameResult")?.take(4), side)
        }
    }

    private fun probability(root: JSONObject): List<Float> =
        root.optJSONArray("winprobability")?.let(::objs).orEmpty().takeLast(MAX_PROBABILITY).mapNotNull { item ->
            if (item.isNull("homeWinPercentage")) null else item.optDouble("homeWinPercentage", Double.NaN).takeIf { !it.isNaN() }
                ?.let { if (it > 1.0) it / 100 else it }?.takeIf { it in 0.0..1.0 }?.toFloat()
        }

    private fun athletes(root: JSONObject): Map<String, String> {
        val map = HashMap<String, String>()
        fun add(a: JSONObject?) { a ?: return; val id = a.str("id") ?: return; (a.str("displayName") ?: a.str("shortName"))?.let { if (map.size < 400) map.putIfAbsent(id, it.take(60)) } }
        root.optJSONArray("rosters")?.let(::objs)?.forEach { t -> t.optJSONArray("roster")?.let(::objs)?.forEach { add(it.optJSONObject("athlete")) } }
        root.optJSONObject("boxscore")?.optJSONArray("players")?.let(::objs)?.forEach { t ->
            t.optJSONArray("statistics")?.let(::objs)?.forEach { s -> s.optJSONArray("athletes")?.let(::objs)?.forEach { add(it.optJSONObject("athlete")) } }
        }
        return map
    }

    private fun count(root: JSONObject, plays: List<JSONObject>, names: Map<String, String>): SummaryCount? {
        val situation = root.optJSONObject("situation")
        val last = plays.lastOrNull { it.optJSONObject("period") != null }
        if (last == null && situation == null) return null
        val tally = last?.optJSONObject("resultCount") ?: last?.optJSONObject("pitchCount")
        fun on(json: JSONObject?, key: String): Boolean? = json?.takeIf { it.has(key) }?.let { j ->
            when (val v = j.opt(key)) { is JSONObject -> true; is Boolean -> v; is Number -> v.toInt() != 0; else -> false }
        }
        fun person(role: String): String? {
            situation?.optJSONObject(role)?.let { p -> (p.optJSONObject("athlete") ?: p).let { it.str("displayName") ?: it.str("shortName") ?: it.str("id")?.let(names::get) } }?.let { return it }
            val participant = last?.optJSONArray("participants")?.let(::objs)?.firstOrNull { it.str("type") == role }?.optJSONObject("athlete") ?: return null
            return participant.str("displayName") ?: participant.str("id")?.let(names::get)
        }
        val period = last?.optJSONObject("period")
        return SummaryCount(situation?.int("balls") ?: tally?.int("balls"), situation?.int("strikes") ?: tally?.int("strikes"),
            situation?.int("outs") ?: last?.int("outs"), on(situation, "onFirst") ?: on(last, "onFirst") ?: false,
            on(situation, "onSecond") ?: on(last, "onSecond") ?: false, on(situation, "onThird") ?: on(last, "onThird") ?: false,
            period?.int("number"), period?.str("type")?.let(::bottom), person("pitcher"), person("batter"))
    }

    private fun strength(plays: List<JSONObject>, sides: Sides): SummaryStrength? {
        val play = plays.lastOrNull { it.optJSONObject("strength") != null } ?: return null
        val strength = play.optJSONObject("strength")!!
        val text = strength.str("text") ?: strength.str("abbreviation") ?: return null
        val lower = text.lowercase()
        val side = sides.of(play)
        val power = when {
            side == null -> null
            "power" in lower -> side
            "short" in lower -> if (side == FixtureSide.HOME) FixtureSide.AWAY else FixtureSide.HOME
            else -> null
        }
        return SummaryStrength(text.take(40), power)
    }

    private fun lastPlays(root: JSONObject, plays: List<JSONObject>): List<SummaryPlay> {
        val fromPlays = plays.filter { it.str("text") != null }.takeLast(3).map {
            SummaryPlay(it.str("text")!!.take(MAX_TEXT), it.optJSONObject("period")?.int("number"), clock(it))
        }
        if (fromPlays.isNotEmpty()) return fromPlays.reversed()
        return root.optJSONArray("commentary")?.let(::objs).orEmpty().filter { it.str("text") != null }.takeLast(3).map {
            SummaryPlay(it.str("text")!!.take(MAX_TEXT), it.optJSONObject("play")?.optJSONObject("period")?.int("number"), it.optJSONObject("time")?.str("displayValue"))
        }.reversed()
    }

    private fun run(plays: List<JSONObject>): SummaryRun? {
        var side: FixtureSide? = null
        var points = 0
        var home = 0
        var away = 0
        plays.forEach { play ->
            val h = play.int("homeScore") ?: return@forEach
            val a = play.int("awayScore") ?: return@forEach
            val dh = h - home
            val da = a - away
            when {
                dh < 0 || da < 0 || (dh > 0 && da > 0) -> { side = null; points = 0 }
                dh > 0 -> if (side == FixtureSide.HOME) points += dh else { side = FixtureSide.HOME; points = dh }
                da > 0 -> if (side == FixtureSide.AWAY) points += da else { side = FixtureSide.AWAY; points = da }
            }
            home = h
            away = a
        }
        return side?.takeIf { points >= 6 }?.let { SummaryRun(it, points) }
    }

    private fun innings(competitors: List<JSONObject>, sides: Sides): List<SummaryInnings> = competitors.flatMap { c ->
        val side = sides.of(c)
        c.optJSONArray("linescores")?.let(::objs).orEmpty().filter { it.has("runs") || it.has("wickets") }
            .filter { it.optBoolean("isBatting") || !it.has("isBatting") || (it.int("runs") ?: 0) > 0 || (it.int("wickets") ?: 0) > 0 }.take(4).mapIndexed { index, line ->
            SummaryInnings(side, line.int("period") ?: line.str("displayValue")?.toIntOrNull() ?: (index + 1), line.int("runs"), line.int("wickets"),
                line.str("overs")?.take(8), line.int("target")?.takeIf { it > 0 })
        }
    }.take(8)

    private val PREFERRED = mapOf(
        "soccer" to listOf("possessionPct" to "Possession", "totalShots" to "Shots", "shotsOnTarget" to "On target", "wonCorners" to "Corners",
            "foulsCommitted" to "Fouls", "yellowCards" to "Yellow cards", "redCards" to "Red cards", "offsides" to "Offsides", "saves" to "Saves"),
        "basketball" to listOf("fieldGoalsMade-fieldGoalsAttempted" to "FG", "fieldGoalPct" to "FG%",
            "threePointFieldGoalsMade-threePointFieldGoalsAttempted" to "3PT", "freeThrowsMade-freeThrowsAttempted" to "FT", "totalRebounds" to "Rebounds",
            "assists" to "Assists", "turnovers" to "Turnovers", "steals" to "Steals", "blocks" to "Blocks", "largestLead" to "Largest lead"),
        "american-football" to listOf("firstDowns" to "1st downs", "totalYards" to "Total yards", "netPassingYards" to "Passing", "rushingYards" to "Rushing",
            "turnovers" to "Turnovers", "thirdDownEff" to "3rd down", "totalPenaltiesYards" to "Penalties", "possessionTime" to "Possession"),
        "ice-hockey" to listOf("shotsTotal" to "Shots", "powerPlayGoals" to "Power play goals", "faceoffPercent" to "Faceoffs %", "hits" to "Hits",
            "blockedShots" to "Blocked shots", "penaltyMinutes" to "PIM", "takeaways" to "Takeaways", "giveaways" to "Giveaways"),
        "baseball" to listOf("hits" to "Hits", "errors" to "Errors", "homeRuns" to "Home runs", "RBIs" to "RBI", "walks" to "Walks",
            "strikeouts" to "Strikeouts", "avg" to "AVG", "leftOnBase" to "Left on base"),
        "australian-football" to listOf("disposals" to "Disposals", "kicks" to "Kicks", "handballs" to "Handballs", "marks" to "Marks", "tackles" to "Tackles",
            "inside50s" to "Inside 50s", "clearances" to "Clearances", "totalClearances" to "Clearances", "contestedPossessions" to "Contested", "hitouts" to "Hit-outs", "freesFor" to "Frees"),
        "rugby-league" to listOf("tries" to "Tries", "metres" to "Run metres", "runs" to "Runs", "cleanBreaks" to "Line breaks", "offload" to "Offloads",
            "tackles" to "Tackles", "missedTackles" to "Missed tackles", "penaltiesConceded" to "Penalties", "kicks" to "Kicks"),
        "rugby" to listOf("possession" to "Possession", "territory" to "Territory", "tries" to "Tries", "metres" to "Metres", "cleanBreaks" to "Line breaks",
            "tackles" to "Tackles", "missedTackles" to "Missed tackles", "lineoutsWon" to "Lineouts won", "scrumsWon" to "Scrums won", "penaltiesConceded" to "Penalties"),
    )
    private val BENCH = setOf("replacement", "reserve", "interchange", "substitute")
    private val AFL_SCORES = setOf("goal", "behind", "rushed")
    private val TABLE_STATS = listOf("GP", "M", "W", "D", "T", "L", "GD", "PD", "PTS", "P", "PCT", "GB", "PER")
    private val POINTS = setOf("points", "matchPoints")
}

data class SportsMarker(val millis: Long, val exact: Boolean)

object SportsMarkers {
    private class Shape(val playMinutes: Double, val realMinutes: Double, val breaks: List<Double>, val down: Boolean,
        val extraPlay: Double = playMinutes, val extraReal: Double = realMinutes, val extraBreak: Double = 2.0)

    private val SHAPES = mapOf(
        "basketball" to Shape(12.0, 32.5, listOf(2.5, 15.0, 2.5), true, 5.0, 13.5, 2.5),
        "american-football" to Shape(15.0, 40.0, listOf(2.0, 13.0, 2.0), true, 10.0, 27.0, 3.0),
        "ice-hockey" to Shape(20.0, 35.0, listOf(18.0, 18.0), true, 5.0, 9.0, 2.0),
        "australian-football" to Shape(30.0, 30.0, listOf(6.0, 20.0, 6.0), false, 10.0, 10.0, 5.0),
    )

    fun all(summary: SportsSummary, startMillis: Long, windowStart: Long? = null, windowEnd: Long? = null): List<Pair<SummaryMoment, SportsMarker>> =
        summary.moments.mapNotNull { moment -> estimate(moment, summary.sport, startMillis, windowStart, windowEnd)?.let { moment to it } }

    fun estimate(moment: SummaryMoment, sport: String, startMillis: Long, windowStart: Long? = null, windowEnd: Long? = null): SportsMarker? {
        val marker = moment.wallclockMillis?.let { SportsMarker(it, true) }
            ?: minutes(sport, moment.period, moment.clock, moment.bottom)?.let { SportsMarker(startMillis + Math.round(it * 60_000), false) } ?: return null
        if (windowStart == null || windowEnd == null || windowEnd < windowStart) return marker
        return marker.copy(millis = marker.millis.coerceIn(windowStart, windowEnd))
    }

    fun minutes(sport: String, period: Int?, clock: String?, bottom: Boolean? = null): Double? {
        val seconds = clock?.let(::seconds)
        return when (sport) {
            "soccer" -> halves(period, seconds, clock?.let(::base), 45.0, 15.0, 15.0, 5.0)
            "rugby-league" -> halves(period, seconds, clock?.let(::base), 40.0, 10.0, 5.0, 5.0)
            "rugby" -> halves(period, seconds, clock?.let(::base), 40.0, 15.0, 10.0, 5.0)
            "baseball" -> period?.takeIf { it >= 1 }?.let { (it - 1) * 18.0 + if (bottom == true) 9.0 + 4.5 else 4.5 }
            else -> SHAPES[sport]?.let { quarters(it, period ?: return null, seconds) }
        }
    }

    private fun quarters(shape: Shape, period: Int, seconds: Int?): Double? {
        if (period < 1) return null
        val regular = shape.breaks.size + 1
        var offset = 0.0
        for (p in 1 until period) offset += if (p < regular) shape.realMinutes + shape.breaks[p - 1] else if (p == regular) shape.realMinutes + shape.extraBreak
            else shape.extraReal + shape.extraBreak
        val extra = period > regular
        val play = if (extra) shape.extraPlay else shape.playMinutes
        val real = if (extra) shape.extraReal else shape.realMinutes
        val elapsed = seconds?.let { s -> (if (shape.down) play - s / 60.0 else s / 60.0).coerceIn(0.0, play) } ?: (play / 2)
        return offset + elapsed * real / play
    }

    private fun halves(period: Int?, seconds: Int?, baseMinute: Int?, half: Double, halfTime: Double, extraHalf: Double, extraBreak: Double): Double? {
        val minute = seconds?.div(60.0)
        val p = period ?: when {
            baseMinute == null -> return null
            baseMinute <= half -> 1
            baseMinute <= half * 2 -> 2
            baseMinute <= half * 2 + extraHalf -> 3
            else -> 4
        }
        val stoppage = 2.0
        val second = half + stoppage + halfTime
        val extra = second + half + stoppage + extraBreak
        return when (p) {
            1 -> (minute ?: (half / 2)).coerceIn(0.0, half + 10)
            2 -> second + ((minute ?: (half * 1.5)) - half).coerceIn(0.0, half + 10)
            3 -> extra + ((minute ?: (half * 2 + extraHalf / 2)) - half * 2).coerceIn(0.0, extraHalf + 5)
            4 -> extra + extraHalf + 1 + ((minute ?: (half * 2 + extraHalf * 1.5)) - half * 2 - extraHalf).coerceIn(0.0, extraHalf + 5)
            else -> extra + extraHalf * 2 + 1 + extraBreak
        }
    }

    private fun base(text: String): Int? = CLOCK.matchEntire(text.trim())?.groupValues?.get(1)?.toInt() ?: MINUTE.matchEntire(text.trim())?.groupValues?.get(1)?.toInt()

    internal fun seconds(text: String): Int? {
        val value = text.trim()
        CLOCK.matchEntire(value)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        MINUTE.matchEntire(value)?.let { m -> return (m.groupValues[1].toInt() + (m.groupValues[2].toIntOrNull() ?: 0)) * 60 }
        SUB_MINUTE.matchEntire(value)?.let { return it.groupValues[1].toInt() }
        return null
    }

    private val CLOCK = Regex("(\\d{1,3}):(\\d{2})(?:\\.\\d+)?")
    private val SUB_MINUTE = Regex("(\\d{1,2})\\.\\d+")
    private val MINUTE = Regex("(\\d{1,3})'?\\s*(?:\\+\\s*(\\d{1,2})'?)?")
}

private fun objs(array: JSONArray): List<JSONObject> = (0 until array.length()).mapNotNull { array.optJSONObject(it) }

private fun whole(value: String): String = value.toDoubleOrNull()?.takeIf { it == Math.floor(it) && kotlin.math.abs(it) < 1e9 }?.toLong()?.toString() ?: value

private fun JSONObject.str(key: String): String? = if (isNull(key)) null else when (val value = opt(key)) {
    is String -> value.trim().takeIf(String::isNotEmpty)
    is Number -> whole(value.toString())
    is Boolean -> value.toString()
    else -> null
}

private fun JSONObject.int(key: String): Int? = if (!has(key) || isNull(key)) null else when (val value = opt(key)) {
    is Number -> value.toInt()
    is String -> value.trim().toDoubleOrNull()?.toInt()
    else -> null
}
