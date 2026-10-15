package com.nuvio.tv.core.calendar

/**
 * Posters for titles none of their sources gave one, by id alias. The addon's own poster comes first when the
 * title's details are already in memory, as on Home, then the one Continue Watching saved. Nothing is fetched.
 */
fun calendarCachedPosters(
    candidates: List<CalendarTitleCandidate>,
    metaPoster: (type: String, id: String) -> String?,
    nextUpPosters: List<Pair<String, String?>>
): Map<String, String> {
    val missing = candidates.filter { it.poster.isNullOrBlank() }
    if (missing.isEmpty()) return emptyMap()
    val wanted = missing.flatMapTo(HashSet()) { it.ids.aliases() }
    val posters = HashMap<String, String>()
    nextUpPosters.forEach { (contentId, poster) ->
        val url = poster?.takeIf(String::isNotBlank) ?: return@forEach
        CalendarIds.fromContentId(contentId).aliases().filter { it in wanted }.forEach { posters.putIfAbsent(it, url) }
    }
    missing.forEach { candidate ->
        val type = if (candidate.type == CalendarTitleType.MOVIE) "movie" else "series"
        val url = listOfNotNull(candidate.contentId, candidate.ids.imdb, candidate.ids.tmdb?.let { "tmdb:$it" })
            .distinct()
            .firstNotNullOfOrNull { id -> metaPoster(type, id)?.takeIf(String::isNotBlank) }
            ?: return@forEach
        candidate.ids.aliases().forEach { posters[it] = url }
    }
    return posters
}

fun List<CalendarFollowedTitle>.withCachedPosters(posters: Map<String, String>): List<CalendarFollowedTitle> =
    if (posters.isEmpty()) this else map { title ->
        if (!title.poster.isNullOrBlank()) return@map title
        title.ids.aliases().firstNotNullOfOrNull(posters::get)?.let { title.copy(poster = it) } ?: title
    }
