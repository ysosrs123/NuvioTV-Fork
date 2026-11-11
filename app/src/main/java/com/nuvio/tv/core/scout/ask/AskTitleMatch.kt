package com.nuvio.tv.core.scout.ask

import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import java.text.Normalizer
import kotlin.math.abs

private const val MIN_VOTES_WITHOUT_YEAR = 20

private val MARKS = Regex("\\p{M}+")
private val NOT_LETTER_OR_DIGIT = Regex("[^\\p{L}\\p{N}]+")
private val YEAR = Regex("\\d{4}")

internal fun normaliseTitle(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(MARKS, "")
        .lowercase()
        .replace(NOT_LETTER_OR_DIGIT, "")

/** What this profile has watched or started, so an answer doesn't suggest it again. */
data class AskSeen(
    val ids: Set<String> = emptySet(),
    val names: Set<String> = emptySet(),
    val watchedIds: Set<String> = emptySet(),
    val watchedNames: Set<String> = emptySet()
) {
    fun blocks(meta: com.nuvio.tv.domain.model.MetaPreview): Boolean = matches(meta, ids, names)

    fun finished(meta: com.nuvio.tv.domain.model.MetaPreview): Boolean = matches(meta, watchedIds, watchedNames)

    private fun matches(meta: com.nuvio.tv.domain.model.MetaPreview, ids: Set<String>, names: Set<String>): Boolean {
        if (meta.id in ids) return true
        val name = normaliseTitle(meta.name)
        if (name in names) return true
        val year = meta.releaseInfo?.take(4)?.toIntOrNull() ?: return false
        return (year - 1..year + 1).any { "$name|$it" in names }
    }
}

internal fun askSeenName(name: String, releaseInfo: String?): String {
    val year = releaseInfo?.let { YEAR.find(it)?.value }
    return normaliseTitle(name) + (year?.let { "|$it" } ?: "")
}

/**
 * The search result that is this title, or null when none clearly is: a wrong film is worse than a
 * shorter list. Results can be in the user's metadata language, so a different name with the right
 * year still counts, but only as search's own top result.
 */
internal fun bestTitleMatch(results: List<TmdbDiscoverResult>, wanted: AskPick): TmdbDiscoverResult? {
    val name = normaliseTitle(wanted.title)
    if (name.isEmpty()) return null
    fun TmdbDiscoverResult.sameName() =
        listOfNotNull(title, this.name, originalTitle, originalName).any { normaliseTitle(it) == name }
    val year = wanted.year
    if (year == null) {
        return results.firstOrNull { it.sameName() }
            ?: results.firstOrNull()?.takeIf { (it.voteCount ?: 0) >= MIN_VOTES_WITHOUT_YEAR }
    }
    fun gap(result: TmdbDiscoverResult): Int =
        (result.releaseDate ?: result.firstAirDate)?.take(4)?.toIntOrNull()?.let { abs(it - year) } ?: Int.MAX_VALUE
    return results.firstOrNull { it.sameName() && gap(it) == 0 }
        ?: results.firstOrNull { it.sameName() && gap(it) <= 2 }
        ?: results.firstOrNull()?.takeIf { gap(it) <= 1 }
}
