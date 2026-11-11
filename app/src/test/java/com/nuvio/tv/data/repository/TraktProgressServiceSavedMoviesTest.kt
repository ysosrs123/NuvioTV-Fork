package com.nuvio.tv.data.repository

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktWatchedMovieItemDto
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class TraktProgressServiceSavedMoviesTest {
    private val sam = TraktAuthState(accessToken = "a", refreshToken = "r", userSlug = "sam")
    private val kid = TraktAuthState(accessToken = "b", refreshToken = "s", userSlug = "kid")
    private val storage = FakeTraktWatchedMoviesStorage()
    private val currentProfile = MutableStateFlow(1)
    private val authByProfile = mutableMapOf(1 to MutableStateFlow(sam), 2 to MutableStateFlow(kid))
    private val fetches = AtomicInteger()

    @Test
    fun `at start the saved ids are there before Trakt answers`() = runBlocking {
        storage.byProfile[1] = SavedTraktWatchedMovies(account = "sam", movieIds = setOf("tt1"))
        val service = service { null }

        val ids = withTimeout(5_000) { service.observeAllWatchedMovieIds().first() }

        assertEquals(setOf("tt1"), ids)
    }

    @Test
    fun `the saved ids do not wait for a slow Trakt answer`() = runBlocking {
        storage.byProfile[1] = SavedTraktWatchedMovies(account = "sam", movieIds = setOf("tt1"))
        storage.loadDelayMs = 300
        val trakt = CompletableDeferred<Unit>()
        val service = service { trakt.await(); null }

        try {
            val ids = withTimeout(2_000) { service.observeAllWatchedMovieIds().first() }
            assertEquals(setOf("tt1"), ids)
        } finally {
            trakt.complete(Unit)
        }
    }

    @Test
    fun `the live Trakt result replaces the saved ids and is saved`() = runBlocking {
        storage.byProfile[1] = SavedTraktWatchedMovies(account = "sam", movieIds = setOf("tt1"))
        val service = service { listOf(watched("tt2")) }

        val ids = withTimeout(5_000) { service.observeAllWatchedMovieIds().first { "tt2" in it } }
        withTimeout(5_000) {
            while (storage.byProfile[1]?.movieIds != setOf("tt2")) delay(20)
        }

        assertEquals(setOf("tt2"), ids)
        assertEquals("sam", storage.byProfile[1]?.account)
    }

    @Test
    fun `a Trakt answer for the old profile never reaches the new one`() = runBlocking {
        storage.byProfile[2] = SavedTraktWatchedMovies(account = "kid", movieIds = setOf("tt9"))
        val fetchStarted = CompletableDeferred<Unit>()
        val trakt = CompletableDeferred<Unit>()
        val service = service {
            if (fetches.getAndIncrement() > 0) return@service null
            fetchStarted.complete(Unit)
            trakt.await()
            listOf(watched("tt1"))
        }
        val seen = MutableStateFlow<Set<String>?>(null)
        val collector = launch { service.observeAllWatchedMovieIds().collect { seen.value = it } }
        try {
            withTimeout(5_000) { fetchStarted.await() }
            currentProfile.value = 2
            delay(100)
            trakt.complete(Unit)

            withTimeout(5_000) { seen.first { it == setOf("tt9") } }
            delay(300)

            assertEquals(setOf("tt9"), seen.value)
            assertEquals(setOf("tt9"), storage.byProfile[2]?.movieIds)
            assertEquals("kid", storage.byProfile[2]?.account)
            assertNull(storage.byProfile[1])
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `signing out of Trakt empties the list even when the box copy cannot be read`(): Unit = runBlocking {
        storage.byProfile[1] = SavedTraktWatchedMovies(account = "sam", movieIds = setOf("tt1"))
        val service = service { null }
        val seen = MutableStateFlow<Set<String>?>(null)
        val collector = launch { service.observeAllWatchedMovieIds().collect { seen.value = it } }
        try {
            withTimeout(5_000) { seen.first { it == setOf("tt1") } }
            storage.failLoads = true
            authByProfile.getValue(1).value = TraktAuthState()

            withTimeout(5_000) { seen.first { it == emptySet<String>() } }
        } finally {
            collector.cancel()
        }
    }

    private fun watched(imdb: String) = TraktWatchedMovieItemDto(movie = TraktMovieDto(ids = TraktIdsDto(imdb = imdb)))

    private fun service(liveResult: suspend () -> List<TraktWatchedMovieItemDto>?): TraktProgressService {
        val authService = mockk<TraktAuthService>(relaxed = true) {
            every { isCircuitClosed() } returns false
            coEvery { executeAuthorizedRequest<Any>(any()) } coAnswers {
                liveResult()?.let { Response.success<Any>(it, Headers.headersOf("X-Pagination-Page-Count", "1")) }
            }
        }
        val profileManager = mockk<ProfileManager>(relaxed = true) {
            every { activeProfileId } returns currentProfile
            every { activeProfileReady } returns MutableStateFlow(true)
        }
        val authStore = mockk<TraktAuthDataStore>(relaxed = true) {
            every { state } returns currentProfile.flatMapLatest { authByProfile.getValue(it) }
            coEvery { getCurrentState(any<Int>()) } coAnswers { authByProfile.getValue(firstArg()).value }
        }
        return TraktProgressService(
            appContext = mockk(relaxed = true),
            traktApi = mockk(relaxed = true),
            traktAuthService = authService,
            metaRepository = mockk(relaxed = true),
            tmdbService = mockk(relaxed = true),
            traktSettingsDataStore = mockk(relaxed = true),
            layoutPreferenceDataStore = mockk(relaxed = true),
            traktEpisodeMappingService = mockk(relaxed = true),
            profileManager = profileManager,
            watchedSeriesStateHolder = mockk(relaxed = true),
            traktAuthDataStore = authStore,
            savedWatchedMovies = TraktSavedWatchedMovies(storage),
            appVisibility = com.nuvio.tv.core.calendar.CalendarAppVisibility(),
            screensaverController = io.mockk.mockk(relaxed = true) {
                io.mockk.every { playbackActive } returns kotlinx.coroutines.flow.MutableStateFlow(false)
            }
        )
    }
}
