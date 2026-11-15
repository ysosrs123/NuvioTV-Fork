package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.util.asStable
import org.junit.Assert.assertEquals
import org.junit.Test

class AdjacentRowPreviewsTest {
    private fun preview(id: String) = MetaPreview(
        id = id, type = ContentType.MOVIE, name = id, poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = null,
        genres = emptyList()
    )

    private fun item(key: String, meta: Boolean = true) = ModernCarouselItem(
        key, key, null, null,
        HeroPreview(key, null, null, null, yearText = null, imdbText = null,
            genres = emptyList<String>().asStable(), poster = null, backdrop = null, imageUrl = null),
        ModernPayload.Catalog(key, key, "movie", "https://example.test", key, null, "movie"),
        metaPreview = if (meta) preview(key) else null
    )

    private fun row(key: String, vararg items: ModernCarouselItem) =
        HeroCarouselRow(key, key, 0, items.toList().asStable())

    private val rows = listOf(
        row("top", item("t0"), item("t1"), item("t2")),
        row("middle", item("m0"), item("m1")),
        row("bottom", item("b0"), item("b1"))
    )

    @Test fun picksTheCardFocusLandsOnAboveAndBelow() {
        val ids = adjacentRowPreviews(rows, "middle", mapOf("top" to 2, "bottom" to 1)).map { it.id }
        assertEquals(listOf("t2", "b1"), ids)
    }

    @Test fun rowWithNoSavedFocusUsesItsFirstCardAndEdgesHaveOneNeighbour() {
        assertEquals(listOf("m0"), adjacentRowPreviews(rows, "top", emptyMap()).map { it.id })
        assertEquals(listOf("m1"), adjacentRowPreviews(rows, "bottom", mapOf("middle" to 1)).map { it.id })
    }

    @Test fun staleIndexIsClampedAndCardsWithoutDetailsOrUnknownRowsGiveNothing() {
        assertEquals(listOf("t2", "b1"), adjacentRowPreviews(rows, "middle", mapOf("top" to 9, "bottom" to 9)).map { it.id })
        val bare = listOf(row("a", item("a0", meta = false)), row("b", item("b0")), row("c"))
        assertEquals(emptyList<String>(), adjacentRowPreviews(bare, "b", emptyMap()).map { it.id })
        assertEquals(emptyList<String>(), adjacentRowPreviews(rows, "gone", emptyMap()).map { it.id })
    }
}
