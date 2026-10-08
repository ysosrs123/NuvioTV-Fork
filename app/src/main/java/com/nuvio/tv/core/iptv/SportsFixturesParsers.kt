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
    fun parse(json: String, league: SportsLeague, nowMillis: Long = System.currentTimeMillis()): List<SportsFixture> {
        val root = JSONObject(json)
        val events = root.optJSONArray("events") ?: return emptyList()
        val leagueLogo = root.optJSONArray("leagues")?.let(::objects)?.firstOrNull()?.let(::logo)
        val list = objects(events)
        if (league.sport == "tennis") return tennis(list, league, leagueLogo, nowMillis)
        return list.take(SportsFixtureCodec.MAX_FIXTURES).mapNotNull { event ->
            runCatching {
                when (league.sport) {
                    "golf" -> golf(event, league, leagueLogo)
                    "motorsport" -> sessions(event, league, leagueLogo)
                    "mma" -> card(event, league, leagueLogo)
                    else -> fixture(event, league, leagueLogo)
                }
            }.getOrNull()
        }
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
        val homeLine = if (started) FixtureLine(homeScore, periods(home, homeScore)) else null
        val awayLine = if (started) FixtureLine(awayScore, periods(away, awayScore)) else null
        val situation = if (paired && status == FixtureStatus.LIVE) competition?.optJSONObject("situation")?.let { situation(it, home!!, away!!) } else null
        val extra = when {
            !started -> null
            league.sport == "cricket" -> cricket(listOf(home!! to homeTeam!!, away!! to awayTeam!!))
            league.sport == "baseball" && status == FixtureStatus.LIVE -> baseball(statusJson, type, competition?.optJSONObject("situation"))
            else -> null
        }
        val events = if (started && league.sport in EVENT_SPORTS) events(competition, league.sport, home!!, away!!) else emptyList()
        return SportsFixture(id, league.id, league.sport, title, homeTeam.takeIf { paired }, awayTeam.takeIf { paired }, start, status, score,
            type?.text("shortDetail") ?: type?.text("detail"), broadcasters, venue, round, period, clock, homeLine, awayLine, situation, leagueLogo, extra, events)
    }

    private fun events(competition: JSONObject?, sport: String, home: JSONObject, away: JSONObject): List<FixtureEvent> {
        val items = competition?.optJSONArray("details")?.let(::objects).orEmpty()
        fun ids(competitor: JSONObject) = setOfNotNull(competitor.text("id"), competitor.optJSONObject("team")?.text("id"))
        val homeIds = ids(home)
        val awayIds = ids(away)
        return items.mapNotNull { item ->
            val type = item.optJSONObject("type")
            val text = (type?.text("text") ?: type?.text("name") ?: type?.text("abbreviation") ?: item.text("type") ?: item.text("text")).orEmpty().lowercase()
            val typeId = type?.text("id")
            val scoring = item.optBoolean("scoringPlay")
            val kind = when {
                item.optBoolean("redCard") || "red card" in text || "second yellow" in text || typeId in RED_IDS -> FixtureEventKind.RED
                item.optBoolean("yellowCard") || "yellow card" in text || typeId in YELLOW_IDS -> FixtureEventKind.YELLOW
                item.optBoolean("shootout") || "shootout" in text -> return@mapNotNull null
                sport == "soccer" && (scoring || typeId in GOAL_IDS || ("goal" in text && "kick" !in text && "disallowed" !in text)) -> FixtureEventKind.GOAL
                sport != "soccer" && "try" in text -> FixtureEventKind.TRY
                scoring -> FixtureEventKind.OTHER
                else -> return@mapNotNull null
            }
            val people = (item.optJSONArray("athletesInvolved") ?: item.optJSONArray("participants"))?.let(::objects).orEmpty()
            val person = people.firstOrNull()?.let { it.optJSONObject("athlete") ?: it }
            val team = item.optJSONObject("team")?.text("id") ?: item.text("teamId") ?: person?.optJSONObject("team")?.text("id")
            val side = when (team) {
                null -> null
                in homeIds -> FixtureSide.HOME
                in awayIds -> FixtureSide.AWAY
                else -> null
            }
            val clock = when (val value = item.opt("clock")) {
                is JSONObject -> value.text("displayValue")
                is String -> value.trim().takeIf(String::isNotEmpty)
                else -> null
            }?.take(16)
            val period = item.optJSONObject("period")?.optIntOrNull("number")?.takeIf { it in 1..9 }
            FixtureEvent(kind, side, clock, period, (person?.text("shortName") ?: person?.text("displayName"))?.take(MAX_NAME))
        }.takeLast(SportsEvents.MAX_EVENTS)
    }

    private fun status(type: JSONObject?): FixtureStatus = when {
        type?.optBoolean("completed") == true || type?.text("state") == "post" -> FixtureStatus.FINAL
        type?.text("state") == "in" -> FixtureStatus.LIVE
        else -> FixtureStatus.SCHEDULED
    }

    private fun skipped(type: JSONObject?): Boolean = type?.text("name").orEmpty().uppercase().let { name -> SKIPPED.any { name.contains(it) } }

    private fun competitions(event: JSONObject): List<JSONObject> = event.optJSONArray("competitions")?.let(::objects).orEmpty()

    private fun competitors(json: JSONObject): List<JSONObject> = json.optJSONArray("competitors")?.let(::objects).orEmpty()

    private fun tennis(events: List<JSONObject>, league: SportsLeague, leagueLogo: String?, nowMillis: Long): List<SportsFixture> {
        val matches = events.flatMap { event ->
            val groupings = event.optJSONArray("groupings")?.let(::objects).orEmpty()
            val list = if (groupings.isEmpty()) competitions(event).map { null to it }
                else groupings.flatMap { group -> competitions(group).map { group.optJSONObject("grouping")?.text("slug") to it } }
            list.filter { (slug, competition) -> draw(competition.optJSONObject("type")?.text("slug") ?: slug, league.women) }
                .mapNotNull { runCatching { match(event, it.second, league, leagueLogo, nowMillis) }.getOrNull() }
        }
        return matches.distinctBy { it.id }.sortedWith(compareBy<SportsFixture>({ TENNIS_ORDER.indexOf(it.status) },
            { if (it.status == FixtureStatus.FINAL) -it.startMillis else it.startMillis })).take(MAX_MATCHES)
    }

    private fun draw(slug: String?, women: Boolean): Boolean = when {
        slug == null -> true
        slug.startsWith("womens") -> women
        slug.startsWith("mens") -> !women
        else -> true
    }

    private fun match(event: JSONObject, competition: JSONObject, league: SportsLeague, leagueLogo: String?, nowMillis: Long): SportsFixture? {
        val id = competition.text("id") ?: return null
        val start = (competition.text("date") ?: competition.text("startDate") ?: event.text("date"))?.let(::sportsInstant) ?: return null
        val statusJson = competition.optJSONObject("status")
        val type = statusJson?.optJSONObject("type")
        if (skipped(type)) return null
        val status = status(type)
        if (status == FixtureStatus.FINAL && start < nowMillis - RECENT_MILLIS) return null
        if (status == FixtureStatus.SCHEDULED && competition.has("timeValid") && !competition.optBoolean("timeValid")) return null
        val players = competitors(competition).take(2).takeIf { it.size == 2 } ?: return null
        val home = players.firstOrNull { it.text("homeAway") == "home" } ?: players.minBy { it.optIntOrNull("order") ?: 9 }
        val away = players.first { it !== home }
        val homeTeam = player(home) ?: return null
        val awayTeam = player(away) ?: return null
        val started = status != FixtureStatus.SCHEDULED
        val homeLines = home.optJSONArray("linescores")?.let(::objects).orEmpty().take(MAX_SETS)
        val awayLines = away.optJSONArray("linescores")?.let(::objects).orEmpty().take(MAX_SETS)
        val sets = if (!started) emptyList() else (0 until maxOf(homeLines.size, awayLines.size)).map { index ->
            val a = homeLines.getOrNull(index)
            val b = awayLines.getOrNull(index)
            TennisSet(a?.optIntOrNull("value"), b?.optIntOrNull("value"), a?.optIntOrNull("tiebreak"), b?.optIntOrNull("tiebreak"), when {
                a?.optBoolean("winner") == true -> FixtureSide.HOME
                b?.optBoolean("winner") == true -> FixtureSide.AWAY
                else -> null
            })
        }
        val server = when {
            status != FixtureStatus.LIVE -> null
            home.optBoolean("possession") -> FixtureSide.HOME
            away.optBoolean("possession") -> FixtureSide.AWAY
            else -> null
        }
        val detail = SportsDetail.Tennis(event.text("name")?.take(MAX_TEXT), competition.optJSONObject("round")?.text("displayName")?.take(MAX_TEXT),
            competition.optJSONObject("venue")?.text("court")?.take(MAX_TEXT), seed(home), seed(away), sets, server)
        val won = detail.setsWon
        val scored = started && sets.isNotEmpty()
        val score = if (scored) "${won.first}–${won.second}" else null
        fun line(side: FixtureSide, total: Int) = FixtureLine(total.toString(), sets.map { ((if (side == FixtureSide.HOME) it.home else it.away) ?: 0).toString() })
        val venue = (event.optJSONObject("venue") ?: competition.optJSONObject("venue"))?.let { it.text("displayName") ?: it.text("fullName") }?.take(MAX_TEXT)
        val broadcasters = listOf(competition, event).flatMap(::broadcasters).distinctBy { SportsGuide.normalise(it) }.take(12)
        val text = if (started) type?.text("detail") ?: type?.text("shortDetail") else type?.text("shortDetail") ?: type?.text("detail")
        return SportsFixture(id, league.id, league.sport, "${homeTeam.name} v ${awayTeam.name}", homeTeam, awayTeam, start, status, score,
            text, broadcasters, venue, null, statusJson?.optIntOrNull("period")?.takeIf { status == FixtureStatus.LIVE && it in 1..5 },
            null, if (scored) line(FixtureSide.HOME, won.first) else null, if (scored) line(FixtureSide.AWAY, won.second) else null, null, leagueLogo, detail)
    }

    private fun player(competitor: JSONObject): FixtureTeam? {
        val athlete = competitor.optJSONObject("athlete")
        val roster = competitor.optJSONObject("roster")
        val name = (athlete?.text("displayName") ?: roster?.text("displayName") ?: competitor.optJSONObject("team")?.text("displayName"))?.take(MAX_TEXT) ?: return null
        if (name.equals("TBD", ignoreCase = true) || competitor.text("id")?.startsWith("-") == true) return null
        val short = roster?.text("shortDisplayName")?.takeIf { it.split('/').size == name.split('/').size } ?: athlete?.text("shortName")
        val surnames = (short ?: name).split('/').map(String::trim).filter(String::isNotEmpty).map { part ->
            (if (athlete != null && roster == null) athlete.text("lastName") else null) ?: INITIAL.matchEntire(part)?.groupValues?.get(1) ?: part.substringAfterLast(' ')
        }
        val abbreviation = surnames.joinToString("/") { it.uppercase() }
        val flag = flag(competitor)
        return FixtureTeam(name, athlete?.text("shortName")?.takeIf { it != name }, abbreviation, surnames.filter { it != name && it.length >= 3 }.distinct(),
            flag?.text("href")?.let(::sportsImage))
    }

    private fun flag(competitor: JSONObject): JSONObject? = competitor.optJSONObject("flag") ?: competitor.optJSONObject("athlete")?.optJSONObject("flag")

    private fun seed(competitor: JSONObject): TennisPlayer {
        val flag = flag(competitor)
        return TennisPlayer(competitor.optJSONObject("curatedRank")?.optIntOrNull("current")?.takeIf { it in 1..64 }, flag?.text("href")?.let(::sportsImage),
            flag?.text("alt")?.take(60))
    }

    private fun golf(event: JSONObject, league: SportsLeague, leagueLogo: String?): SportsFixture? {
        val id = event.text("id") ?: return null
        val competition = competitions(event).firstOrNull()
        val start = (event.text("date") ?: competition?.text("date"))?.let(::sportsInstant) ?: return null
        val title = (event.text("name") ?: event.text("shortName"))?.take(MAX_TEXT) ?: return null
        val eventType = (event.optJSONObject("status") ?: competition?.optJSONObject("status"))?.optJSONObject("type")
        val roundStatus = competition?.optJSONObject("status") ?: event.optJSONObject("status")
        val roundType = roundStatus?.optJSONObject("type")
        if (skipped(eventType)) return null
        val status = status(eventType)
        val round = roundStatus?.optIntOrNull("period")?.takeIf { it in 1..6 }
        val players = competitors(competition ?: JSONObject()).withIndex().sortedBy { it.value.optIntOrNull("order") ?: (1000 + it.index) }.mapNotNull { (index, competitor) ->
            val athlete = competitor.optJSONObject("athlete")
            val name = (athlete?.text("displayName") ?: athlete?.text("fullName"))?.take(MAX_TEXT) ?: return@mapNotNull null
            val flag = flag(competitor)
            val state = competitor.optJSONObject("status")
            val card = competitor.optJSONArray("linescores")?.let(::objects).orEmpty().lastOrNull { line ->
                (round == null || line.optIntOrNull("period") == round) && line.optJSONArray("linescores") != null
            }
            val holes = card?.optJSONArray("linescores")?.length()?.takeIf { it > 0 }
            val thru = state?.optIntOrNull("thru")?.let { if (it >= 18) "F" else if (it > 0) it.toString() else null } ?: state?.text("thru")?.takeIf { it.length <= 3 && it != "0" }
                ?: holes?.let { if (it >= 18) "F" else it.toString() }
            Triple(competitor.optIntOrNull("order") ?: (index + 1), score(competitor)?.takeIf { it.length <= 6 },
                GolfPlayer("", name, athlete?.text("shortName")?.takeIf { it != name }, flag?.text("alt")?.take(60), flag?.text("href")?.let(::sportsImage), null, thru,
                    (state?.text("todayDetail") ?: card?.text("displayValue")?.takeIf { holes != null })?.take(12)))
        }
        val values = players.map { toPar(it.second) }
        val leaders = players.mapIndexed { index, (order, par, player) ->
            val value = values[index]
            val position = if (value == null) order.toString() else {
                val place = values.count { it != null && it < value } + 1
                if (values.count { it == value } > 1) "T$place" else place.toString()
            }
            player.copy(position = position, toPar = par)
        }.take(SportsDetails.MAX_LEADERS)
        val detail = SportsDetail.Golf(title, round, (roundType?.text("detail") ?: roundType?.text("shortDetail"))?.take(MAX_TEXT),
            (event.text("displayPurse") ?: competition?.text("displayPurse"))?.take(40), leaders,
            (event.text("endDate") ?: competition?.text("endDate"))?.let(::sportsInstant))
        val venue = (competition?.optJSONObject("venue") ?: event.optJSONObject("venue"))?.let { it.text("fullName") ?: it.text("displayName") }?.take(MAX_TEXT)
        val broadcasters = (competitions(event) + event).flatMap(::broadcasters).distinctBy { SportsGuide.normalise(it) }.take(12)
        return SportsFixture(id, league.id, league.sport, title, null, null, start, status, null, detail.statusText, broadcasters, venue, null,
            detail.round.takeIf { status == FixtureStatus.LIVE }, null, null, null, null, leagueLogo, detail)
    }

    internal fun toPar(value: String?): Int? {
        val text = value?.trim()?.uppercase() ?: return null
        if (text == "E" || text == "EVEN") return 0
        return text.removePrefix("+").toIntOrNull()
    }

    private fun sessions(event: JSONObject, league: SportsLeague, leagueLogo: String?): SportsFixture? {
        val id = event.text("id") ?: return null
        val title = (event.text("name") ?: event.text("shortName"))?.take(MAX_TEXT) ?: return null
        val eventStatus = event.optJSONObject("status")
        if (skipped(eventStatus?.optJSONObject("type"))) return null
        val list = competitions(event).mapNotNull { competition ->
            val start = (competition.text("date") ?: competition.text("startDate"))?.let(::sportsInstant) ?: return@mapNotNull null
            val type = (competition.optJSONObject("status") ?: eventStatus)?.optJSONObject("type")
            if (skipped(type)) return@mapNotNull null
            val abbreviation = competition.optJSONObject("type")?.text("abbreviation")?.take(12)
            Triple(competition, type, RaceSession(abbreviation, sessionName(abbreviation, competition.optJSONObject("type")?.text("text")), start, status(type)))
        }.sortedBy { it.third.startMillis }.take(MAX_SESSIONS)
        val circuit = event.optJSONObject("circuit")
        val venue = (circuit?.text("fullName") ?: list.firstNotNullOfOrNull { it.first.optJSONObject("venue")?.text("fullName") }
            ?: event.optJSONObject("venue")?.text("fullName"))?.take(MAX_TEXT)
        val detail = SportsDetail.Sessions(venue, list.map { it.third })
        val current = detail.current
        val currentType = list.firstOrNull { it.third === current }?.second ?: eventStatus?.optJSONObject("type")
        val start = current?.startMillis ?: event.text("date")?.let(::sportsInstant) ?: return null
        val status = current?.state ?: status(eventStatus?.optJSONObject("type"))
        val broadcasters = (list.filter { it.third === current }.map { it.first } + list.map { it.first } + event).flatMap(::broadcasters)
            .distinctBy { SportsGuide.normalise(it) }.take(12)
        return SportsFixture(id, league.id, league.sport, title, null, null, start, status, null, currentType?.text("shortDetail") ?: currentType?.text("detail"),
            broadcasters, venue, null, null, null, null, null, null, leagueLogo, detail.takeIf { list.isNotEmpty() })
    }

    private fun sessionName(abbreviation: String?, text: String?): String = when (abbreviation?.lowercase()) {
        "fp1" -> "Practice 1"
        "fp2" -> "Practice 2"
        "fp3" -> "Practice 3"
        "ss", "sq" -> "Sprint Qualifying"
        "sr", "sprint" -> "Sprint"
        "qual", "q" -> "Qualifying"
        "race" -> "Race"
        else -> text?.take(40) ?: abbreviation ?: "Race"
    }

    private fun card(event: JSONObject, league: SportsLeague, leagueLogo: String?): SportsFixture? {
        val id = event.text("id") ?: return null
        val title = (event.text("name") ?: event.text("shortName"))?.take(MAX_TEXT) ?: return null
        val eventStatus = event.optJSONObject("status")
        if (skipped(eventStatus?.optJSONObject("type"))) return null
        val eventStart = event.text("date")?.let(::sportsInstant)
        val raw = competitions(event).take(MAX_BOUTS).mapNotNull { competition ->
            val start = (competition.text("date") ?: competition.text("startDate"))?.let(::sportsInstant) ?: eventStart ?: return@mapNotNull null
            val statusJson = competition.optJSONObject("status")
            val type = statusJson?.optJSONObject("type")
            if (skipped(type)) return@mapNotNull null
            val pair = competitors(competition).withIndex().sortedBy { it.value.optIntOrNull("order") ?: it.index }.map { it.value }.take(2)
            if (pair.size != 2) return@mapNotNull null
            val first = fighter(pair[0]) ?: return@mapNotNull null
            val second = fighter(pair[1]) ?: return@mapNotNull null
            val state = status(type)
            val winner = when {
                pair[0].optBoolean("winner") -> 1
                pair[1].optBoolean("winner") -> 2
                else -> null
            }
            val bout = FightBout(competition.optJSONObject("type")?.let { it.text("abbreviation") ?: it.text("text") }?.take(40),
                competition.optJSONObject("format")?.optJSONObject("regulation")?.optIntOrNull("periods")?.takeIf { it in 1..12 }, first, second, winner, start, state,
                statusJson?.optIntOrNull("period")?.takeIf { state == FixtureStatus.LIVE && it in 1..12 })
            Triple(competition, type, bout)
        }
        val bouts = raw.map { it.third }
        val mainStart = bouts.lastOrNull()?.startMillis
        val detail = SportsDetail.Card(bouts.map { it.copy(mainCard = mainStart != null && it.startMillis >= mainStart) })
        val states = bouts.map { it.state }.toSet()
        val status = when {
            bouts.isEmpty() -> status(eventStatus?.optJSONObject("type"))
            FixtureStatus.LIVE in states -> FixtureStatus.LIVE
            states == setOf(FixtureStatus.FINAL) -> FixtureStatus.FINAL
            FixtureStatus.FINAL in states -> FixtureStatus.LIVE
            else -> FixtureStatus.SCHEDULED
        }
        val start = bouts.minOfOrNull { it.startMillis } ?: eventStart ?: return null
        val shown = raw.firstOrNull { it.third.state == FixtureStatus.LIVE }?.second ?: eventStatus?.optJSONObject("type") ?: raw.lastOrNull()?.second
        val venue = (event.optJSONArray("venues")?.let(::objects)?.firstOrNull() ?: raw.firstNotNullOfOrNull { it.first.optJSONObject("venue") }
            ?: event.optJSONObject("venue"))?.let { it.text("fullName") ?: it.text("displayName") }?.take(MAX_TEXT)
        val broadcasters = (raw.map { it.first } + event).flatMap(::broadcasters).distinctBy { SportsGuide.normalise(it) }.take(12)
        return SportsFixture(id, league.id, league.sport, title, null, null, start, status, null, shown?.text("shortDetail") ?: shown?.text("detail"),
            broadcasters, venue, null, null, null, null, null, null, leagueLogo, detail.takeIf { bouts.isNotEmpty() })
    }

    private fun fighter(competitor: JSONObject): Fighter? {
        val athlete = competitor.optJSONObject("athlete") ?: return null
        val name = athlete.text("displayName")?.take(MAX_TEXT) ?: return null
        val record = competitor.optJSONArray("records")?.let(::objects)?.firstOrNull()?.text("summary")?.takeIf { RECORD.matches(it) }
        return Fighter(name, athlete.text("shortName")?.takeIf { it != name }, record, flag(competitor)?.text("href")?.let(::sportsImage))
    }

    private fun cricket(sides: List<Pair<JSONObject, FixtureTeam>>): SportsDetail.Cricket? {
        val innings = sides.flatMap { (competitor, team) ->
            val name = team.abbreviation ?: team.shortName ?: team.name
            val summary = CRICKET.find(competitor.text("score").orEmpty())
            val lines = competitor.optJSONArray("linescores")?.let(::objects).orEmpty().filter { it.has("runs") || it.has("wickets") }.filter { line ->
                val any = (line.optIntOrNull("runs") ?: 0) > 0 || (line.optIntOrNull("wickets") ?: 0) > 0
                any || (line.optBoolean("isBatting") || !line.has("isBatting")) && (line.text("overs") != null || line.optBoolean("isBatting"))
            }
            val last = lines.maxOfOrNull { it.optIntOrNull("period") ?: 0 }
            val max = summary?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 1..200 }
            lines.map { line ->
                val latest = (line.optIntOrNull("period") ?: 0) == last
                (line.optIntOrNull("period") ?: 0) to CricketInnings(name, line.optIntOrNull("runs") ?: 0, (line.optIntOrNull("wickets") ?: 0).coerceIn(0, 10),
                    (line.text("overs") ?: summary?.groupValues?.get(1)?.takeIf { latest })?.takeIf { SportsDetails.balls(it) != null }, max,
                    summary?.groupValues?.get(3)?.toIntOrNull()?.takeIf { latest }, line.optBoolean("isBatting") && line.optIntOrNull("isCurrent") != 0)
            }
        }.sortedBy { it.first }.map { it.second }
        return if (innings.isEmpty()) null else SportsDetail.Cricket(innings.take(4))
    }

    private fun baseball(status: JSONObject?, type: JSONObject?, situation: JSONObject?): SportsDetail.Baseball? {
        val half = listOfNotNull(status?.text("periodPrefix"), type?.text("shortDetail"), type?.text("detail")).firstNotNullOfOrNull(::half)
        if (situation == null && half == null) return null
        fun count(key: String) = situation?.optIntOrNull(key)?.takeIf { it in 0..4 }
        fun base(key: String) = when (val value = situation?.opt(key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is JSONObject -> true
            else -> false
        }
        return SportsDetail.Baseball(status?.optIntOrNull("period")?.takeIf { it in 1..30 }, half, count("outs"), count("balls"), count("strikes"),
            base("onFirst"), base("onSecond"), base("onThird"))
    }

    private fun half(text: String): InningHalf? = text.trim().lowercase().let {
        when {
            it.startsWith("top") -> InningHalf.TOP
            it.startsWith("bot") -> InningHalf.BOTTOM
            it.startsWith("mid") -> InningHalf.MIDDLE
            it.startsWith("end") -> InningHalf.END
            else -> null
        }
    }

    private fun team(competitor: JSONObject): FixtureTeam? {
        val json = competitor.optJSONObject("team") ?: return null
        val name = json.text("displayName") ?: json.text("name") ?: return null
        val short = json.text("shortDisplayName")?.takeIf { it != name }
        val alternatives = listOfNotNull(json.text("location"), json.text("name"), json.text("nickname"), json.text("alternateDisplayName"))
            .filter { it != name && it != short }.distinct()
        val records = competitor.optJSONArray("records")?.let(::objects).orEmpty()
        val record = (records.firstOrNull { it.text("type") == "total" || it.text("name")?.lowercase() == "overall" } ?: records.firstOrNull())
            ?.text("summary")?.takeIf { RECORD.matches(it) }
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
            else -> competitor.text("score")?.substringBefore(" (")
        }?.takeIf { it.length <= 24 }
    }

    private fun periods(competitor: JSONObject?, total: String?): List<String> {
        val lines = competitor?.optJSONArray("linescores")?.let(::objects).orEmpty()
        if (lines.any { it.has("runs") || it.has("wickets") }) return emptyList()
        val values = lines.filter { (it.optIntOrNull("period") ?: 1) in 1..MAX_PERIODS }.sortedBy { it.optIntOrNull("period") ?: 0 }.take(MAX_PERIODS).mapNotNull { line ->
            (line.text("displayValue") ?: line.text("value")?.let(::wholeNumber))?.takeIf { it.length <= 8 }
        }
        return SportsLines.perPeriod(values, total)
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

    private val EVENT_SPORTS = setOf("soccer", "rugby-league", "rugby")
    private val GOAL_IDS = setOf("70", "137", "138", "173")
    private val YELLOW_IDS = setOf("94")
    private val RED_IDS = setOf("93")
    private const val MAX_NAME = 40
    private val SKIPPED = listOf("POSTPONED", "CANCELED", "CANCELLED", "ABANDONED", "FORFEIT", "SUSPENDED", "DELAYED")
    private val COLOUR = Regex("[0-9A-Fa-f]{6}")
    private val RECORD = Regex("\\d{1,3}(-\\d{1,3}){1,3}")
    private val INITIAL = Regex("""[\p{L}.\-]{1,6}\.\s+(.+)""")
    private val CRICKET = Regex("""\(([\d.]+)(?:/(\d+))? ov(?:, target (\d+))?""")
    private val TENNIS_ORDER = listOf(FixtureStatus.LIVE, FixtureStatus.SCHEDULED, FixtureStatus.FINAL)
    private const val MAX_PERIODS = 12
    private const val MAX_SETS = 5
    private const val MAX_SESSIONS = 12
    private const val MAX_BOUTS = 24
    const val MAX_MATCHES = 60
    private const val RECENT_MILLIS = 12L * 60 * 60 * 1000
}

object SportsLines {
    fun perPeriod(values: List<String>, total: String?): List<String> {
        val numbers = values.map { it.toIntOrNull() ?: return values }
        val sum = total?.toIntOrNull() ?: return values
        val played = numbers.dropLastWhile { it == 0 }.takeIf { it.size < numbers.size && it.size >= 2 && it.last() == sum && it.sum() != sum }
        val list = played ?: numbers
        if (list.size < 2 || list.sum() == sum || list.last() != sum || list.zipWithNext().any { (a, b) -> b < a }) return values
        return list.mapIndexed { index, value -> (value - (list.getOrNull(index - 1) ?: 0)).toString() }
    }
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
            if (started) FixtureLine(awayScore) else null, null, event.text("strLeagueBadge")?.let(::sportsImage), source = SportsService.THESPORTSDB)
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

    internal val FINISHED = setOf("match finished", "ft", "aet", "pen", "ft pen", "aot", "final", "finished", "after over time", "after extra time", "after penalties", "ended", "full time")
    internal val NOT_STARTED = setOf("not started", "ns", "tbd", "time to be defined", "scheduled")
    internal val PERIODS = mapOf("1h" to 1, "2h" to 2, "et" to 3, "q1" to 1, "q2" to 2, "q3" to 3, "q4" to 4, "p1" to 1, "p2" to 2, "p3" to 3)
    internal val SKIPPED = setOf("postponed", "pst", "canc", "cancelled", "canceled", "abandoned", "abd", "awd", "wo", "susp", "suspended", "int", "interrupted")
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
