package com.nuvio.tv.core.ratinglimit

/** The highest rating a profile may play without the main profile's PIN, on one scale for films and series. */
enum class RatingLimit(val code: String, val highest: Int, val label: String, val scoutCeiling: Int) {
    PRESCHOOL("y", 0, "G · TV-Y", 0),
    YOUNG("g", 1, "G · TV-Y7 · TV-G", 0),
    PG("pg", 2, "PG · TV-PG", 1),
    TEEN("m", 3, "M · PG-13 · TV-14", 2);

    fun allows(rank: Int?): Boolean = rank != null && rank <= highest

    companion object {
        fun fromCode(code: String?): RatingLimit? = entries.firstOrNull { it.code == code }
    }
}

/** US film and TV ratings on one scale: 0 pre-school, 1 young kids, 2 PG, 3 teens, 4 adults. Anything else is unknown. */
fun ratingRank(certification: String?): Int? = when (certification?.trim()?.uppercase()) {
    "G", "TV-Y" -> 0
    "TV-Y7", "TV-Y7-FV", "TV-G" -> 1
    "PG", "TV-PG" -> 2
    "PG-13", "TV-14" -> 3
    "R", "NC-17", "TV-MA" -> 4
    else -> null
}

/** The US rating of a film: the first one given for any release. */
fun usFilmCertification(countries: List<Pair<String?, List<String?>>>): String? =
    countries.firstOrNull { it.first == "US" }?.second?.firstOrNull { ratingRank(it) != null }

private val RATED_CATALOG_TYPES = setOf("movie", "series")

/** Add-on rows of other types (downloads, cloud files, channels) carry no rating, so a profile with a limit leaves them out. */
fun List<com.nuvio.tv.domain.model.Addon>.withRatedCatalogsOnly(): List<com.nuvio.tv.domain.model.Addon> =
    map { addon -> addon.copy(catalogs = addon.catalogs.filter { it.apiType.lowercase() in RATED_CATALOG_TYPES }) }

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface RatingLimitEntryPoint {
    fun ratingLimits(): RatingLimitGate
}

fun ratingLimitGate(context: android.content.Context): RatingLimitGate =
    dagger.hilt.android.EntryPointAccessors.fromApplication(context, RatingLimitEntryPoint::class.java).ratingLimits()

/**
 * Null only when the application is not a Hilt application, which is never the case in the app itself (plain unit
 * tests); callers then filter nothing. Any other failure is thrown, so a limit can't be skipped quietly.
 */
fun ratingLimitGateOrNull(context: android.content.Context): RatingLimitGate? {
    val app = runCatching { context.applicationContext }.getOrNull()
    if (app !is dagger.hilt.internal.GeneratedComponentManager<*> && app !is dagger.hilt.internal.GeneratedComponentManagerHolder) return null
    return ratingLimitGate(context)
}

/** Episodes and seasons count as their show. */
fun ratingTitleKey(contentId: String, contentType: String): String {
    val tv = contentType.trim().lowercase() != "movie"
    val base = contentId.substringBefore(':').takeIf { contentId.startsWith("tt") }
        ?: contentId.takeIf { it.startsWith("tmdb:") }?.split(':')?.take(2)?.joinToString(":")
        ?: contentId
    return (if (tv) "tv|" else "movie|") + base
}
