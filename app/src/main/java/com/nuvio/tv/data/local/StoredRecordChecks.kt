package com.nuvio.tv.data.local

import com.google.gson.Gson
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.WatchedItem

/** Gson fills a missing key with null even where Kotlin says it cannot be; these read the fields as nullable. */
@Suppress("SENSELESS_COMPARISON", "USELESS_CAST")
internal fun WatchedItem.completeOrNull(): WatchedItem? {
    if ((contentId as String?).isNullOrBlank() || (contentType as String?).isNullOrBlank()) return null
    return if ((title as String?) == null) copy(title = "") else this
}

internal fun parseWatchedItem(gson: Gson, raw: String): WatchedItem? =
    runCatching { gson.fromJson(raw, WatchedItem::class.java) }.getOrNull()?.completeOrNull()

@Suppress("SENSELESS_COMPARISON", "USELESS_CAST")
internal fun SavedLibraryItem.completeOrNull(): SavedLibraryItem? {
    if ((id as String?).isNullOrBlank() || (type as String?).isNullOrBlank()) return null
    if ((name as String?) != null && (posterShape as PosterShape?) != null && (genres as List<String>?) != null) return this
    return copy(
        name = (name as String?).orEmpty(),
        posterShape = (posterShape as PosterShape?) ?: PosterShape.POSTER,
        genres = (genres as List<String>?).orEmpty()
    )
}
