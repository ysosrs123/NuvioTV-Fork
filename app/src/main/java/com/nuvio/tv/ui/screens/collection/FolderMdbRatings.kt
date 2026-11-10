package com.nuvio.tv.ui.screens.collection

import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** The items of the rows near focus that still need MDBList ratings, once each. */
internal fun mdbRatingsToFetch(items: List<MetaPreview>, noRating: Set<String>): List<MetaPreview> =
    items.asSequence()
        .filter { it.mdbListRatings == null }
        .filterNot { it.id in noRating || it.id.startsWith("__placeholder") }
        .distinctBy { it.id }
        .toList()

/**
 * Looks up [items] together. Only a title with no rating is remembered in [noRating]: a rated title has to stay
 * fetchable because a fresh catalog row replaces the rated one, and a failed lookup may work next time.
 */
internal suspend fun <T : Any> fetchMdbRatings(
    items: List<MetaPreview>,
    noRating: MutableSet<String>,
    lookup: suspend (MetaPreview) -> T?,
    onRated: (MetaPreview, T) -> Unit
) {
    coroutineScope {
        items.map { item ->
            async(Dispatchers.IO) {
                try {
                    val result = lookup(item)
                    if (result != null) onRated(item, result) else noRating.add(item.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }.awaitAll()
    }
}

/** Every copy of [itemId] in [items] transformed, or null when nothing changed. A row can list the same title twice. */
internal fun replaceEveryCopy(
    items: List<MetaPreview>,
    itemId: String,
    transform: (MetaPreview) -> MetaPreview
): List<MetaPreview>? {
    var changed = false
    val updated = items.map { item ->
        if (item.id != itemId) return@map item
        val merged = transform(item)
        if (merged != item) changed = true
        merged
    }
    return if (changed) updated else null
}
