package com.nuvio.tv.core.iptv

import org.json.JSONArray
import org.json.JSONObject

enum class InningHalf { TOP, MIDDLE, BOTTOM, END }

data class TennisPlayer(val seed: Int? = null, val flag: String? = null, val country: String? = null)
data class TennisSet(val home: Int? = null, val away: Int? = null, val homeTiebreak: Int? = null, val awayTiebreak: Int? = null, val winner: FixtureSide? = null)
data class GolfPlayer(val position: String, val name: String, val shortName: String? = null, val country: String? = null, val flag: String? = null,
    val toPar: String? = null, val thru: String? = null, val today: String? = null, val teeMillis: Long? = null)
data class RaceSession(val abbreviation: String?, val name: String, val startMillis: Long, val state: FixtureStatus, val top: List<String> = emptyList())
data class Fighter(val name: String, val shortName: String? = null, val record: String? = null, val flag: String? = null)
data class FightBout(val weightClass: String?, val rounds: Int?, val first: Fighter, val second: Fighter, val winner: Int? = null, val startMillis: Long,
    val state: FixtureStatus = FixtureStatus.SCHEDULED, val round: Int? = null, val mainCard: Boolean = false)
data class CricketInnings(val team: String, val runs: Int, val wickets: Int, val overs: String? = null, val maxOvers: Int? = null, val target: Int? = null,
    val batting: Boolean = false)
data class CricketChase(val runs: Int, val balls: Int, val rate: Double)

sealed class SportsDetail {
    data class Tennis(val tournament: String?, val round: String?, val court: String?, val homePlayer: TennisPlayer, val awayPlayer: TennisPlayer,
        val sets: List<TennisSet>, val server: FixtureSide? = null) : SportsDetail() {
        val servingForSet: Boolean get() {
            val side = server ?: return false
            val set = sets.lastOrNull()?.takeIf { it.winner == null } ?: return false
            val own = (if (side == FixtureSide.HOME) set.home else set.away) ?: return false
            val other = (if (side == FixtureSide.HOME) set.away else set.home) ?: return false
            return own <= 6 && own + 1 >= 6 && own + 1 - other >= 2
        }
        val setsWon: Pair<Int, Int> get() = sets.count { it.winner == FixtureSide.HOME } to sets.count { it.winner == FixtureSide.AWAY }
    }

    data class Golf(val tournament: String, val round: Int? = null, val statusText: String? = null, val purse: String? = null,
        val leaders: List<GolfPlayer> = emptyList(), val endMillis: Long? = null) : SportsDetail()

    data class Sessions(val venue: String?, val sessions: List<RaceSession>) : SportsDetail() {
        val current: RaceSession? get() = sessions.firstOrNull { it.state == FixtureStatus.LIVE } ?: sessions.firstOrNull { it.state == FixtureStatus.SCHEDULED }
            ?: sessions.lastOrNull()
    }

    data class Card(val bouts: List<FightBout>) : SportsDetail() {
        val mainEvent: FightBout? get() = bouts.lastOrNull()
        val mainCard: List<FightBout> get() = bouts.filter { it.mainCard }
        val prelims: List<FightBout> get() = bouts.filter { !it.mainCard }
        val live: FightBout? get() = bouts.firstOrNull { it.state == FixtureStatus.LIVE }
    }

    data class Cricket(val innings: List<CricketInnings>) : SportsDetail() {
        val chase: CricketChase? get() {
            val chasing = innings.lastOrNull() ?: return null
            val target = chasing.target ?: return null
            val max = chasing.maxOvers ?: return null
            val bowled = SportsDetails.balls(chasing.overs ?: "0") ?: return null
            val runs = target - chasing.runs
            val balls = max * 6 - bowled
            if (runs <= 0 || balls <= 0 || chasing.wickets >= 10) return null
            return CricketChase(runs, balls, runs * 6.0 / balls)
        }
    }

    data class Baseball(val inning: Int? = null, val half: InningHalf? = null, val outs: Int? = null, val balls: Int? = null, val strikes: Int? = null,
        val first: Boolean = false, val second: Boolean = false, val third: Boolean = false) : SportsDetail()
}

object SportsDetails {
    private const val FORMAT = 1
    private val OVERS = Regex("""(\d{1,3})(?:\.(\d))?""")

    fun balls(overs: String): Int? {
        val match = OVERS.matchEntire(overs.trim()) ?: return null
        val part = match.groupValues[2].toIntOrNull() ?: 0
        if (part > 5) return null
        return match.groupValues[1].toInt() * 6 + part
    }

    fun encode(detail: SportsDetail): JSONObject = JSONObject().apply {
        put("v", FORMAT)
        when (detail) {
            is SportsDetail.Tennis -> {
                put("type", "tennis"); detail.tournament?.let { put("tournament", it) }; detail.round?.let { put("round", it) }
                detail.court?.let { put("court", it) }; put("homePlayer", player(detail.homePlayer)); put("awayPlayer", player(detail.awayPlayer))
                put("sets", JSONArray().apply { detail.sets.forEach { set -> put(JSONObject().apply {
                    set.home?.let { put("home", it) }; set.away?.let { put("away", it) }; set.homeTiebreak?.let { put("homeTiebreak", it) }
                    set.awayTiebreak?.let { put("awayTiebreak", it) }; set.winner?.let { put("winner", it.name) }
                }) } })
                detail.server?.let { put("server", it.name) }
            }
            is SportsDetail.Golf -> {
                put("type", "golf"); put("tournament", detail.tournament); detail.round?.let { put("round", it) }
                detail.statusText?.let { put("status", it) }; detail.purse?.let { put("purse", it) }; detail.endMillis?.let { put("end", it) }
                put("leaders", JSONArray().apply { detail.leaders.forEach { player -> put(JSONObject().apply {
                    put("position", player.position); put("name", player.name); player.shortName?.let { put("short", it) }
                    player.country?.let { put("country", it) }; player.flag?.let { put("flag", it) }; player.toPar?.let { put("toPar", it) }
                    player.thru?.let { put("thru", it) }; player.today?.let { put("today", it) }; player.teeMillis?.let { put("tee", it) }
                }) } })
            }
            is SportsDetail.Sessions -> {
                put("type", "sessions"); detail.venue?.let { put("venue", it) }
                put("sessions", JSONArray().apply { detail.sessions.forEach { session -> put(JSONObject().apply {
                    session.abbreviation?.let { put("abbreviation", it) }; put("name", session.name); put("start", session.startMillis); put("state", session.state.name)
                    if (session.top.isNotEmpty()) put("top", JSONArray(session.top))
                }) } })
            }
            is SportsDetail.Card -> {
                put("type", "card")
                put("bouts", JSONArray().apply { detail.bouts.forEach { bout -> put(JSONObject().apply {
                    bout.weightClass?.let { put("weightClass", it) }; bout.rounds?.let { put("rounds", it) }; put("first", fighter(bout.first))
                    put("second", fighter(bout.second)); bout.winner?.let { put("winner", it) }; put("start", bout.startMillis); put("state", bout.state.name)
                    bout.round?.let { put("round", it) }; put("mainCard", bout.mainCard)
                }) } })
            }
            is SportsDetail.Cricket -> {
                put("type", "cricket")
                put("innings", JSONArray().apply { detail.innings.forEach { innings -> put(JSONObject().apply {
                    put("team", innings.team); put("runs", innings.runs); put("wickets", innings.wickets); innings.overs?.let { put("overs", it) }
                    innings.maxOvers?.let { put("maxOvers", it) }; innings.target?.let { put("target", it) }; put("batting", innings.batting)
                }) } })
            }
            is SportsDetail.Baseball -> {
                put("type", "baseball"); detail.inning?.let { put("inning", it) }; detail.half?.let { put("half", it.name) }
                detail.outs?.let { put("outs", it) }; detail.balls?.let { put("balls", it) }; detail.strikes?.let { put("strikes", it) }
                put("first", detail.first); put("second", detail.second); put("third", detail.third)
            }
        }
    }

    fun decode(json: JSONObject): SportsDetail? = runCatching {
        when (json.text("type")) {
            "tennis" -> SportsDetail.Tennis(json.text("tournament"), json.text("round"), json.text("court"),
                json.optJSONObject("homePlayer")?.let(::player) ?: TennisPlayer(), json.optJSONObject("awayPlayer")?.let(::player) ?: TennisPlayer(),
                json.objects("sets").map { set ->
                    TennisSet(set.int("home"), set.int("away"), set.int("homeTiebreak"), set.int("awayTiebreak"), side(set.text("winner")))
                }, side(json.text("server")))
            "golf" -> SportsDetail.Golf(json.text("tournament") ?: return null, json.int("round"), json.text("status"), json.text("purse"),
                json.objects("leaders").mapNotNull { player ->
                    GolfPlayer(player.text("position") ?: return@mapNotNull null, player.text("name") ?: return@mapNotNull null, player.text("short"),
                        player.text("country"), player.text("flag"), player.text("toPar"), player.text("thru"), player.text("today"), player.long("tee"))
                }.take(MAX_LEADERS), json.long("end"))
            "sessions" -> SportsDetail.Sessions(json.text("venue"), json.objects("sessions").mapNotNull { session ->
                RaceSession(session.text("abbreviation"), session.text("name") ?: return@mapNotNull null, session.long("start") ?: return@mapNotNull null,
                    status(session.text("state")), session.optJSONArray("top")?.let { top -> (0 until minOf(top.length(), 3)).mapNotNull { top.optString(it).takeIf(String::isNotEmpty) } }.orEmpty())
            })
            "card" -> SportsDetail.Card(json.objects("bouts").mapNotNull { bout ->
                FightBout(bout.text("weightClass"), bout.int("rounds"), bout.optJSONObject("first")?.let(::fighter) ?: return@mapNotNull null,
                    bout.optJSONObject("second")?.let(::fighter) ?: return@mapNotNull null, bout.int("winner"), bout.long("start") ?: return@mapNotNull null,
                    status(bout.text("state")), bout.int("round"), bout.optBoolean("mainCard"))
            })
            "cricket" -> SportsDetail.Cricket(json.objects("innings").mapNotNull { innings ->
                CricketInnings(innings.text("team") ?: return@mapNotNull null, innings.int("runs") ?: 0, innings.int("wickets") ?: 0, innings.text("overs"),
                    innings.int("maxOvers"), innings.int("target"), innings.optBoolean("batting"))
            })
            "baseball" -> SportsDetail.Baseball(json.int("inning"), InningHalf.entries.firstOrNull { it.name == json.text("half") }, json.int("outs"),
                json.int("balls"), json.int("strikes"), json.optBoolean("first"), json.optBoolean("second"), json.optBoolean("third"))
            else -> null
        }
    }.getOrNull()

    private fun player(player: TennisPlayer) = JSONObject().apply {
        player.seed?.let { put("seed", it) }; player.flag?.let { put("flag", it) }; player.country?.let { put("country", it) }
    }

    private fun player(json: JSONObject) = TennisPlayer(json.int("seed"), json.text("flag"), json.text("country"))

    private fun fighter(fighter: Fighter) = JSONObject().apply {
        put("name", fighter.name); fighter.shortName?.let { put("short", it) }; fighter.record?.let { put("record", it) }; fighter.flag?.let { put("flag", it) }
    }

    private fun fighter(json: JSONObject): Fighter? = json.text("name")?.let { Fighter(it, json.text("short"), json.text("record"), json.text("flag")) }

    private fun side(value: String?): FixtureSide? = FixtureSide.entries.firstOrNull { it.name == value }
    private fun status(value: String?): FixtureStatus = FixtureStatus.entries.firstOrNull { it.name == value } ?: FixtureStatus.SCHEDULED

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf(String::isNotEmpty)
    private fun JSONObject.int(key: String): Int? = if (!has(key) || isNull(key)) null else optInt(key)
    private fun JSONObject.long(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)
    private fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { array ->
        (0 until minOf(array.length(), MAX_ITEMS)).mapNotNull { array.optJSONObject(it) }
    }.orEmpty()

    const val MAX_LEADERS = 10
    private const val MAX_ITEMS = 40
}
