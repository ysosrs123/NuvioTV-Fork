package com.nuvio.tv.core.scout.sync

import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.domain.model.ScoutRatingEntry
import com.nuvio.tv.domain.model.ScoutTitle
import kotlin.math.abs

/** A film or show as the trackers know it. */
data class TrackerTitle(val movie: Boolean, val imdb: String? = null, val tmdb: Long? = null) {
    val hasIds: Boolean get() = imdb != null || tmdb != null

    /** Keys under which the same title can be found on both sides; tmdb ids repeat between films and shows. */
    val keys: List<String>
        get() = listOfNotNull(
            imdb?.let { "imdb:$it" },
            tmdb?.let { "tmdb:${if (movie) "m" else "s"}:$it" }
        )
}

data class TrackerRating(
    val title: TrackerTitle,
    val value: Double,
    val ratedAtMs: Long? = null,
    val name: String? = null
)

data class TrackerRatingWrite(val title: TrackerTitle, val value: Double, val ratedAtMs: Long)

fun ScoutRating.trackerValue(): Double = when (this) {
    ScoutRating.NOT_FOR_ME -> 3.0
    ScoutRating.LIKE -> 7.0
    ScoutRating.LOVE -> 10.0
}

/** 9-10 love, 6-8 like, 1-4 not for me; a 5, or anything under 1, says nothing. */
fun scoutRatingFromTracker(value: Double): ScoutRating? = when {
    value >= 9.0 -> ScoutRating.LOVE
    value >= 6.0 -> ScoutRating.LIKE
    value in 1.0..4.99 -> ScoutRating.NOT_FOR_ME
    else -> null
}

internal fun sameTrackerValue(a: Double, b: Double): Boolean = abs(a - b) < 0.26

fun ScoutTitle.trackerTitle(): TrackerTitle? {
    val ids = parseTrackingExternalIds(id)
    val movie = type.trim().lowercase() in setOf("movie", "film")
    return TrackerTitle(movie, ids.imdb?.takeIf { it.startsWith("tt") }, ids.tmdb?.takeIf { it > 0 }).takeIf { it.hasIds }
}

fun TrackerTitle.scoutContentId(): String = imdb ?: "tmdb:$tmdb"

fun Collection<TrackerRating>.byKey(): Map<String, TrackerRating> {
    val out = HashMap<String, TrackerRating>()
    forEach { rating -> rating.title.keys.forEach { out.putIfAbsent(it, rating) } }
    return out
}

fun Map<String, TrackerRating>.find(title: TrackerTitle): TrackerRating? = title.keys.firstNotNullOfOrNull { this[it] }

/** What Arc wants a tracker to hold for one title: a value, or nothing (a cleared thumb). */
data class OutAction(val rating: ScoutRating?, val stamp: Long)

sealed interface PushStep {
    data class Write(val value: Double) : PushStep
    data object Remove : PushStep
    /** The tracker already holds what Arc wants; [value] is what to remember as sent. */
    data class Done(val value: Double?) : PushStep
    /** The tracker holds a rating the user gave there; it stays and Arc forgets what it sent. */
    data object Keep : PushStep
}

/**
 * Arc only ever replaces or removes a value it sent itself. [lastSent] is what Arc last sent this tracker
 * for the title, [remote] what the tracker holds now.
 */
fun planPush(action: OutAction, remote: Double?, lastSent: Double?): PushStep {
    val arcOwns = remote != null && lastSent != null && sameTrackerValue(remote, lastSent)
    val wanted = action.rating?.trackerValue()
    return when {
        wanted == null && remote == null -> PushStep.Done(null)
        wanted == null -> if (arcOwns) PushStep.Remove else PushStep.Keep
        remote == null -> PushStep.Write(wanted)
        sameTrackerValue(remote, wanted) -> PushStep.Done(wanted)
        arcOwns -> PushStep.Write(wanted)
        else -> PushStep.Keep
    }
}

data class ImportChange(val contentId: String, val entry: ScoutRatingEntry?)

/**
 * Turns one tracker's full list of ratings into thumbs. A thumb pressed in Arc always wins, a title the user
 * cleared in Arc stays clear, a rating Arc sent or is about to send is left alone, and only thumbs this
 * tracker brought in are changed or removed.
 */
fun planImport(
    source: TrackingProviderId,
    remote: Collection<TrackerRating>,
    local: Map<String, ScoutRatingEntry>,
    dismissed: Set<String>,
    sentByArc: Map<String, Double>,
    pending: Set<String>,
    now: Long
): List<ImportChange> {
    val sourceId = source.storageId
    val localByKey = HashMap<String, String>()
    local.forEach { (id, entry) -> entry.title.trackerTitle()?.keys?.forEach { localByKey.putIfAbsent(it, id) } }
    val changes = LinkedHashMap<String, ImportChange>()
    val seen = HashSet<String>()
    remote.forEach { rating ->
        val existingId = rating.title.keys.firstNotNullOfOrNull { localByKey[it] }
        val id = existingId ?: rating.title.scoutContentId()
        if (!seen.add(id) || id in dismissed || id in pending) return@forEach
        val sent = sentByArc[id]
        if (sent != null && sameTrackerValue(sent, rating.value)) return@forEach
        val existing = local[id]
        if (existing != null && existing.source != sourceId) return@forEach
        val mapped = scoutRatingFromTracker(rating.value)
        if (mapped == null) {
            if (existing != null) changes[id] = ImportChange(id, null)
            return@forEach
        }
        if (existing?.rating == mapped) return@forEach
        val name = rating.name?.takeIf(String::isNotBlank) ?: existing?.title?.name ?: return@forEach
        val title = existing?.title ?: ScoutTitle(id, if (rating.title.movie) "movie" else "series", name)
        changes[id] = ImportChange(id, ScoutRatingEntry(title, mapped, rating.ratedAtMs ?: now, sourceId))
    }
    local.forEach { (id, entry) ->
        if (entry.source == sourceId && id !in seen && id !in changes) changes[id] = ImportChange(id, null)
    }
    return changes.values.toList()
}
