package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference

/**
 * A film and a show can share a TMDB or TVDB number, so a library match that turns a film into a
 * show or back is not trusted. Anime references carry no film or show type and are left alone.
 */
internal fun SimklSyncSnapshot.enrichMediaReferenceOfSameType(
    reference: TrackingMediaReference,
    entryIndex: SimklEntryIndex?
): TrackingMediaReference {
    val enriched = enrichMediaReference(reference, entryIndex)
    val wasFilm = reference.kind == TrackingMediaKind.MOVIE
    val isFilm = enriched.kind == TrackingMediaKind.MOVIE
    return if (reference.kind != TrackingMediaKind.ANIME && wasFilm != isFilm) reference else enriched
}
