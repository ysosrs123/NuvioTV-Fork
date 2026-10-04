package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.ui.util.asStable
import org.junit.Assert.*
import org.junit.Test

class ModernHomeInitialFocusTest {
    private fun row(key: String, globalIndex: Int, vararg keys: String) = HeroCarouselRow(
        key, key, globalIndex, keys.map { itemKey ->
            ModernCarouselItem(
                itemKey, itemKey, null, null,
                HeroPreview(itemKey, null, null, null, yearText = null, imdbText = null,
                    genres = emptyList<String>().asStable(), poster = null, backdrop = null, imageUrl = null),
                ModernPayload.Catalog(itemKey, itemKey, "movie", "https://example.test", itemKey, null, "movie")
            )
        }.asStable()
    )

    @Test fun savedIdentityRestoresTheCorrectHeroEvenAfterRowAndItemReordering() {
        val rows = listOf(row("first", 0, "a"), row("saved", 1, "new", "b", "c"))
        val result = resolveModernHomeInitialFocus(rows, HomeScreenFocusState(
            focusedRowKey = "saved", focusedItemKeyByRow = mapOf("saved" to "c"),
            focusedItemIndex = 0, hasSavedFocus = true
        ))!!
        assertEquals("saved", result.first.key)
        assertEquals(2, result.second)
        assertEquals("c", result.first.items[result.second].heroPreview.title)
    }

    @Test fun legacyContinueWatchingAndIndicesAreRestoredAndClamped() {
        val rows = listOf(row("first", 0, "a"), row("continue_watching", -1, "b", "c"))
        val legacy = HomeScreenFocusState(focusedRowIndex = -1, focusedItemIndex = 99, hasSavedFocus = true)
        assertEquals(rows[1] to 1, resolveModernHomeInitialFocus(rows, legacy))
        assertEquals(rows[0] to 0, resolveModernHomeInitialFocus(rows, legacy.copy(focusedRowIndex = 0, focusedItemIndex = -1)))
    }

    @Test fun missingSavedContentAndLateDataHaveBoundedFallbacks() {
        val rows = listOf(row("first", 0, "a"))
        val saved = HomeScreenFocusState(focusedRowKey = "deleted", focusedItemKeyByRow = mapOf("first" to "deleted"), hasSavedFocus = true)
        assertEquals(rows[0] to 0, resolveModernHomeInitialFocus(rows, saved))
        assertNull(resolveModernHomeInitialFocus(emptyList(), saved))
        assertEquals(rows[0] to 0, resolveModernHomeInitialFocus(rows, saved.copy(hasSavedFocus = false)))
    }
}
