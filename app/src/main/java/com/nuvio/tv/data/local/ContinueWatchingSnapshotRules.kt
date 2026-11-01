package com.nuvio.tv.data.local

/** Gson leaves a missing field null even where Kotlin says it cannot be, so the checks read the fields as nullable. */
@Suppress("SENSELESS_COMPARISON", "USELESS_CAST")
internal fun CachedInProgressItem.isSavable(): Boolean =
    !(contentId as String?).isNullOrBlank() && !(contentType as String?).isNullOrBlank() &&
        (name as String?) != null && (videoId as String?) != null

@Suppress("SENSELESS_COMPARISON", "USELESS_CAST")
internal fun CachedNextUpItem.isSavable(): Boolean =
    !(contentId as String?).isNullOrBlank() && !(contentType as String?).isNullOrBlank() &&
        (name as String?) != null && (videoId as String?) != null

internal fun <T> List<T>.savableSnapshot(kind: String, isSavable: (T) -> Boolean, log: (String) -> Unit): List<T> {
    val kept = filter(isSavable)
    if (kept.size != size) log("$kind snapshot: ${size - kept.size} entries without an id not saved")
    return kept
}
