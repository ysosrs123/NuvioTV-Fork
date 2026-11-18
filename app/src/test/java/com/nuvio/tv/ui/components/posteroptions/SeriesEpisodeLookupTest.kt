package com.nuvio.tv.ui.components.posteroptions

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesEpisodeLookupTest {

    @Test
    fun `a show the primary add-on knows needs no other lookup`() = runBlocking {
        val repo = mockk<MetaRepository> {
            every { getMetaFromPrimaryAddon("series", TMDB_ID) } returns flowOf(NetworkResult.Loading, NetworkResult.Success(meta(TMDB_ID, 3)))
        }
        val episodes = lookUp(repo)
        assertEquals(3, episodes.size)
        verify(exactly = 0) { repo.getMetaFromAllAddons(any(), any(), any()) }
    }

    @Test
    fun `a tmdb id the primary add-on does not know is asked of the other add-ons`() = runBlocking {
        val repo = mockk<MetaRepository> {
            every { getMetaFromPrimaryAddon("series", TMDB_ID) } returns flowOf(NetworkResult.Error("not found"))
            every { getMetaFromAllAddons("series", TMDB_ID, ADDON) } returns flowOf(NetworkResult.Success(meta(TMDB_ID, 6)))
        }
        assertEquals(6, lookUp(repo).size)
    }

    @Test
    fun `when no add-on knows the tmdb id the primary is asked under the IMDb id`() = runBlocking {
        val repo = mockk<MetaRepository> {
            every { getMetaFromPrimaryAddon("series", TMDB_ID) } returns flowOf(NetworkResult.Success(meta(TMDB_ID, 0)))
            every { getMetaFromAllAddons("series", TMDB_ID, ADDON) } returns flowOf(NetworkResult.Error("not found"))
            every { getMetaFromPrimaryAddon("series", IMDB_ID) } returns flowOf(NetworkResult.Success(meta(IMDB_ID, 7)))
        }
        assertEquals(7, lookUp(repo, resolveTo = IMDB_ID).size)
    }

    @Test
    fun `nothing anywhere gives no episodes`() = runBlocking {
        val repo = mockk<MetaRepository> {
            every { getMetaFromPrimaryAddon(any(), any()) } returns flowOf(NetworkResult.Error("not found"))
            every { getMetaFromAllAddons(any(), any(), any()) } returns flowOf(NetworkResult.Error("not found"))
        }
        assertTrue(lookUp(repo, resolveTo = IMDB_ID).isEmpty())
        assertTrue(lookUp(repo).isEmpty())
    }

    private suspend fun lookUp(repo: MetaRepository, resolveTo: String? = null): List<Video> =
        lookUpSeriesEpisodes(
            metaRepository = repo,
            item = preview(TMDB_ID),
            type = "series",
            addonBaseUrl = ADDON,
            resolve = { item -> if (resolveTo == null) item else item.copy(id = resolveTo) },
            pick = { it.watchableEpisodes() }
        )

    private fun preview(id: String) = MetaPreview(
        id = id,
        type = ContentType.SERIES,
        rawType = "series",
        name = "Griselda",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "2024",
        imdbRating = null,
        genres = emptyList()
    )

    private fun meta(id: String, episodes: Int) = Meta(
        id = id,
        type = ContentType.SERIES,
        name = "Griselda",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "2024",
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        videos = (1..episodes).map { e ->
            Video(id = "$id:1:$e", title = "Episode $e", released = "2024-01-25T00:00:00.000Z", thumbnail = null,
                season = 1, episode = e, overview = null)
        },
        country = null,
        awards = null,
        language = null,
        links = emptyList()
    )

    private companion object {
        const val ADDON = "https://scout.example/addon"
        const val TMDB_ID = "tmdb:137893"
        const val IMDB_ID = "tt15837600"
    }
}
