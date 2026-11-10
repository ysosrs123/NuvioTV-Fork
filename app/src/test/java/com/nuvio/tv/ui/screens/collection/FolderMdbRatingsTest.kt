package com.nuvio.tv.ui.screens.collection

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MDBListRatings
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderMdbRatingsTest {

    private val rated = MDBListRatings(imdb = 7.5)

    private fun show(id: String, ratings: MDBListRatings? = null) = MetaPreview(
        id = id,
        type = ContentType.SERIES,
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        mdbListRatings = ratings
    )

    @Test
    fun aTitleListedTwiceIsFetchedOnce() {
        val items = listOf(show("tt1"), show("tt2"), show("tt1"))
        assertEquals(listOf("tt1", "tt2"), mdbRatingsToFetch(items, emptySet()).map { it.id })
    }

    @Test
    fun noRatingTitlesRatedTitlesAndPlaceholdersAreSkipped() {
        val items = listOf(show("tt1"), show("tt2", rated), show("__placeholder_x_0"), show("tt3"))
        assertEquals(listOf("tt3"), mdbRatingsToFetch(items, setOf("tt1")).map { it.id })
    }

    private fun round(
        row: List<MetaPreview>,
        noRating: MutableSet<String>,
        lookup: (MetaPreview) -> MDBListRatings? = { rated }
    ): List<MetaPreview> = runBlocking {
        val current = java.util.concurrent.atomic.AtomicReference(row)
        fetchMdbRatings(mdbRatingsToFetch(row, noRating), noRating, { lookup(it) }, { item, ratings ->
            current.updateAndGet { items ->
                replaceEveryCopy(items, item.id) { it.copy(mdbListRatings = ratings) } ?: items
            }
        })
        current.get()
    }

    @Test
    fun aRowWithATitleTwiceSettlesAfterOneRound() {
        val noRating = mutableSetOf<String>()
        val row = round(listOf(show("tt1"), show("tt2"), show("tt1")), noRating)
        assertEquals(listOf(rated, rated, rated), row.map { it.mdbListRatings })
        assertEquals(emptyList<MetaPreview>(), mdbRatingsToFetch(row, noRating))
    }

    @Test
    fun aFreshRowReplacingARatedOneIsRatedAgain() {
        val noRating = mutableSetOf<String>()
        round(listOf(show("tt1"), show("tt2")), noRating)
        val fresh = listOf(show("tt1"), show("tt2"))
        assertEquals(listOf("tt1", "tt2"), mdbRatingsToFetch(fresh, noRating).map { it.id })
        assertEquals(listOf(rated, rated), round(fresh, noRating).map { it.mdbListRatings })
    }

    @Test
    fun aTitleWithNoRatingIsNotAskedForAgain() {
        val noRating = mutableSetOf<String>()
        val row = round(listOf(show("tt1"), show("tt2")), noRating) { if (it.id == "tt1") null else rated }
        assertEquals(setOf("tt1"), noRating)
        assertEquals(emptyList<MetaPreview>(), mdbRatingsToFetch(row, noRating))
    }

    @Test
    fun aFailedLookupCanBeTriedAgain() {
        val noRating = mutableSetOf<String>()
        val row = round(listOf(show("tt1")), noRating) { throw IllegalStateException("offline") }
        assertTrue(noRating.isEmpty())
        assertEquals(listOf("tt1"), mdbRatingsToFetch(row, noRating).map { it.id })
    }

    @Test
    fun noChangeGivesNull() {
        val row = listOf(show("tt1", rated), show("tt2"))
        assertNull(replaceEveryCopy(row, "tt1") { it.copy(mdbListRatings = rated) })
        assertNull(replaceEveryCopy(row, "tt9") { it.copy(mdbListRatings = rated) })
    }
}
