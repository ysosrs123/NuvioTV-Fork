package com.nuvio.tv.core.scout

import android.content.Context
import com.nuvio.tv.core.ratinglimit.RatingLimit
import com.nuvio.tv.core.ratinglimit.RatingLimitGate
import com.nuvio.tv.core.ratinglimit.ratingLimitGateOrNull
import com.nuvio.tv.domain.model.ScoutMix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LIMIT_BATCH = 20

/** The rating limit of the profile in use, with the gate that checks titles against it. */
internal class ScoutLimit(val gate: RatingLimitGate, val limit: RatingLimit, val keepUnknown: Boolean = false) {
    val code: String get() = limit.code
}

internal suspend fun scoutLimit(context: Context): ScoutLimit? {
    val gate = ratingLimitGateOrNull(context) ?: return null
    val limit = withContext(Dispatchers.IO) { gate.activeLimit() } ?: return null
    return ScoutLimit(gate, limit)
}

/**
 * The check for a family mix, on top of the local rating TMDB was asked for: a show whose US rating is above TV-PG is
 * left out, as some countries rate anime for adults PG. Without a limit a show with no US rating stays; with one, the
 * stricter of the two rules applies in a single pass, so a long list is never cut short before the limit sees it.
 */
internal fun scoutFamilyLimit(limit: ScoutLimit?, gate: RatingLimitGate?): ScoutLimit? = when {
    limit != null -> if (limit.limit.highest <= RatingLimit.PG.highest) limit else ScoutLimit(limit.gate, RatingLimit.PG)
    gate != null -> ScoutLimit(gate, RatingLimit.PG, keepUnknown = true)
    else -> null
}

/** The check for one of Scout's own rows: the profile's limit, and on Family night the family rule on top. */
internal suspend fun scoutRowLimit(gate: RatingLimitGate?, family: Boolean): ScoutLimit? {
    gate ?: return null
    val limit = withContext(Dispatchers.IO) { gate.activeLimit() }?.let { ScoutLimit(gate, it) }
    return if (family) scoutFamilyLimit(limit, gate) else limit
}

/**
 * The items whose rating fits the limit, checked a batch at a time and stopping once [enough] fit, so a long list
 * does not ask TMDB about every title. Without a limit the list comes back as it is.
 */
internal suspend fun <T> scoutKeepFitting(
    limit: ScoutLimit?,
    items: List<T>,
    enough: Int = Int.MAX_VALUE,
    idOf: (T) -> Pair<String, String>
): List<T> {
    if (limit == null || items.isEmpty()) return items
    val kept = ArrayList<T>()
    for (batch in items.chunked(LIMIT_BATCH)) {
        kept += limit.gate.keepFitting(limit.limit, batch, limit.keepUnknown, idOf)
        if (kept.size >= enough) break
    }
    return kept
}

/** The mix with only the shows that fit the limit. */
internal suspend fun ScoutMix.fittingLimit(limit: ScoutLimit?): ScoutMix {
    if (limit == null) return this
    val fit = scoutKeepFitting(limit, shows) { it.id to "series" }
    return if (fit.size == shows.size) this else copy(shows = fit)
}
