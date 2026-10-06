package com.nuvio.tv.core.iptv

import java.text.Normalizer
import java.util.Locale

const val SPORTS_AHEAD_MILLIS = 6L * 60 * 60 * 1000
const val SPORTS_OPEN_ENDED_MILLIS = 3L * 60 * 60 * 1000

object SportsGuide {
    private val SPORT_WORDS = setOf(
        "sport", "sports", "deporte", "deportes", "esporte", "esportes", "desporto", "sportif", "sportive", "sporten",
        "football", "soccer", "futbol", "fussball", "calcio", "futebol", "voetbal", "fotboll", "fotball", "fodbold",
        "rugby", "league", "cricket", "tennis", "golf", "basketball", "baloncesto", "baseball", "hockey", "boxing", "boxe", "boxen",
        "mma", "wrestling", "athletics", "cycling", "ciclismo", "racing", "motorsport", "motorsports", "motogp", "formula",
        "darts", "snooker", "volleyball", "handball", "netball", "afl", "nrl", "nfl", "nba", "nhl", "mlb", "ufc", "f1", "superbike")
    private val COMPETITIONS = listOf(
        "premier league", "champions league", "europa league", "conference league", "a league", "a leagues", "la liga", "serie a", "bundesliga",
        "ligue 1", "eredivisie", "primeira liga", "mls", "world cup", "fa cup", "efl", "copa", "libertadores", "brasileirao", "campeonato",
        "nrl", "afl", "aflw", "nrlw", "nfl", "nba", "wnba", "nhl", "mlb", "ufc", "formula 1", "formula one", "f1", "grand prix",
        "motogp", "supercars", "nascar", "indycar", "super rugby", "six nations", "rugby championship", "state of origin",
        "test match", "the ashes", "big bash", "bbl", "wbbl", "ipl", "odi", "t20", "wimbledon", "us open", "australian open",
        "roland garros", "french open", "atp", "wta", "tour de france", "giro", "vuelta", "olympic", "olympics", "paralympic",
        "super bowl", "world series", "stanley cup", "ryder cup", "pga", "lpga", "liv golf", "melbourne cup",
        "derby", "match of the day", "monday night football", "sunday night football", "thursday night football",
        "ncaa", "college football", "college basketball", "wrestlemania").map(::normalise)
    private val EXCLUDED_TITLE = listOf(
        "highlights", "highlight", "preview", "previews", "review", "magazine", "news", "betting", "odds", "tips", "tipping",
        "podcast", "talk", "debate", "documentary", "resumen", "resumo", "zusammenfassung", "sintesi", "melhores momentos", "resume",
        "draft", "awards", "quiz").map(::normalise)
    private val NON_SPORT_CATEGORIES = listOf(
        "movie", "movies", "film", "films", "drama", "comedy", "news", "documentary", "documentaries", "children", "kids",
        "animation", "cartoon", "soap", "reality", "magazine", "talk", "music", "cooking", "lifestyle", "travel", "series").map(::normalise)
    private val LIVE_MARKERS = listOf("live", "en vivo", "en directo", "ao vivo", "en direct", "in diretta", "direkt").map(::normalise)
    private val SPORT_CHANNELS = listOf(
        "sport", "sports", "espn", "bein", "dazn", "eurosport", "kayo", "optus sport", "supersport", "sportsnet", "tsn",
        "arena sport", "fox league", "fox footy", "fox cricket", "nba tv", "nfl network", "mlb network", "nhl network",
        "golf", "racing", "premier league", "laliga", "motogp", "f1 tv", "stan sport", "setanta", "match", "deportes", "esporte").map(::normalise)
    private val FIXTURE = Regex("""\S.*\s(?:v|vs|vs\.|versus|@|contra|gegen)\s+\S""")

    fun isSportsProgramme(titles: List<LocalizedGuideText>, categories: List<String>, sportsChannel: Boolean): Boolean {
        val words = titles.map { normalise(it.text) }.filter(String::isNotEmpty)
        if (words.isEmpty()) return false
        val genres = categories.map(::normalise).filter(String::isNotEmpty)
        if (words.any { title -> EXCLUDED_TITLE.any { contains(title, it) } }) return false
        val sportGenre = genres.any { genre -> genre.split(' ').any { it in SPORT_WORDS } && NON_SPORT_CATEGORIES.none { contains(genre, it) } }
        if (!sportGenre && genres.any { genre -> NON_SPORT_CATEGORIES.any { contains(genre, it) } }) return false
        if (sportGenre) return true
        if (words.any { title -> COMPETITIONS.any { contains(title, it) } }) return true
        val fixture = titles.any { FIXTURE.containsMatchIn(it.text.lowercase(Locale.ROOT)) }
        val live = words.any { title -> LIVE_MARKERS.any { contains(title, it) } }
        val sportWord = words.any { title -> title.split(' ').any { it in SPORT_WORDS } }
        return (fixture && (live || sportsChannel || sportWord)) || (sportsChannel && (sportWord || live))
    }

    fun isSportsChannel(names: List<LocalizedGuideText>): Boolean =
        names.map { normalise(it.text) }.any { name -> SPORT_CHANNELS.any { contains(name, it) } }

    fun normalise(value: String): String {
        val decomposed = Normalizer.normalize(foldSearchText(value), Normalizer.Form.NFD)
        val plain = StringBuilder(decomposed.length)
        for (char in decomposed) {
            when {
                Character.getType(char) == Character.NON_SPACING_MARK.toInt() -> Unit
                char == 'ß' -> plain.append("ss")
                char.isLetterOrDigit() -> plain.append(char)
                else -> plain.append(' ')
            }
        }
        return plain.toString().split(' ').filter(String::isNotEmpty).joinToString(" ")
    }

    private fun contains(text: String, needle: String): Boolean {
        var from = 0
        while (true) {
            val index = text.indexOf(needle, from)
            if (index < 0) return false
            val end = index + needle.length
            if ((index == 0 || text[index - 1] == ' ') && (end == text.length || text[end] == ' ')) return true
            from = index + 1
        }
    }
}

fun <T> sportsOrder(listings: List<Pair<T, GuideProgramme>>, nowMillis: Long): List<Pair<T, GuideProgramme>> =
    listings.sortedWith(compareBy<Pair<T, GuideProgramme>>({ if (it.second.start.epochMillis <= nowMillis) 0 else 1 },
        { it.second.start.epochMillis }, { it.second.titles.firstOrNull()?.text.orEmpty() }))
