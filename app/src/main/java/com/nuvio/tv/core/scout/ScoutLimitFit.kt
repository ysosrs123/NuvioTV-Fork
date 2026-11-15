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
internal class ScoutLimit(val gate: RatingLimitGate, val limit: RatingLimit) {
    val code: String get() = limit.code
}

internal suspend fun scoutLimit(context: Context): ScoutLimit? {
    val gate = ratingLimitGateOrNull(context) ?: return null
    val limit = withContext(Dispatchers.IO) { gate.activeLimit() } ?: return null
    return ScoutLimit(gate, limit)
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
        kept += limit.gate.keepFitting(limit.limit, batch, idOf)
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
