package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.LibraryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTransferTest {

    private fun entry(
        id: String,
        type: String = "movie",
        name: String = "Title",
        imdbId: String? = null,
        tmdbId: Int? = null,
        traktId: Int? = null,
        releaseInfo: String? = "1994"
    ) = LibraryEntry(
        id = id,
        type = type,
        name = name,
        poster = null,
        background = null,
        logo = null,
        description = null,
        releaseInfo = releaseInfo,
        imdbRating = null,
        genres = emptyList(),
        addonBaseUrl = null,
        imdbId = imdbId,
        tmdbId = tmdbId,
        traktId = traktId
    )

    @Test
    fun `toTransferInput preserves identity and parses year`() {
        val input = entry(id = "tt0111161", name = "Shawshank", imdbId = "tt0111161", tmdbId = 278, releaseInfo = "1994")
            .toTransferInput()
        assertEquals("tt0111161", input.itemId)
        assertEquals("movie", input.itemType)
        assertEquals("Shawshank", input.title)
        assertEquals("tt0111161", input.imdbId)
        assertEquals(278, input.tmdbId)
        assertEquals(1994, input.year)
    }

    @Test
    fun `plan skips entries already in the destination`() {
        val source = listOf(
            entry(id = "tt0111161", imdbId = "tt0111161"),
            entry(id = "tt0068646", imdbId = "tt0068646")
        )
        val destKeys = setOf("tt0111161") // Shawshank already there
        val plan = computeTransferPlan(source, destKeys)
        assertEquals(1, plan.willWrite)
        assertEquals(1, plan.alreadyPresent)
        assertEquals("tt0068646", plan.toWrite.single().itemId)
    }

    @Test
    fun `plan dedups the source by canonical id`() {
        val source = listOf(
            entry(id = "tt0111161", imdbId = "tt0111161"),
            entry(id = "tt0111161"), // same title, id only in itemId
            entry(id = "tmdb:278", tmdbId = 278)
        )
        val plan = computeTransferPlan(source, emptySet())
        assertEquals(2, plan.willWrite) // tt0111161 and tmdb:278 are distinct
        assertEquals(1, plan.duplicates)
    }

    @Test
    fun `plan counts entries with no resolvable id as unmatched`() {
        val source = listOf(
            entry(id = "kitsu:42", type = "series"),
            entry(id = "tt0111161", imdbId = "tt0111161")
        )
        val plan = computeTransferPlan(source, emptySet())
        assertEquals(1, plan.willWrite)
        assertEquals(1, plan.unmatched)
        assertEquals(2, plan.sourceTotal)
    }

    @Test
    fun `hasResolvableId reflects imdb tmdb availability`() {
        assertTrue(entry(id = "tt0111161", imdbId = "tt0111161").hasResolvableId())
        assertTrue(entry(id = "tmdb:278").hasResolvableId())
        assertTrue(entry(id = "anything", tmdbId = 278).hasResolvableId())
        assertFalse(entry(id = "kitsu:42").hasResolvableId())
        assertFalse(entry(id = "trakt:100").hasResolvableId()) // trakt-only: not MDBList/id-writable
    }

    @Test
    fun `empty source yields an empty plan`() {
        val plan = computeTransferPlan(emptyList(), setOf("tt0111161"))
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.sourceTotal)
    }
}
