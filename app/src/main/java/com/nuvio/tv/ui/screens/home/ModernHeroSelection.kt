package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest

/** The displayed hero owns one row/item identity independently of moving poster focus. */
internal data class ModernHeroSelection(val rowKey: String, val itemKey: String)

internal fun HeroCarouselRow.heroSelectionAt(index: Int): ModernHeroSelection? =
    items.getOrNull(index)?.let { ModernHeroSelection(key, it.key) }

internal fun resolveModernHeroItem(
    rows: Map<String, HeroCarouselRow>,
    selection: ModernHeroSelection?
): ModernCarouselItem? = selection?.let { selected ->
    rows[selected.rowKey]?.items?.firstOrNull { it.key == selected.itemKey }
}

internal data class ModernHeroFocus(val selection: ModernHeroSelection?, val delayMs: Long)

/** A newer focus cancels both the delay and any wait for the row to finish scrolling. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<ModernHeroFocus>.settledModernHeroSelections(
    verticalScrolling: Flow<Boolean>
): Flow<ModernHeroSelection> = transformLatest { focus ->
    val selection = focus.selection ?: return@transformLatest
    delay(focus.delayMs)
    verticalScrolling.first { !it }
    emit(selection)
}.buffer(0)

/** The cards focus would land on one row up and one row down, so their art is on disk before the move. */
internal fun adjacentRowPreviews(
    rows: List<HeroCarouselRow>,
    rowKey: String,
    focusedIndexByRow: Map<String, Int>
): List<MetaPreview> {
    val index = rows.indexOfFirst { it.key == rowKey }
    if (index < 0) return emptyList()
    return listOfNotNull(rows.getOrNull(index - 1), rows.getOrNull(index + 1)).mapNotNull { row ->
        val items = row.items.list
        if (items.isEmpty()) null
        else items[(focusedIndexByRow[row.key] ?: 0).coerceIn(0, items.lastIndex)].metaPreview
    }
}
