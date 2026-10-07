package com.nuvio.tv.core.iptv

enum class HomeRowKind(val key: String, val enabledByDefault: Boolean) {
    FAVOURITES("favourites", true), SPORT("sport", true), MOVIES("movies", false), SERIES("series", false), RECORDINGS("recordings", true)
}

data class HomeRowSettings(val enabled: Set<HomeRowKind> = DEFAULT) {
    fun shows(kind: HomeRowKind): Boolean = kind in enabled
    fun with(kind: HomeRowKind, on: Boolean): HomeRowSettings = HomeRowSettings(if (on) enabled + kind else enabled - kind)

    companion object {
        val DEFAULT: Set<HomeRowKind> = HomeRowKind.entries.filter { it.enabledByDefault }.toSet()
        fun read(saved: (HomeRowKind) -> Boolean?): HomeRowSettings =
            HomeRowSettings(HomeRowKind.entries.filter { saved(it) ?: it.enabledByDefault }.toSet())
    }
}

object HomeRows {
    const val FAVOURITES = 24
    const val SPORT = 12
    const val TITLES = 20
    const val RECORDINGS = 12
    const val CACHE_MILLIS = 3L * 60 * 1000
    const val NOW_SPAN_MILLIS = 30L * 60 * 1000
    const val PROGRAMMES_PER_CHANNEL = 4

    fun order(settings: HomeRowSettings, sportAvailable: Boolean): List<HomeRowKind> =
        HomeRowKind.entries.filter { settings.shows(it) && (it != HomeRowKind.SPORT || sportAvailable) }

    fun fresh(loadedAt: Long?, now: Long, maxAge: Long = CACHE_MILLIS): Boolean =
        loadedAt != null && now >= loadedAt && now - loadedAt < maxAge

    fun <T> merged(groups: List<List<T>>, id: (T) -> String, limit: Int): List<T> {
        require(limit > 0)
        val seen = HashSet<String>()
        val result = ArrayList<T>()
        for (group in groups) for (item in group) {
            if (result.size >= limit) return result
            if (seen.add(id(item))) result += item
        }
        return result
    }

    fun airing(programme: GuideProgramme, now: Long): Boolean =
        programme.start.epochMillis <= now && (programme.stop?.epochMillis?.let { it > now } ?: (now - programme.start.epochMillis < SPORTS_OPEN_ENDED_MILLIS))

    fun nowPlaying(programmes: Iterable<GuideProgramme>, now: Long): GuideProgramme? =
        programmes.filter { airing(it, now) }.maxWithOrNull(compareBy({ it.start.epochMillis }, { -(it.stop?.epochMillis ?: Long.MAX_VALUE) }))

    fun progress(programme: GuideProgramme, now: Long): Float? {
        val stop = programme.stop?.epochMillis ?: return null
        val span = stop - programme.start.epochMillis
        if (span <= 0) return null
        return ((now - programme.start.epochMillis).toDouble() / span).toFloat().coerceIn(0f, 1f)
    }

    fun <T> sportOnNow(listings: List<Pair<T, GuideProgramme>>, id: (T) -> String, now: Long, limit: Int): List<Pair<T, GuideProgramme>> {
        require(limit > 0)
        val seen = HashSet<String>()
        return sportsOrder(listings.filter { airing(it.second, now) }, now).filter { seen.add(id(it.first)) }.take(limit)
    }

    fun <T> recentTitles(groups: List<List<T>>, id: (T) -> String, addedSeconds: (T) -> Long?, limit: Int): List<T> {
        require(limit > 0)
        val seen = HashSet<String>()
        return groups.flatten().filter { seen.add(id(it)) }.sortedByDescending { addedSeconds(it) ?: Long.MIN_VALUE }.take(limit)
    }

    fun <T> recentRecordings(items: List<T>, status: (T) -> RecordingStatus, endedAt: (T) -> Long, limit: Int): List<T> {
        require(limit > 0)
        return items.filter { status(it) == RecordingStatus.DONE || status(it) == RecordingStatus.PARTIAL }.sortedByDescending(endedAt).take(limit)
    }

    fun logo(attribute: String?): String? = attribute?.trim()
        ?.takeIf { it.length <= 2048 && (it.startsWith("https://") || it.startsWith("http://")) }
}
