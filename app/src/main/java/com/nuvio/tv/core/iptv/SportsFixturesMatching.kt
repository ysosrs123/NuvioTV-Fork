package com.nuvio.tv.core.iptv

data class FixtureChannel(val id: String, val name: String, val category: String? = null, val hidden: Boolean = false)
data class FixtureListing(val channelId: String, val programme: GuideProgramme)
enum class FixtureLinkReason { GUIDE_TEAMS, GUIDE_LEAGUE, BROADCASTER }
enum class SportsChannelSource {
    GUIDE, BROADCASTERS, BOTH;
    val guide: Boolean get() = this != BROADCASTERS
    val broadcasters: Boolean get() = this != GUIDE
}
data class FixtureLink(val channelId: String, val reason: FixtureLinkReason, val programme: GuideProgramme? = null, val broadcaster: String? = null)

object SportsGuideSlices {
    const val SLICE_MILLIS = 60L * 60 * 1000
    const val MAX_SLICES = 24 * SportsDays.MAX_DAYS
    const val MAX_RECENT_SLICES = 8

    fun plan(upcoming: List<SportsFixture>, recent: List<SportsFixture>, nowMillis: Long, maxSlices: Int = MAX_SLICES,
        maxRecent: Int = MAX_RECENT_SLICES): Map<Long, List<SportsFixture>> {
        val ahead = upcoming.groupBy { Math.floorDiv(maxOf(it.startMillis, nowMillis - SLICE_MILLIS), SLICE_MILLIS) }
        val behind = recent.groupBy { Math.floorDiv(it.startMillis, SLICE_MILLIS) }
        val kept = ahead.keys.sorted().take(maxSlices) + behind.keys.sortedDescending().take(maxRecent)
        return kept.distinct().sorted().associateWith { slice -> (ahead[slice].orEmpty() + behind[slice].orEmpty()).distinctBy { it.key } }
    }
}

object SportsFixtureMatching {
    const val MAX_LINKS = 6
    private const val LEAD_MILLIS = 30L * 60 * 1000
    private const val OPEN_PROGRAMME_MILLIS = 3L * 60 * 60 * 1000

    fun link(fixtures: List<SportsFixture>, channels: List<FixtureChannel>, listings: List<FixtureListing>, hiddenCategories: Set<String>,
        nowMillis: Long, max: Int = MAX_LINKS, source: SportsChannelSource = SportsChannelSource.BOTH): Map<String, List<FixtureLink>> {
        require(max > 0)
        val visible = channels.filter { !it.hidden && (it.category?.trim().orEmpty()) !in hiddenCategories }.distinctBy { it.id }
        val order = visible.withIndex().associate { it.value.id to it.index }
        val byChannel = if (source.guide) listings.filter { it.channelId in order } else emptyList()
        val brands = visible.associate { it.id to channelBrand(it.name) }
        return fixtures.associate { fixture ->
            val found = mutableListOf<Pair<FixtureLink, Long>>()
            for (listing in byChannel) {
                val reason = guideMatch(fixture, listing.programme, nowMillis) ?: continue
                found += FixtureLink(listing.channelId, reason, listing.programme) to kotlin.math.abs(listing.programme.start.epochMillis - fixture.startMillis)
            }
            if (source.broadcasters) for (broadcaster in fixture.broadcasters) {
                val wanted = channelBrand(broadcaster) ?: continue
                for (channel in visible) if (brands[channel.id]?.let { sameBrand(wanted, it) } == true)
                    found += FixtureLink(channel.id, FixtureLinkReason.BROADCASTER, broadcaster = broadcaster) to Long.MAX_VALUE
            }
            fixture.id to found.sortedWith(compareBy({ it.first.reason.ordinal }, { it.second }, { order[it.first.channelId] ?: Int.MAX_VALUE }))
                .map { it.first }.distinctBy { it.channelId }.take(max)
        }.filterValues { it.isNotEmpty() }
    }

    fun overlaps(fixture: SportsFixture, programme: GuideProgramme, nowMillis: Long): Boolean {
        val programmeStart = programme.start.epochMillis
        val programmeEnd = programme.stop?.epochMillis ?: (programmeStart + OPEN_PROGRAMME_MILLIS)
        return windows(fixture, nowMillis).any { (start, end) -> programmeStart < end && programmeEnd > start }
    }

    private fun windows(fixture: SportsFixture, nowMillis: Long): List<Pair<Long, Long>> {
        val duration = SportsRefresh.durationMillis(fixture)
        fun around(start: Long, live: Boolean) = (start - LEAD_MILLIS) to (if (live) maxOf(start + duration, nowMillis + LEAD_MILLIS) else start + duration)
        val plain = listOf(around(fixture.startMillis, fixture.status == FixtureStatus.LIVE))
        return when (val detail = fixture.sportDetail) {
            is SportsDetail.Sessions -> detail.sessions.map { around(it.startMillis, it.state == FixtureStatus.LIVE) }.ifEmpty { plain }
            is SportsDetail.Golf -> listOf((fixture.startMillis - LEAD_MILLIS) to maxOf((detail.endMillis ?: (fixture.startMillis + 3 * DAY_MILLIS)) + DAY_MILLIS,
                if (fixture.status == FixtureStatus.LIVE) nowMillis + LEAD_MILLIS else 0L))
            is SportsDetail.Card -> {
                val last = detail.bouts.maxOfOrNull { it.startMillis } ?: fixture.startMillis
                listOf((fixture.startMillis - LEAD_MILLIS) to maxOf(fixture.startMillis + duration, last + CARD_MILLIS,
                    if (fixture.status == FixtureStatus.LIVE) nowMillis + LEAD_MILLIS else 0L))
            }
            else -> plain
        }
    }

    fun guideMatch(fixture: SportsFixture, programme: GuideProgramme, nowMillis: Long): FixtureLinkReason? {
        if (!programme.start.precise || !overlaps(fixture, programme, nowMillis)) return null
        val titles = programme.titles.map { SportsGuide.normalise(it.text) }.filter(String::isNotEmpty)
        val named = SportsGuide.normalise(listOfNotNull(fixture.title, (fixture.sportDetail as? SportsDetail.Tennis)?.tournament,
            (fixture.sportDetail as? SportsDetail.Golf)?.tournament).joinToString(" "))
        if (titles.isEmpty() || titles.any { title -> EXCLUDED.any { has(title, it) && !has(named, it) } }) return null
        val text = (titles + programme.descriptions.take(4).map { SportsGuide.normalise(it.text.take(600)) }).joinToString(" | ")
        val league = SportsLeagues.byId(fixture.league)
        val leagueWords = (league?.aliases.orEmpty() + SPORT_WORDS[fixture.sport].orEmpty()).map(SportsGuide::normalise).distinct()
        val ownLeague = leagueWords.any { has(text, it) }
        val ownAliases = league?.aliases.orEmpty().map(SportsGuide::normalise).toSet()
        val otherLeague = SportsLeagues.ALL.filter { it.id != fixture.league }.flatMap { it.aliases }.map(SportsGuide::normalise)
            .filter { it !in ownAliases && ownAliases.none { own -> has(own, it) || has(it, own) } }.any { has(text, it) }
        if (otherLeague && !ownLeague) return null
        val home = fixture.home
        val away = fixture.away
        val event = home == null || away == null || fixture.sportDetail is SportsDetail.Tennis
        val fixtureWomen = league?.women == true || listOfNotNull(fixture.home?.name, fixture.away?.name).any { women(SportsGuide.normalise(it)) }
        if (if (event) women(text) && !fixtureWomen else women(text) != fixtureWomen) return null
        if (youth(text) != youth(SportsGuide.normalise(listOfNotNull(fixture.home?.name, fixture.away?.name, fixture.title).joinToString(" ")))) return null
        if (event) return eventMatch(fixture, text, titles.joinToString(" | "), leagueWords, league?.aliases.orEmpty(), ownLeague)
        home!!; away!!
        val homeHit = teamHit(home, text)
        val awayHit = teamHit(away, text)
        if (homeHit != null && awayHit != null) {
            return if (homeHit || awayHit || ownLeague) FixtureLinkReason.GUIDE_TEAMS else null
        }
        if (ownLeague && (homeHit == true || awayHit == true)) return FixtureLinkReason.GUIDE_LEAGUE
        return null
    }

    private fun eventMatch(fixture: SportsFixture, text: String, titles: String, leagueWords: List<String>, aliases: List<String>,
        ownLeague: Boolean): FixtureLinkReason? {
        val detail = fixture.sportDetail
        val name = SportsGuide.normalise(when (detail) {
            is SportsDetail.Tennis -> detail.tournament.orEmpty()
            is SportsDetail.Golf -> detail.tournament
            else -> fixture.title
        })
        val stop = leagueWords.flatMap { it.split(' ') }.toSet()
        val words = name.split(' ').filter { word -> word !in TITLE_STOP && word !in stop && (word.length >= 4 || (word.length >= 2 && word.all(Char::isDigit))) }.distinct()
        val hits = words.count { has(text, it) }
        val eventHit = (name.contains(' ') && has(text, name)) || (ownLeague && hits > 0) || hits >= 2
        val home = fixture.home
        val away = fixture.away
        val tennis = detail is SportsDetail.Tennis && home != null && away != null
        if (tennis) {
            val players = listOf(home!!, away!!).count { team -> (team.strongNames + team.weakNames).any { value -> variants(value).any { it.length >= 3 && has(text, it) } } }
            if (players == 2 || (players == 1 && (ownLeague || eventHit))) return FixtureLinkReason.GUIDE_TEAMS
        }
        if (eventHit) return if (tennis) FixtureLinkReason.GUIDE_LEAGUE else FixtureLinkReason.GUIDE_TEAMS
        if (!ownLeague) return null
        if (NUMBERED_EVENT.findAll(titles).any { !has(name, it.value) }) return null
        var rest = " $titles "
        val own = aliases.map(SportsGuide::normalise).filter(String::isNotEmpty)
        (own + name).filter(String::isNotEmpty).sortedByDescending { it.length }.forEach { rest = rest.replace(" $it ", " ") }
        val known = (name.split(' ') + own.flatMap { it.split(' ') } + "|").toSet()
        val tokens = rest.split(' ').filter(String::isNotEmpty)
        for (marker in EVENT_MARKERS) {
            if (!has(rest, marker)) continue
            if (!has(name, marker)) return null
            val parts = marker.split(' ')
            for (index in 1..tokens.size - parts.size) if (tokens.subList(index, index + parts.size) == parts && tokens[index - 1] !in known) return null
        }
        return FixtureLinkReason.GUIDE_LEAGUE
    }

    fun broadcasterMatches(broadcaster: String, channelName: String): Boolean {
        val wanted = channelBrand(broadcaster) ?: return false
        return channelBrand(channelName)?.let { sameBrand(wanted, it) } == true
    }

    fun searchTerms(broadcasters: List<String>, max: Int = 12): List<String> = broadcasters.flatMap { name ->
        val brand = channelBrand(name) ?: return@flatMap emptyList()
        BRANDS.firstOrNull { it.id == brand.id }?.search ?: listOf(brand.tokens.joinToString(" "))
    }.map { it.trim() }.filter { it.length >= 2 }.distinct().take(max)

    private fun teamHit(team: FixtureTeam, text: String): Boolean? {
        if (team.strongNames.any { name -> variants(name).any { has(text, it) } }) return true
        if (team.weakNames.any { name -> variants(name).any { it.length >= 3 && has(text, it) } }) return false
        return null
    }

    internal fun variants(name: String): List<String> {
        val base = SportsGuide.normalise(name)
        if (base.isEmpty()) return emptyList()
        val words = base.split(' ')
        val trimmed = words.dropWhile { it == "the" }.let { list -> if (list.size > 1 && list.last() in CLUB_SUFFIXES) list.dropLast(1) else list }
            .let { list -> if (list.size > 1 && list.first() in CLUB_PREFIXES) list.drop(1) else list }
        val short = trimmed.map { SHORTENED[it] ?: it }
        return listOf(base, trimmed.joinToString(" "), short.joinToString(" ")).filter { it.isNotEmpty() }.distinct()
    }

    private fun women(text: String): Boolean = WOMEN.any { has(text, it) }

    private fun youth(text: String): Set<String> = buildSet {
        YOUTH.findAll(text).forEach { add(it.value.replace(" ", "").replace("under", "u")) }
        listOf("youth", "reserves", "academy").filter { has(text, it) }.forEach(::add)
    }

    internal fun has(text: String, needle: String): Boolean {
        if (needle.isEmpty()) return false
        var from = 0
        while (true) {
            val index = text.indexOf(needle, from)
            if (index < 0) return false
            val end = index + needle.length
            if ((index == 0 || !text[index - 1].isLetterOrDigit()) && (end == text.length || !text[end].isLetterOrDigit())) return true
            from = index + 1
        }
    }

    internal data class BrandHit(val id: String, val number: Int?, val qualifier: List<String>, val tokens: List<String>)

    internal fun channelBrand(name: String): BrandHit? {
        val tokens = tokens(name)
        if (tokens.isEmpty()) return null
        var best: Pair<Brand, Pair<Int, List<String>>>? = null
        for (brand in BRANDS) for (alias in brand.aliasTokens) {
            val at = if (brand.exact) (if (tokens.take(alias.size) == alias) 0 else -1) else indexOf(tokens, alias)
            if (at < 0) continue
            if (best == null || alias.size > best.second.second.size) best = brand to (at to alias)
        }
        val (brand, hit) = best ?: return BrandHit("", null, tokens.filter { it !in NOISE }, tokens)
        val rest = tokens.drop(hit.first + hit.second.size).filter { it !in NOISE && it !in REGIONS }
        if (brand.exact) return BrandHit(brand.id, null, rest, tokens)
        val number = rest.firstOrNull()?.takeIf { it.length <= 3 && it.all(Char::isDigit) }?.toInt()
        return BrandHit(brand.id, number, if (number != null) rest.drop(1) else rest, tokens)
    }

    private fun sameBrand(wanted: BrandHit, channel: BrandHit): Boolean {
        if (wanted.id.isEmpty()) return channel.id.isEmpty() && wanted.qualifier.isNotEmpty() && wanted.qualifier.joinToString("").length >= 3 &&
            wanted.qualifier == channel.qualifier.filter { it !in REGIONS }
        if (wanted.id != channel.id || wanted.qualifier != channel.qualifier) return false
        return if (wanted.number != null) channel.number == wanted.number else channel.number == null || channel.number == 1
    }

    private fun indexOf(tokens: List<String>, alias: List<String>): Int {
        for (index in 0..tokens.size - alias.size) if (tokens.subList(index, index + alias.size) == alias) return index
        return -1
    }

    private fun tokens(name: String): List<String> {
        val cleaned = name.replace(COUNTRY_PREFIX, "").replace(BRACKETS, " ")
        return SportsGuide.normalise(cleaned).replace(LETTER_DIGIT, " ").split(' ').filter(String::isNotEmpty)
            .map { if (it == "sports") "sport" else it }.filter { it !in DROPPED }
    }

    private class Brand(val id: String, aliases: List<String>, val exact: Boolean = false, val search: List<String> = aliases) {
        val aliasTokens = aliases.map { alias -> SportsGuide.normalise(alias).replace(LETTER_DIGIT, " ").split(' ').filter(String::isNotEmpty).map { if (it == "sports") "sport" else it } }
    }

    private val COUNTRY_PREFIX = Regex("""^\s*[A-Za-z]{2,3}\s*[:|]\s*""")
    private val BRACKETS = Regex("""[\[(][^\])]*[\])]""")
    private val LETTER_DIGIT = Regex("""(?<=[a-z])(?=\d)|(?<=\d)(?=[a-z])""")
    private val YOUTH = Regex("""\bu ?\d{2}\b|\bunder \d{2}\b""")
    private val DROPPED = setOf("channel", "network", "tv")
    private val NOISE = setOf("hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h264", "h265", "fps", "raw", "backup", "alt", "vip", "live", "hdr",
        "1080p", "720p", "1080", "720", "50", "60", "plus", "au", "aus", "uk", "us", "usa", "nz", "ie", "ca", "en", "eng", "english", "feed")
    private val REGIONS = setOf("australia", "sydney", "melbourne", "brisbane", "adelaide", "perth", "hobart", "darwin", "canberra", "gold", "coast",
        "nsw", "vic", "qld", "sa", "wa", "tas", "nt", "act", "regional", "metro", "east", "west", "north", "south", "queensland", "victoria",
        "united", "kingdom", "ireland", "canada", "zealand", "new")
    private val EXCLUDED = listOf("highlights", "highlight", "replay", "classic", "classics", "rewind", "condensed", "mini match", "review", "preview",
        "magazine", "news", "podcast", "talk", "tipping", "draft").map(SportsGuide::normalise)
    private val WOMEN = listOf("women", "womens", "woman", "ladies", "aflw", "nrlw", "wbbl", "wnba", "wta", "lpga", "femenino", "feminino", "feminine", "feminin",
        "frauen", "femminile", "liga f", "wsl", "w league")
    private val TITLE_STOP = setOf("grand", "prix", "the", "and", "of", "de", "del", "la", "le", "di", "race", "round", "fight", "night", "main", "card",
        "event", "vs", "v", "presented", "by", "gp", "formula", "one", "championship", "season", "week", "open", "masters", "classic", "tour", "series", "cup",
        "invitational", "international", "championships", "tournament", "singles", "doubles", "mens", "womens", "final", "finals")
    private val EVENT_MARKERS = listOf("grand prix", "gp", "open", "masters", "classic", "championship", "championships", "invitational", "fight night", "cup", "trophy")
    private val NUMBERED_EVENT = Regex("""\bufc \d{2,4}\b""")
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    private const val CARD_MILLIS = 3L * 60 * 60 * 1000
    private val CLUB_SUFFIXES = setOf("fc", "afc", "cf", "sc", "sfc", "rlfc", "rfc", "fk", "ac", "bc", "sk")
    private val CLUB_PREFIXES = setOf("fc", "afc", "cf", "ac", "as", "ss", "sv", "vfb", "vfl", "rc", "rcd", "cd", "ud", "sd", "us", "ssc", "tsg", "1")
    private val SHORTENED = mapOf("manchester" to "man", "united" to "utd", "saint" to "st", "wanderers" to "wanderers", "hotspur" to "hotspur")
    private val SPORT_WORDS = mapOf(
        "soccer" to listOf("football", "soccer", "futbol", "calcio", "fussball"),
        "australian-football" to listOf("afl", "footy", "aussie rules"),
        "rugby-league" to listOf("rugby league", "nrl"),
        "rugby" to listOf("rugby", "rugby union"),
        "cricket" to listOf("cricket", "t20"),
        "basketball" to listOf("basketball"),
        "american-football" to listOf("nfl", "american football"),
        "baseball" to listOf("baseball"),
        "ice-hockey" to listOf("hockey", "ice hockey"),
        "motorsport" to listOf("formula 1", "f1", "motor racing", "motorsport"),
        "tennis" to listOf("tennis"),
        "golf" to listOf("golf"),
        "mma" to listOf("ufc", "mma"))
    private val BRANDS = listOf(
        Brand("fox-footy", listOf("fox footy")), Brand("fox-league", listOf("fox league")), Brand("fox-cricket", listOf("fox cricket")),
        Brand("fox-sports", listOf("fox sports", "fs"), search = listOf("fox sports", "fs1", "fs 1")), Brand("fox", listOf("fox"), exact = true),
        Brand("kayo", listOf("kayo", "kayo sports")), Brand("optus-sport", listOf("optus sport")), Brand("bein", listOf("bein", "bein sports")),
        Brand("sky-sports", listOf("sky sports")), Brand("tnt-sports", listOf("tnt sports")), Brand("tnt", listOf("tnt"), exact = true),
        Brand("dazn", listOf("dazn")), Brand("espn", listOf("espn")), Brand("espnu", listOf("espnu")), Brand("espn-news", listOf("espnews", "espn news")),
        Brand("stan-sport", listOf("stan sport")), Brand("eurosport", listOf("eurosport")), Brand("supersport", listOf("supersport")),
        Brand("sportsnet", listOf("sportsnet")), Brand("tsn", listOf("tsn")), Brand("premier-sports", listOf("premier sports")),
        Brand("seven", listOf("seven", "7"), exact = true, search = listOf("seven", "7 hd", "channel 7", "7 sydney", "7 melbourne")),
        Brand("7mate", listOf("7mate", "7 mate"), exact = true, search = listOf("7mate")), Brand("7two", listOf("7two", "7 two"), exact = true, search = listOf("7two")),
        Brand("7plus", listOf("7plus", "7 plus"), exact = true, search = listOf("7plus")),
        Brand("nine", listOf("nine", "9"), exact = true, search = listOf("nine", "9 hd", "channel 9", "9 sydney", "9 melbourne")),
        Brand("9gem", listOf("9gem", "9 gem"), exact = true, search = listOf("9gem")), Brand("9go", listOf("9go", "9 go"), exact = true, search = listOf("9go")),
        Brand("9now", listOf("9now", "9 now"), exact = true, search = listOf("9now")),
        Brand("ten", listOf("ten", "10"), exact = true, search = listOf("network 10", "channel 10", "10 hd", "ten hd", "10 sydney")),
        Brand("10bold", listOf("10 bold", "10bold"), exact = true, search = listOf("10 bold", "10bold")),
        Brand("10peach", listOf("10 peach", "10peach"), exact = true, search = listOf("10 peach", "10peach")),
        Brand("sbs", listOf("sbs"), exact = true), Brand("sbs-viceland", listOf("sbs viceland"), exact = true),
        Brand("abc", listOf("abc"), exact = true), Brand("cbs", listOf("cbs"), exact = true), Brand("nbc", listOf("nbc"), exact = true),
        Brand("nbcsn", listOf("nbcsn", "nbc sports")), Brand("cbs-sports", listOf("cbs sports")), Brand("peacock", listOf("peacock")),
        Brand("nfl-network", listOf("nfl network"), exact = true, search = listOf("nfl network", "nfl")),
        Brand("nba-tv", listOf("nba tv"), exact = true, search = listOf("nba tv", "nba")),
        Brand("mlb-network", listOf("mlb network"), exact = true, search = listOf("mlb network", "mlb")),
        Brand("nhl-network", listOf("nhl network"), exact = true, search = listOf("nhl network", "nhl")),
        Brand("tbs", listOf("tbs"), exact = true), Brand("tvnz", listOf("tvnz")), Brand("sky-sport-nz", listOf("sky sport nz")),
    )
}
