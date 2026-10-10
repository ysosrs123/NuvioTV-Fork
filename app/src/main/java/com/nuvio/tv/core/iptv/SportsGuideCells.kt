package com.nuvio.tv.core.iptv

data class SportsGuideLink(val fixture: SportsFixture, val channelId: String, val programme: GuideProgramme, val reason: FixtureLinkReason)

data class SportsGuideChip(val score: String?, val clock: String?)

class SportsGuideIndex(private val cells: Map<String, Map<Long, SportsFixture>>, private val spans: Map<String, LongArray>) {
    val empty: Boolean get() = cells.isEmpty()

    fun at(channelId: String, startMillis: Long): SportsFixture? = cells[channelId]?.get(startMillis)

    fun linked(channelId: String): Boolean = channelId in cells

    fun within(channelId: String, fromMillis: Long, untilMillis: Long): Boolean {
        val span = spans[channelId] ?: return false
        var index = 0
        while (index < span.size) {
            if (span[index] < untilMillis && span[index + 1] > fromMillis) return true
            index += 2
        }
        return false
    }

    companion object { val EMPTY = SportsGuideIndex(emptyMap(), emptyMap()) }
}

object SportsGuideCells {
    const val SOON_MILLIS = 30L * 60 * 1000
    private val BADGES = mapOf("a-league-men" to "ALM", "a-league-women" to "ALW", "super-rugby" to "SRP", "big-bash" to "BBL",
        "epl" to "EPL", "champions-league" to "UCL", "la-liga" to "LIGA", "serie-a" to "SA", "bundesliga" to "BUN", "pga" to "PGA",
        "nascar-cup" to "NAS", "urc" to "URC", "college-football" to "CFB",
        "ligue-1" to "L1", "europa-league" to "UEL", "conference-league" to "UECL", "championship" to "EFL", "fa-cup" to "FAC", "efl-cup" to "EFLC",
        "scottish-premiership" to "SPFL", "eredivisie" to "ERE", "primeira-liga" to "POR", "super-lig" to "TSL", "saudi-pro-league" to "SPL", "liga-mx" to "LMX",
        "womens-champions-league" to "UWCL", "g-league" to "GL", "womens-college-basketball" to "WCBB", "college-hockey" to "CHK", "womens-college-hockey" to "WCHK",
        "dp-world-tour" to "DPWT", "nascar-xfinity" to "NXS", "nascar-truck" to "NTS", "indycar" to "INDY", "premiership-rugby" to "PREM", "top-14" to "T14",
        "champions-cup" to "ECC", "six-nations" to "6N", "rugby-championship" to "TRC")
    private val ALIASES by lazy {
        SportsLeagues.ALL.flatMap { league -> (league.aliases + league.name).map { SportsGuide.normalise(it) to league.id } }
            .filter { it.first.length >= 2 }.sortedByDescending { it.first.length }
    }

    fun index(links: List<SportsGuideLink>): SportsGuideIndex {
        if (links.isEmpty()) return SportsGuideIndex.EMPTY
        val best = HashMap<String, HashMap<Long, SportsGuideLink>>()
        for (link in links) {
            val start = link.programme.start.epochMillis
            val row = best.getOrPut(link.channelId) { HashMap() }
            val current = row[start]
            if (current == null || better(link, current)) row[start] = link
        }
        val cells = HashMap<String, Map<Long, SportsFixture>>(best.size)
        val spans = HashMap<String, LongArray>(best.size)
        for ((channel, row) in best) {
            cells[channel] = row.mapValuesTo(HashMap(row.size)) { it.value.fixture }
            val span = LongArray(row.size * 2)
            var index = 0
            for (link in row.values) {
                val start = link.programme.start.epochMillis
                span[index++] = start
                span[index++] = link.programme.stop?.epochMillis?.takeIf { it > start } ?: (start + SportsRefresh.durationMillis(link.fixture))
            }
            spans[channel] = span
        }
        return SportsGuideIndex(cells, spans)
    }

    private fun better(link: SportsGuideLink, current: SportsGuideLink): Boolean {
        if (link.reason != current.reason) return link.reason.ordinal < current.reason.ordinal
        val start = link.programme.start.epochMillis
        return kotlin.math.abs(link.fixture.startMillis - start) < kotlin.math.abs(current.fixture.startMillis - start)
    }

    fun badge(league: String): String = BADGES[league] ?: SportsLeagues.byId(league)?.let { known ->
        if (known.name.length <= 4) known.name.uppercase() else known.name.split(' ', '-').filter(String::isNotEmpty).joinToString("") { it.take(1) }.uppercase().take(4)
    } ?: league.split(' ', '-', '_').filter(String::isNotEmpty).joinToString("") { it.take(1) }.uppercase().take(4)

    fun league(titles: List<LocalizedGuideText>): String? {
        val words = titles.map { " " + SportsGuide.normalise(it.text) + " " }
        return ALIASES.firstOrNull { (alias, _) -> words.any { it.contains(" $alias ") } }?.second
    }

    fun hidden(fixture: SportsFixture, showScores: Boolean, spoilerKeys: Set<String>): Boolean = !showScores || fixture.key in spoilerKeys

    fun chip(fixture: SportsFixture, hidden: Boolean): SportsGuideChip? {
        if (fixture.status != FixtureStatus.LIVE) return null
        val bug = SportsFixtureText.bug(fixture)
        val clock = bug.state?.takeIf(String::isNotBlank)
        if (hidden) return clock?.let { SportsGuideChip(null, it) }
        val detail = fixture.sportDetail
        val score = when {
            detail is SportsDetail.Tennis -> detail.sets.filter { it.home != null || it.away != null }
                .joinToString(" ") { "${it.home ?: 0}-${it.away ?: 0}" }.takeIf(String::isNotEmpty)
            fixture.teams && detail !is SportsDetail.Cricket -> SportsFixtureText.scores(fixture)?.let { (home, away) ->
                if (SportsFixtureText.awayFirst(fixture)) "$away–$home" else "$home–$away" }
            detail is SportsDetail.Cricket -> bug.primary.takeIf { it.length <= 18 }
            else -> null
        }
        if (score == null && clock == null) return null
        return SportsGuideChip(score, clock)
    }

    fun label(fixture: SportsFixture, hidden: Boolean): String {
        val home = fixture.home
        val away = fixture.away
        if (fixture.status == FixtureStatus.SCHEDULED || hidden) {
            if (home != null && away != null && fixture.sportDetail == null)
                return if (SportsFixtureText.awayFirst(fixture)) "${SportsFixtureText.code(away)} @ ${SportsFixtureText.code(home)}"
                    else "${SportsFixtureText.code(home)} v ${SportsFixtureText.code(away)}"
            if (hidden) return fixture.title
        }
        return SportsFixtureText.bug(fixture).primary
    }

    fun close(fixture: SportsFixture, hidden: Boolean): Boolean = !hidden && SportsFixtureSections.close(fixture)

    fun progress(fixture: SportsFixture, nowMillis: Long): Float? {
        if (fixture.status != FixtureStatus.LIVE) return null
        if (fixture.sport == "soccer") fixture.clock?.trim()?.substringBefore('\'')?.substringBefore('+')?.trim()?.toIntOrNull()
            ?.let { return (it / 90f).coerceIn(0f, 1f) }
        val elapsed = nowMillis - fixture.startMillis
        if (elapsed <= 0) return 0f
        return (elapsed.toFloat() / SportsRefresh.durationMillis(fixture)).coerceIn(0f, 1f)
    }

    fun <T> lane(items: List<T>, fixture: (T) -> SportsFixture, nowMillis: Long, soonMillis: Long = SOON_MILLIS): List<T> {
        val seen = HashSet<String>()
        return items.filter { item ->
            val game = fixture(item)
            val wanted = game.status == FixtureStatus.LIVE ||
                (game.status == FixtureStatus.SCHEDULED && game.startMillis <= nowMillis + soonMillis && game.startMillis > nowMillis - SportsRefresh.durationMillis(game))
            wanted && seen.add(game.key)
        }.sortedWith(compareBy<T>({ if (fixture(it).status == FixtureStatus.LIVE) 0 else 1 }, { fixture(it).startMillis }, { fixture(it).key }))
    }
}
