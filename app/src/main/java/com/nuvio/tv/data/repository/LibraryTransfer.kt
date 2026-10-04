package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput

/**
 * Provider-agnostic library transfer planning, pure and side-effect free: it
 * decides what a copy or move would do; a service performs it. Copy is the
 * default; move is copy-then-remove. The plan dedups the source, skips entries
 * already in the destination and counts entries without a usable id.
 *
 * Matching uses [normalizeContentId] (imdb "tt..." preferred, then
 * "tmdb:{n}"). An entry with only a trakt or simkl id counts as unmatched.
 */

enum class LibraryTransferMode { COPY, MOVE }

data class LibraryTransferPlan(
    /** Deduped, matched, not-already-in-destination - the actual write set. */
    val toWrite: List<LibraryEntryInput>,
    /** Source entries already in the destination (skipped). */
    val alreadyPresent: Int,
    /** Source entries with no imdb/tmdb id (cannot be written). */
    val unmatched: Int,
    /** Source entries collapsed as duplicates of an earlier entry. */
    val duplicates: Int,
    /** Size of the source library that was planned. */
    val sourceTotal: Int
) {
    val willWrite: Int get() = toWrite.size
    val isEmpty: Boolean get() = toWrite.isEmpty()
}

/** Canonical key for dedup and destination-presence tests. */
internal fun transferKey(entry: LibraryEntry): String =
    normalizeContentId(toTraktIds(parseContentIds(entry.id))).ifBlank { entry.id.trim() }

/** True when the entry carries an imdb or tmdb id a destination can resolve. */
internal fun LibraryEntry.hasResolvableId(): Boolean {
    if (!imdbId.isNullOrBlank() || tmdbId != null) return true
    val parsed = parseContentIds(id)
    return !parsed.imdb.isNullOrBlank() || parsed.tmdb != null
}

/** LibraryEntry -> LibraryEntryInput, preserving identity and display fields. */
internal fun LibraryEntry.toTransferInput(): LibraryEntryInput = LibraryEntryInput(
    itemId = id,
    itemType = type,
    title = name,
    year = extractYear(releaseInfo),
    traktId = traktId,
    simklId = simklId,
    imdbId = imdbId,
    tmdbId = tmdbId,
    poster = poster,
    posterShape = posterShape,
    background = background,
    logo = logo,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
    addonBaseUrl = addonBaseUrl
)

/**
 * Plans a copy of [sourceEntries] into a destination whose current contents are
 * given as canonical keys [destinationKeys] (e.g. `destEntries.map(::transferKey)`).
 */
internal fun computeTransferPlan(
    sourceEntries: List<LibraryEntry>,
    destinationKeys: Set<String>
): LibraryTransferPlan {
    var alreadyPresent = 0
    var unmatched = 0
    var duplicates = 0
    val seen = HashSet<String>()
    val toWrite = mutableListOf<LibraryEntryInput>()
    for (entry in sourceEntries) {
        if (!entry.hasResolvableId()) {
            unmatched++
            continue
        }
        val key = transferKey(entry)
        if (key in destinationKeys) {
            alreadyPresent++
            continue
        }
        if (!seen.add(key)) {
            duplicates++
            continue
        }
        toWrite += entry.toTransferInput()
    }
    return LibraryTransferPlan(
        toWrite = toWrite,
        alreadyPresent = alreadyPresent,
        unmatched = unmatched,
        duplicates = duplicates,
        sourceTotal = sourceEntries.size
    )
}
