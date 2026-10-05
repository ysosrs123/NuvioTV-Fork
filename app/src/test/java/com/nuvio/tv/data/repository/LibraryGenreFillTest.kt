package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbPosterArt
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun TestScope.genreFill(lookup: LibraryGenreLookup, store: LibraryGenreStore, language: String = "en") =
        LibraryGenreFill(lookup, store, flowOf(language), StandardTestDispatcher(testScheduler), now = { testScheduler.currentTime })

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
    fun `simkl and mdblist entries get tmdb genre names, mdblist slugs included`() = runTest {
        val lookup = CountingLookup()
        val entries = listOf(
            entry("tmdb:1", tmdb = 1),
            entry("tt2", provider = "simkl", imdb = "tt2", type = "series"),
            entry("tmdb:3", tmdb = 3, genres = listOf("crime", "science-fiction")),
            entry("kitsu:6", provider = "simkl")
        )
        val result = genreFill(lookup, FakeStore()).fill(flowOf(entries)).last()
        assertEquals(listOf("Drama", "Crime"), result[0].genres)
        assertEquals(listOf("Drama", "Crime"), result[1].genres)
        assertEquals(listOf("Drama", "Crime"), result[2].genres)
        assertEquals(entries[3], result[3])
        assertEquals(
            setOf(LibraryGenreTarget("movie", 1, null), LibraryGenreTarget("series", null, "tt2"), LibraryGenreTarget("movie", 3, null)),
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
    fun `provider slugs are shown title cased when tmdb cannot help`() = runTest {
        val lookup = CountingLookup { target -> if (target.tmdbId == 1) null else emptyList() }
        val entries = listOf(
            entry("tmdb:1", tmdb = 1, genres = listOf("science-fiction", "drama")),
            entry("tmdb:2", tmdb = 2, genres = listOf("tv_movie")),
            entry("mdblist:9", genres = listOf("war", "Science Fiction", "science-fiction"))
        )
        val emissions = genreFill(lookup, FakeStore()).fill(flowOf(entries)).toList()
        assertEquals(listOf("Science Fiction", "Drama"), emissions.first()[0].genres)
        val result = emissions.last()
        assertEquals(listOf("Science Fiction", "Drama"), result[0].genres)
        assertEquals(listOf("Tv Movie"), result[1].genres)
        assertEquals(listOf("War", "Science Fiction"), result[2].genres)
        assertEquals(2, lookup.calls.size)
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

        val first = genreFill(lookup, store)
        assertEquals(listOf("Drama", "Crime"), first.fill(flowOf(entries)).last()[1].genres)
        val emissions = genreFill(lookup, FakeStore(store.entries)).fill(flowOf(entries)).toList()
        assertEquals(2, lookup.calls.size)
        assertEquals(listOf("Drama", "Crime"), emissions.last()[0].genres)
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
    fun `no more than four lookups run at once`() = runTest {
        val active = AtomicInteger()
        var peak = 0
        val lookup = CountingLookup {
            peak = maxOf(peak, active.incrementAndGet())
            delay(100)
            active.decrementAndGet()
            listOf("Drama")
        }
        val entries = (1..30).map { entry("tmdb:$it", tmdb = it) }
        val result = genreFill(lookup, FakeStore()).fill(flowOf(entries)).last()
        assertEquals(LibraryGenreFill.MAX_CONCURRENT_LOOKUPS, peak)
        assertEquals(30, lookup.calls.size)
        assertTrue(result.all { it.genres == listOf("Drama") })
    }

    @Test
    fun `failed lookups leave genres empty and are not retried`() = runTest {
        val lookup = CountingLookup { null }
        val store = FakeStore()
        val instance = genreFill(lookup, store)
        val entries = listOf(entry("tmdb:1", tmdb = 1), entry("tmdb:2", tmdb = 2))
        assertTrue(instance.fill(flowOf(entries)).last().all { it.genres.isEmpty() })
        instance.fill(flowOf(entries)).last()
        assertEquals(2, lookup.calls.size)
        assertTrue(store.entries.isEmpty())
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
    fun `tmdb lookup maps imdb ids and uses the app language`() = runTest {
        val metadata = mockk<TmdbMetadataService>()
        val ids = mockk<TmdbService>()
        coEvery { ids.imdbToTmdb("tt9", "series") } returns 55
        coEvery { metadata.fetchPosterArt("55", ContentType.SERIES, "de") } returns TmdbPosterArt(null, null, null, listOf("Drama"))
        coEvery { metadata.fetchPosterArt("7", ContentType.MOVIE, "de") } returns null
        val lookup = TmdbLibraryGenreLookup(metadata, ids)
        assertEquals(listOf("Drama"), lookup.genres(LibraryGenreTarget("series", null, "tt9"), "de"))
        assertEquals(null, lookup.genres(LibraryGenreTarget("movie", 7, "tt7"), "de"))
        coVerify(exactly = 0) { ids.imdbToTmdb("tt7", any()) }
    }

    @Test
    fun `file store keeps genres across restarts`() = runTest {
        val directory = Files.createTempDirectory("library-genres").toFile()
        try {
            val store = FileLibraryGenreStore(directory, backgroundScope)
            store.load()
            store.put("en|movie:tmdb:1", listOf("Drama"))
            store.save()
            val reopened = FileLibraryGenreStore(directory, backgroundScope)
            reopened.load()
            assertEquals(listOf("Drama"), reopened.get("en|movie:tmdb:1"))
            assertEquals(null, reopened.get("en|movie:tmdb:2"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
