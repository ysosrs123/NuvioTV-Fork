package com.nuvio.tv.ui.components.posteroptions

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingMembershipApplyResult
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PosterTitleIdentityTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `anime poster takes the id and type its details page saves under`() = runTest {
        val metaRepository = mockk<MetaRepository>(relaxed = true) {
            every { getMeta(ADDON, "anime", MAL_ID) } returns flowOf(NetworkResult.Loading, NetworkResult.Success(kitsuMeta()))
        }
        val identity = PosterTitleIdentity(metaRepository, mockk(relaxed = true)) { false }

        val saved = identity.resolve(animePoster(), ADDON)

        assertEquals(KITSU_ID, saved.id)
        assertEquals("series", saved.apiType)
    }

    @Test
    fun `with meta add-ons preferred the lookup asks all add-ons first, like the details page`() = runTest {
        val metaRepository = mockk<MetaRepository>(relaxed = true) {
            every { getMetaFromAllAddons("anime", MAL_ID, any()) } returns flowOf(NetworkResult.Success(kitsuMeta()))
        }
        val identity = PosterTitleIdentity(metaRepository, mockk(relaxed = true)) { true }

        val saved = identity.resolve(animePoster(), ADDON)

        assertEquals(KITSU_ID, saved.id)
        verify(exactly = 0) { metaRepository.getMeta(any(), any(), any()) }
    }

    @Test
    fun `a second press reuses the answer`() = runTest {
        val metaRepository = mockk<MetaRepository>(relaxed = true) {
            every { getMeta(ADDON, "anime", MAL_ID) } returns flowOf(NetworkResult.Success(kitsuMeta()))
        }
        val identity = PosterTitleIdentity(metaRepository, mockk(relaxed = true)) { false }

        identity.resolve(animePoster(), ADDON)
        val again = identity.resolve(animePoster(), ADDON)
        val savedAgain = identity.resolve(again, ADDON)

        assertEquals(KITSU_ID, savedAgain.id)
        verify(exactly = 1) { metaRepository.getMeta(any(), any(), any()) }
    }

    @Test
    fun `imdb movies and shows are left alone without a lookup`() = runTest {
        val metaRepository = mockk<MetaRepository>(relaxed = true)
        val identity = PosterTitleIdentity(metaRepository, mockk(relaxed = true)) { false }
        val movie = animePoster().copy(id = "tt1234567", type = ContentType.MOVIE, rawType = "movie")

        assertSame(movie, identity.resolve(movie, ADDON))
        verify(exactly = 0) { metaRepository.getMeta(any(), any(), any()) }
        verify(exactly = 0) { metaRepository.getMetaFromAllAddons(any(), any(), any()) }
    }

    @Test
    fun `a failed lookup keeps the poster's own id`() = runTest {
        val metaRepository = mockk<MetaRepository>(relaxed = true) {
            every { getMeta(any(), any(), any()) } returns flowOf(NetworkResult.Error("down"))
            every { getMetaFromAllAddons(any(), any(), any()) } returns flowOf(NetworkResult.Error("down"))
        }
        val identity = PosterTitleIdentity(metaRepository, mockk(relaxed = true)) { false }
        val poster = animePoster()

        assertSame(poster, identity.resolve(poster, ADDON))
    }

    @Test
    fun `tmdb ids still become imdb ids`() = runTest {
        val tmdbService = mockk<TmdbService>(relaxed = true) {
            coEvery { tmdbToImdb(4321, "tv") } returns "tt7654321"
        }
        val identity = PosterTitleIdentity(mockk(relaxed = true), tmdbService) { false }
        val poster = animePoster().copy(id = "tmdb:4321", type = ContentType.SERIES, rawType = "series")

        assertEquals("tt7654321", identity.resolve(poster, null).id)
    }

    @Test
    fun `toggle with only the old poster entry saved removes it and adds nothing`() = runTest {
        val library = LocalLibrary(TitleAlias(MAL_ID, "anime"))

        val result = library.repository.toggleDefaultMerging(input(KITSU_ID, "series"), TitleAlias(MAL_ID, "anime"))

        assertEquals(false, result.added)
        assertTrue(library.saved.isEmpty())
    }

    @Test
    fun `toggle with both entries saved removes both`() = runTest {
        val library = LocalLibrary(TitleAlias(MAL_ID, "anime"), TitleAlias(KITSU_ID, "series"))

        library.repository.toggleDefaultMerging(input(KITSU_ID, "series"), TitleAlias(MAL_ID, "anime"))

        assertTrue(library.saved.isEmpty())
    }

    @Test
    fun `toggle with nothing saved adds the title once, under its details-page id`() = runTest {
        val library = LocalLibrary()

        library.repository.toggleDefaultMerging(input(KITSU_ID, "series"), TitleAlias(MAL_ID, "anime"))

        assertEquals(setOf(TitleAlias(KITSU_ID, "series")), library.saved)
    }

    @Test
    fun `either entry counts as in the library`() = runTest {
        val library = LocalLibrary(TitleAlias(MAL_ID, "anime"))

        assertTrue(library.repository.isInLibraryOrAlias(KITSU_ID, "series", TitleAlias(MAL_ID, "anime")).first())
        assertFalse(library.repository.isInLibraryOrAlias("kitsu:1", "series", TitleAlias("mal:1", "anime")).first())
    }

    @Test
    fun `tracker libraries get the plain toggle`() = runTest {
        val library = LocalLibrary(TitleAlias(MAL_ID, "anime"), mode = LibrarySourceMode.SIMKL)

        library.repository.toggleDefaultMerging(input(KITSU_ID, "series"), TitleAlias(MAL_ID, "anime"))

        coVerify(exactly = 1) { library.repository.toggleDefault(match { it.itemId == KITSU_ID }, any()) }
        coVerify(exactly = 0) { library.repository.toggleDefault(match { it.itemId == MAL_ID }, any()) }
    }

    @Test
    fun `poster menu on an anime poster shows and clears the entry the old menu saved`() = runTest {
        val library = LocalLibrary(TitleAlias(MAL_ID, "anime"))
        val metaRepository = mockk<MetaRepository>(relaxed = true) {
            every { getMeta(ADDON, "anime", MAL_ID) } returns flowOf(NetworkResult.Success(kitsuMeta()))
        }
        val controller = PosterOptionsController(
            appContext = mockk<Context>(relaxed = true),
            libraryRepository = library.repository,
            watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true) {
                every { isWatched(any(), any(), any(), any()) } returns flowOf(false)
            },
            metaRepository = metaRepository,
            watchedSeriesStateHolder = mockk<WatchedSeriesStateHolder>(relaxed = true) {
                every { fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())
            },
            titleIdentity = PosterTitleIdentity(metaRepository, mockk(relaxed = true)) { false }
        )
        val bindScope = CoroutineScope(coroutineContext + Job())
        controller.bind(bindScope)

        controller.show(animePoster(), ADDON)
        advanceUntilIdle()
        assertEquals(KITSU_ID, controller.state.value.target?.id)
        assertTrue(controller.state.value.isInLibrary)

        controller.toggleLibrary()
        advanceUntilIdle()
        assertTrue(library.saved.isEmpty())
        assertFalse(controller.state.value.isInLibrary)

        controller.toggleLibrary()
        advanceUntilIdle()
        assertEquals(setOf(TitleAlias(KITSU_ID, "series")), library.saved)
        bindScope.cancel()
    }

    /** The local library keyed by (id, type), as LibraryPreferences keys it. */
    private class LocalLibrary(vararg initial: TitleAlias, mode: LibrarySourceMode = LibrarySourceMode.LOCAL) {
        private val state = MutableStateFlow(initial.toSet())
        val saved: Set<TitleAlias> get() = state.value
        val repository: LibraryRepository = mockk(relaxed = true) {
            every { sourceMode } returns flowOf(mode)
            every { listTabs } returns flowOf(emptyList())
            every { membershipListTabs } returns flowOf(emptyList())
            every { isInLibrary(any(), any()) } answers {
                val key = TitleAlias(firstArg(), secondArg())
                state.map { key in it }
            }
            coEvery { toggleDefault(any(), any()) } answers {
                val key = TitleAlias(firstArg<LibraryEntryInput>().itemId, firstArg<LibraryEntryInput>().itemType)
                val had = key in state.value
                state.value = if (had) state.value - key else state.value + key
                TrackingMembershipApplyResult(added = !had)
            }
        }
    }

    private fun input(id: String, type: String) = LibraryEntryInput(itemId = id, itemType = type, title = "Shingeki")

    private fun animePoster() = MetaPreview(
        id = MAL_ID,
        type = ContentType.fromString("anime"),
        rawType = "anime",
        name = "Shingeki",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "2013",
        imdbRating = null,
        genres = emptyList()
    )

    private fun kitsuMeta() = Meta(
        id = KITSU_ID,
        type = ContentType.SERIES,
        name = "Shingeki",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "2013",
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        videos = emptyList(),
        country = null,
        awards = null,
        language = null,
        links = emptyList()
    )

    private companion object {
        const val ADDON = "https://anime.example/addon"
        const val MAL_ID = "mal:16498"
        const val KITSU_ID = "kitsu:7442"
    }
}
