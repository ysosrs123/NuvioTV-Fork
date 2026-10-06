package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbGenre
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryGenreFillTest {
    private class FakeStore(initial: Map<String, List<String>> = emptyMap()) : LibraryGenreStore {
        val entries = initial.toMutableMap()
        override var isLoaded = false
        override suspend fun load() {
            isLoaded = true
        }
        override fun get(key: String) = entries[key]
        override fun put(key: String, genres: List<String>) {
            entries[key] = genres
        }
    }

    private class CountingLookup(
        private val answer: suspend (LibraryGenreTarget) -> List<String>? = { listOf("Drama", "Crime") }
    ) : LibraryGenreLookup {
        val calls = mutableListOf<Pair<LibraryGenreTarget, String>>()
        override suspend fun genres(target: LibraryGenreTarget, language: String): List<String>? {
            calls += target to language
            return answer(target)
        }
    }

    private fun TestScope.genreFill(
        lookup: LibraryGenreLookup,
        store: LibraryGenreStore,
        language: String = "en",
        lookupsEnabled: Boolean = true,
        visible: Boolean = true
    ) = LibraryGenreFill(
        lookup, store, flowOf(LibraryGenreFillSettings(language, lookupsEnabled)),
        StandardTestDispatcher(testScheduler), now = { testScheduler.currentTime }
    ).also { it.setScreenVisible(visible) }

    private fun entry(
        id: String,
        provider: String? = "mdblist",
        tmdb: Int? = null,
        imdb: String? = null,
        genres: List<String> = emptyList(),
        type: String = "movie"
    ) = LibraryEntry(
        id = id, type = type, name = id, poster = null, background = null, logo = null, description = null,
        releaseInfo = null, imdbRating = null, genres = genres, addonBaseUrl = null, imdbId = imdb, tmdbId = tmdb,
        trackingProviderId = provider
    )

    @Test
    fun `only simkl and mdblist titles without genres are looked up, provider slugs are shown as words`() = runTest {
        val lookup = CountingLookup()
        val entries = listOf(
            entry("tmdb:1", tmdb = 1),
            entry("tt2", provider = "simkl", imdb = "tt2", type = "series"),
            entry("tmdb:3", tmdb = 3, genres = listOf("science-fiction", "anime", "Drama")),
            entry("kitsu:6", provider = "simkl")
        )
        val result = genreFill(lookup, FakeStore()).fill(flowOf(entries)).last()
        assertEquals(listOf("Drama", "Crime"), result[0].genres)
        assertEquals(listOf("Drama", "Crime"), result[1].genres)
        assertEquals(listOf("Science Fiction", "Anime", "Drama"), result[2].genres)
        assertEquals(entries[3], result[3])
        assertEquals(
            setOf(LibraryGenreTarget("movie", 1, null), LibraryGenreTarget("series", null, "tt2")),
            lookup.calls.map { it.first }.toSet()
        )
    }

    @Test
    fun `trakt and local entries are left exactly as they are`() = runTest {
        val lookup = CountingLookup()
        val entries = listOf(
            entry("tt4", provider = "trakt", imdb = "tt4", tmdb = 4, genres = listOf("science-fiction")),
            entry("tt5", provider = "trakt", tmdb = 5),
            entry("local", provider = null, tmdb = 6, genres = listOf("drama"))
        )
        val emissions = genreFill(lookup, FakeStore()).fill(flowOf(entries)).toList()
        assertEquals(listOf(entries), emissions)
        assertEquals(0, lookup.calls.size)
    }

    @Test
    fun `slugs become display names`() {
        assertEquals("Science Fiction", LibraryGenreFill.genreDisplayName("science-fiction"))
        assertEquals("Drama", LibraryGenreFill.genreDisplayName(" drama "))
        assertEquals("Tv Movie", LibraryGenreFill.genreDisplayName("tv_movie"))
        assertEquals("Sci-Fi & Fantasy", LibraryGenreFill.genreDisplayName("Sci-Fi & Fantasy"))
    }

    @Test
    fun `the first list is emitted before any lookup finishes`() = runTest {
        val lookup = CountingLookup { delay(5_000); listOf("Drama") }
        val emissions = genreFill(lookup, FakeStore()).fill(flowOf(listOf(entry("tmdb:1", tmdb = 1)))).toList()
        assertEquals(emptyList<String>(), emissions.first().single().genres)
        assertEquals(listOf("Drama"), emissions.last().single().genres)
    }

    @Test
    fun `cached genres avoid a second lookup, also after a restart`() = runTest {
        val lookup = CountingLookup()
        val store = FakeStore()
        val entries = listOf(entry("tmdb:1", tmdb = 1), entry("tmdb:2", tmdb = 2, type = "series"))
        genreFill(lookup, store).fill(flowOf(entries)).last()
        assertEquals(2, lookup.calls.size)

        assertEquals(listOf("Drama", "Crime"), genreFill(lookup, store).fill(flowOf(entries)).last()[1].genres)
        val emissions = genreFill(lookup, FakeStore(store.entries)).fill(flowOf(entries)).toList()
        assertEquals(2, lookup.calls.size)
        assertEquals(listOf("Drama", "Crime"), emissions.last()[0].genres)
    }

    @Test
    fun `titles tmdb has no genres for are remembered, failures are not`() = runTest {
        val lookup = CountingLookup { target -> if (target.tmdbId == 1) emptyList() else null }
        val store = FakeStore()
        val entries = listOf(entry("tmdb:1", tmdb = 1), entry("tmdb:2", tmdb = 2))
        val instance = genreFill(lookup, store)
        assertTrue(instance.fill(flowOf(entries)).last().all { it.genres.isEmpty() })
        assertEquals(mapOf("en|movie:tmdb:1" to emptyList<String>()), store.entries)
        instance.fill(flowOf(entries)).last()
        assertEquals(2, lookup.calls.size)

        genreFill(lookup, FakeStore(store.entries)).fill(flowOf(entries)).last()
        assertEquals(listOf(1, 2, 2), lookup.calls.map { it.first.tmdbId })
    }

    @Test
    fun `genres are kept per language`() = runTest {
        val store = FakeStore(mapOf("de|movie:tmdb:1" to listOf("Krimi")))
        val lookup = CountingLookup()
        assertEquals(listOf("Krimi"), genreFill(lookup, store, "de").fill(flowOf(listOf(entry("tmdb:1", tmdb = 1)))).last().single().genres)
        assertEquals(0, lookup.calls.size)
        genreFill(lookup, store, "en").fill(flowOf(listOf(entry("tmdb:1", tmdb = 1)))).last()
        assertEquals(listOf(LibraryGenreTarget("movie", 1, null) to "en"), lookup.calls)
    }

    @Test
    fun `no more than four lookups run at once and the list is rebuilt at most every two seconds`() = runTest {
        val active = AtomicInteger()
        var peak = 0
        val lookup = CountingLookup {
            peak = maxOf(peak, active.incrementAndGet())
            delay(100)
            active.decrementAndGet()
            listOf("Drama")
        }
        val entries = (1..120).map { entry("tmdb:$it", tmdb = it) }
        val emissions = genreFill(lookup, FakeStore()).fill(flowOf(entries)).toList()
        assertEquals(LibraryGenreFill.MAX_CONCURRENT_LOOKUPS, peak)
        assertEquals(120, lookup.calls.size)
        assertEquals(3, emissions.size)
        assertTrue(emissions.last().all { it.genres == listOf("Drama") })
    }

    @Test
    fun `a pass that only fails stops early and pauses`() = runTest {
        val lookup = CountingLookup { null }
        val instance = genreFill(lookup, FakeStore())
        val entries = (1..100).map { entry("tmdb:$it", tmdb = it) }
        instance.fill(flowOf(entries)).last()
        assertEquals(LibraryGenreFill.EMIT_EVERY, lookup.calls.size)
        instance.fill(flowOf(entries)).last()
        assertEquals(LibraryGenreFill.EMIT_EVERY, lookup.calls.size)
    }

    @Test
    fun `lookups wait while the library is hidden and carry on when it is shown again`() = runTest {
        val lookup = CountingLookup { delay(100); listOf("Drama") }
        val instance = genreFill(lookup, FakeStore(), visible = false)
        val entries = (1..8).map { entry("tmdb:$it", tmdb = it) }
        val result = async { instance.fill(flowOf(entries)).last() }
        advanceTimeBy(60_000)
        assertEquals(0, lookup.calls.size)

        instance.setScreenVisible(true)
        advanceTimeBy(LibraryGenreFill.START_DELAY_MS + 50)
        assertEquals(4, lookup.calls.size)
        instance.setScreenVisible(false)
        advanceTimeBy(60_000)
        assertEquals(4, lookup.calls.size)
        assertFalse(result.isCompleted)

        instance.setScreenVisible(true)
        runCurrent()
        assertTrue(result.await().all { it.genres == listOf("Drama") })
        assertEquals(8, lookup.calls.size)
    }

    @Test
    fun `with tmdb switched off nothing is looked up and provider genres are still tidied`() = runTest {
        val lookup = CountingLookup()
        val store = FakeStore(mapOf("en|movie:tmdb:1" to listOf("Drama")))
        val entries = listOf(entry("tmdb:1", tmdb = 1), entry("tmdb:2", tmdb = 2, genres = listOf("science-fiction")))
        val result = genreFill(lookup, store, lookupsEnabled = false).fill(flowOf(entries)).last()
        assertEquals(emptyList<String>(), result[0].genres)
        assertEquals(listOf("Science Fiction"), result[1].genres)
        assertEquals(0, lookup.calls.size)
    }

    @Test
    fun `lookups follow the tmdb switch`() {
        assertEquals(LibraryGenreFillSettings("de", false), LibraryGenreFill.fillSettings(TmdbSettings(enabled = false, language = "de")))
        assertEquals(LibraryGenreFillSettings("de", true), LibraryGenreFill.fillSettings(TmdbSettings(enabled = true, language = "de")))
    }

    @Test
    fun `tmdb lookup maps imdb ids and uses the app language`() = runTest {
        val metadata = mockk<TmdbMetadataService>()
        val ids = mockk<TmdbService>()
        coEvery { ids.imdbToTmdb("tt9", "series") } returns 55
        coEvery { metadata.fetchGenres(55, ContentType.SERIES, "de") } returns listOf("Drama")
        coEvery { metadata.fetchGenres(7, ContentType.MOVIE, "de") } returns null
        val lookup = TmdbLibraryGenreLookup(metadata, ids)
        assertEquals(listOf("Drama"), lookup.genres(LibraryGenreTarget("series", null, "tt9"), "de"))
        assertNull(lookup.genres(LibraryGenreTarget("movie", 7, "tt7"), "de"))
        coVerify(exactly = 0) { ids.imdbToTmdb("tt7", any()) }
    }

    @Test
    fun `tmdb genres call tells no genres apart from a failed request`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getMovieDetails(1, any(), "de") } returns
            Response.success(TmdbDetailsResponse(id = 1, genres = listOf(TmdbGenre(18, "Drama"), TmdbGenre(80, " "))))
        coEvery { api.getMovieDetails(2, any(), any()) } returns Response.error(404, "".toResponseBody(null))
        coEvery { api.getTvDetails(3, any(), any()) } returns Response.error(503, "".toResponseBody(null))
        val service = TmdbMetadataService(api, StandardTestDispatcher(testScheduler))
        assertEquals(listOf("Drama"), service.fetchGenres(1, ContentType.MOVIE, "de"))
        assertEquals(emptyList<String>(), service.fetchGenres(2, ContentType.MOVIE, "de"))
        assertNull(service.fetchGenres(3, ContentType.SERIES, "de"))
    }

    @Test
    fun `file store keeps genres across restarts and asks again about empty ones after a week`() = runTest {
        val directory = Files.createTempDirectory("library-genres").toFile()
        var time = 1_000L
        try {
            val store = FileLibraryGenreStore(directory, backgroundScope, now = { time })
            store.load()
            store.put("en|movie:tmdb:1", listOf("Drama"))
            store.put("en|movie:tmdb:2", emptyList())
            store.save()
            val reopened = FileLibraryGenreStore(directory, backgroundScope, now = { time })
            reopened.load()
            assertEquals(listOf("Drama"), reopened.get("en|movie:tmdb:1"))
            assertEquals(emptyList<String>(), reopened.get("en|movie:tmdb:2"))
            assertNull(reopened.get("en|movie:tmdb:3"))
            time += 8L * 24L * 60L * 60L * 1000L
            assertEquals(listOf("Drama"), reopened.get("en|movie:tmdb:1"))
            assertNull(reopened.get("en|movie:tmdb:2"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
