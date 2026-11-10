package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeAddonLookupIdTest {
    private fun preview(id: String, imdbId: String? = null) = MetaPreview(
        id = id,
        type = ContentType.SERIES,
        rawType = "series",
        name = "Show",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        imdbId = imdbId
    )

    private suspend fun lookup(item: MetaPreview, claimed: Boolean = false, cached: String? = null) =
        addonLookupId(item, { claimed }, { _, _ -> cached })

    @Test
    fun `other ids go to addons as they are`() = runTest {
        assertEquals("tt1", lookup(preview("tt1")))
    }

    @Test
    fun `a tmdb id nobody claims and nothing cached is not asked for`() = runTest {
        assertNull(lookup(preview("tmdb:3452")))
    }

    @Test
    fun `a tmdb id uses the imdb id once it is known`() = runTest {
        assertEquals("tt2", lookup(preview("tmdb:3452", imdbId = "tt2")))
        assertEquals("tt3", lookup(preview("tmdb:3452"), cached = "tt3"))
    }

    @Test
    fun `an addon that claims tmdb ids is still asked`() = runTest {
        assertEquals("tmdb:3452", lookup(preview("tmdb:3452"), claimed = true))
    }
}
