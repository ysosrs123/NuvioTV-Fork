package com.nuvio.tv.core.scout

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.ScoutStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResponse
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.data.remote.api.TmdbRecommendationResult
import com.nuvio.tv.data.remote.api.TmdbRecommendationsResponse
import com.nuvio.tv.domain.model.SCOUT_REFRESH_MS
import com.nuvio.tv.domain.model.ScoutProfile
import com.nuvio.tv.domain.model.ScoutSettings
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

class ScoutEngineTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tmdb = mockk<TmdbApi>()
    private val service = mockk<TmdbService>()
    private val now = SCOUT_REFRESH_MS * 10

    private fun rec(id: Int, name: String = "Rec $id", year: String = "2020") = TmdbRecommendationResult(
        id = id, title = name, posterPath = "/p$id", backdropPath = "/b$id", releaseDate = "$year-01-01",
        voteAverage = 7.5, voteCount = 500, mediaType = "movie", genreIds = listOf(18)
    )

    private fun disc(id: Int) = TmdbDiscoverResult(
        id = id, title = "Popular $id", posterPath = "/p$id", backdropPath = "/b$id", releaseDate = "2021-01-01",
        voteAverage = 8.0, voteCount = 900, genreIds = listOf(35)
    )

    private fun stubDiscover(results: List<TmdbDiscoverResult>) {
        val body = Response.success(TmdbDiscoverResponse(results = results))
        coEvery { tmdb.discoverMovies(apiKey = any(), language = any(), page = any(), sortBy = any(), withCompanies = any(), releaseDateLte = any(), voteCountGte = any(), withGenres = any(), releaseDateGte = any(), voteAverageGte = any(), voteAverageLte = any(), withOriginalLanguage = any(), withOriginCountry = any(), withKeywords = any(), year = any(), watchRegion = any(), withWatchProviders = any(), withWatchMonetizationTypes = any(), withoutCompanies = any(), withoutGenres = any(), withoutKeywords = any(), withoutWatchProviders = any(), voteCountLte = any(), withRuntimeLte = any(), withRuntimeGte = any(), certificationCountry = any(), certificationLte = any()) } returns body
        coEvery { tmdb.discoverTv(apiKey = any(), language = any(), page = any(), sortBy = any(), withCompanies = any(), withNetworks = any(), firstAirDateLte = any(), voteCountGte = any(), withGenres = any(), firstAirDateGte = any(), voteAverageGte = any(), voteAverageLte = any(), withOriginalLanguage = any(), withOriginCountry = any(), withKeywords = any(), firstAirDateYear = any(), withStatus = any(), watchRegion = any(), withWatchProviders = any(), withWatchMonetizationTypes = any(), withoutCompanies = any(), withoutGenres = any(), withoutKeywords = any(), withoutWatchProviders = any(), voteCountLte = any(), withRuntimeLte = any(), withRuntimeGte = any(), certificationCountry = any(), certificationLte = any(), withType = any()) } returns body
    }

    private fun engine(
        watched: List<WatchedItem>,
        profile: ScoutProfile = ScoutProfile(),
        started: List<WatchProgress> = emptyList(),
        listed: ScoutListed = ScoutListed(),
        activeProfile: MutableStateFlow<Int> = MutableStateFlow(1),
        ceiling: Int? = null,
        levels: Map<String, Int?> = emptyMap()
    ): ScoutEngine {
        val context = mockk<Context> { every { filesDir } returns folder.root }
        val settings = mockk<TmdbSettingsDataStore>(relaxed = true)
        every { settings.settings } returns MutableStateFlow(com.nuvio.tv.domain.model.TmdbSettings(language = "en"))
        val progress = mockk<WatchProgressRepository> {
            every { tasteHistory } returns flowOf(watched)
            every { allProgress } returns flowOf(started)
        }
        val store = mockk<ScoutStore> { coEvery { current() } returns profile }
        io.mockk.every { store.profile } returns kotlinx.coroutines.flow.flowOf(profile)
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns activeProfile }
        val support = mockk<ScoutFolderSupport> { coEvery { listed() } returns listed }
        val picks = mockk<ScoutPicksBuilder> {
            coEvery { improve(any(), any(), any(), any(), any(), any(), any()) } returns null
            coEvery { ageCeiling(any(), any(), any()) } returns ceiling
            every { knownAgeLevels(any()) } returns levels
        }
        return ScoutEngine(context, tmdb, service, settings, dagger.Lazy { progress }, mockk(relaxed = true), dagger.Lazy { mockk(relaxed = true) }, store, profiles, support, picks, dagger.Lazy { mockk<ScoutMoodCheck>(relaxed = true) })
    }

    private fun watched(id: String, name: String, at: Long, year: String? = "2019") =
        WatchedItem(id, "movie", name, watchedAt = at, releaseInfo = year)

    private fun seeded() = listOf(watched("tt1", "One", 3), watched("tt2", "Two", 2), watched("tt3", "Three", 1))

    private fun stubRecs(vararg recs: TmdbRecommendationResult) {
        coEvery { service.ensureTmdbId(any(), any(), any()) } returns "9999"
        coEvery { tmdb.getMovieRecommendations(any(), any(), any(), any()) } returns
            Response.success(TmdbRecommendationsResponse(results = recs.toList()))
    }

    @Test
    fun `builds picks from history and names the reason`() = runBlocking {
        stubRecs(*(1..60).map { rec(it) }.toTypedArray())
        stubDiscover(emptyList())
        val e = engine(seeded())
        e.refreshIfDue(now)
        val pool = e.pool.value!!
        assertEquals(60, pool.picked.size)
        assertEquals("One", pool.picked.first().seedName)
        assertEquals(2, pool.because.size)
    }

    @Test
    fun `new viewer gets popular titles with no reason`() = runBlocking {
        stubDiscover((1..30).map { disc(it) })
        val e = engine(emptyList())
        e.refreshIfDue(now)
        val picked = e.pool.value!!.picked
        assertTrue(picked.isNotEmpty())
        assertNull(picked.first().seedName)
        assertTrue(e.pool.value!!.because.isEmpty())
    }

    @Test
    fun `watched and started titles are not offered`() = runBlocking {
        stubRecs(rec(1, "One", "2019"), rec(2, "Other"), rec(3, "Halfway"))
        stubDiscover(emptyList())
        val started = listOf(mockk<WatchProgress> {
            every { contentId } returns "tt9"
            every { contentType } returns "movie"
            every { name } returns "Halfway"
            every { position } returns 50L
            every { duration } returns 100L
            every { lastWatched } returns now
        })
        val e = engine(seeded(), started = started)
        e.refreshIfDue(now)
        assertEquals(listOf("Other"), e.pool.value!!.picked.map { it.name })
    }

    @Test
    fun `plan to watch and watching are never offered`() = runBlocking {
        stubRecs(rec(1, "Listed by id"), rec(2, "Listed by name"), rec(3, "Free"))
        stubDiscover(emptyList())
        val listed = ScoutListed(ids = setOf("tmdb:1"), names = setOf(com.nuvio.tv.domain.model.scoutNormalizedName("Listed by name")))
        val e = engine(seeded(), listed = listed)
        e.refreshIfDue(now)
        assertEquals(listOf("Free"), e.pool.value!!.picked.map { it.name })
    }

    @Test
    fun `left out genres are removed`() = runBlocking {
        stubRecs(rec(1))
        stubDiscover(emptyList())
        val e = engine(seeded(), ScoutProfile(settings = ScoutSettings(leaveOutGenres = setOf(18))))
        e.refreshIfDue(now)
        assertNull(e.pool.value)
    }

    @Test
    fun `pool survives on disk and is not rebuilt within the day`() = runBlocking {
        stubRecs(*(1..60).map { rec(it) }.toTypedArray())
        stubDiscover(emptyList())
        engine(seeded()).refreshIfDue(now)
        val second = engine(seeded())
        second.refreshIfDue(now + 1000)
        assertEquals(60, second.pool.value!!.picked.size)
        coVerify(exactly = 3) { tmdb.getMovieRecommendations(any(), any(), any(), any()) }
    }

    @Test
    fun `a failed refresh keeps the old picks`() = runBlocking {
        stubRecs(*(1..60).map { rec(it) }.toTypedArray())
        stubDiscover(emptyList())
        val e = engine(seeded())
        e.refreshIfDue(now)
        coEvery { tmdb.getMovieRecommendations(any(), any(), any(), any()) } throws java.io.IOException("offline")
        e.refreshIfDue(now + SCOUT_REFRESH_MS)
        assertEquals(60, e.pool.value!!.picked.size)
        assertEquals(now, e.pool.value!!.builtAt)
    }

    @Test
    fun `title with no tmdb match is skipped`() = runBlocking {
        coEvery { service.ensureTmdbId(any(), any(), any()) } returns null
        stubDiscover((1..3).map { disc(it) })
        val e = engine(seeded())
        e.refreshIfDue(now)
        assertNotNull(e.pool.value)
        assertFalse(e.pool.value!!.picked.isEmpty())
        coVerify(exactly = 0) { tmdb.getMovieRecommendations(any(), any(), any(), any()) }
    }
    private fun savePool(profileId: Int, title: String) {
        val pool = com.nuvio.tv.domain.model.ScoutPool(
            builtAt = System.currentTimeMillis(),
            historySignature = com.nuvio.tv.domain.model.scoutHistorySignature(emptyList(), emptySet()),
            version = com.nuvio.tv.domain.model.SCOUT_POOL_VERSION,
            picked = listOf(com.nuvio.tv.domain.model.ScoutPick(tmdbId = profileId, tv = false, name = title))
        )
        java.io.File(folder.root, "scout").mkdirs()
        java.io.File(folder.root, "scout/pool_$profileId.json")
            .writeText(com.squareup.moshi.Moshi.Builder().build().adapter(com.nuvio.tv.domain.model.ScoutPool::class.java).toJson(pool))
    }

    private suspend fun ScoutEngine.awaitFirst(title: String?) = withTimeout(5_000) {
        while (pool.value?.picked?.firstOrNull()?.name != title) delay(10)
    }

    @Test
    fun `a profile switch shows that profile's picks`() = runBlocking {
        savePool(1, "Adult pick")
        savePool(2, "Kids pick")
        val active = MutableStateFlow(1)
        val e = engine(emptyList(), activeProfile = active)
        val job = launch(Dispatchers.Default) { e.followProfiles(checkEveryMs = 60_000) }
        e.awaitFirst("Adult pick")
        active.value = 2
        e.awaitFirst("Kids pick")
        active.value = 3
        e.awaitFirst(null)
        job.cancel()
    }

    @Test
    fun `picks built for a profile that was switched away from are not shown`() = runBlocking {
        val active = MutableStateFlow(1)
        coEvery { service.ensureTmdbId(any(), any(), any()) } returns "9999"
        coEvery { tmdb.getMovieRecommendations(any(), any(), any(), any()) } coAnswers {
            active.value = 2
            Response.success(TmdbRecommendationsResponse(results = (1..60).map { rec(it) }))
        }
        stubDiscover(emptyList())
        val e = engine(seeded(), activeProfile = active)
        e.refreshIfDue(now)
        assertNull(e.pool.value)
        assertFalse(java.io.File(folder.root, "scout/pool_1.json").exists())
    }

    @Test
    fun `a kids profile keeps adult titles out and tops up with kids titles only`() = runBlocking {
        stubRecs(rec(1, "Kids film"), rec(2, "Adult film"), rec(3, "Unrated drama"))
        val kidsTv = TmdbDiscoverResult(
            id = 50, name = "Kids show", posterPath = "/p50", firstAirDate = "2020-01-01",
            voteAverage = 7.5, voteCount = 300, genreIds = listOf(10762, 16)
        )
        stubDiscover(listOf(kidsTv))
        val e = engine(seeded(), ceiling = 0, levels = mapOf("movie:1" to 0, "movie:2" to 3))
        e.refreshIfDue(now)
        val pool = e.pool.value!!
        assertTrue(pool.picked.any { it.name == "Kids film" })
        assertFalse(pool.picked.any { it.name == "Adult film" || it.name == "Unrated drama" })
        assertTrue(pool.because.flatMap { it.picks }.none { it.name == "Adult film" })
        coVerify { tmdb.discoverMovies(apiKey = any(), language = any(), page = any(), sortBy = "popularity.desc", withCompanies = any(), releaseDateLte = any(), voteCountGte = any(), withGenres = any(), releaseDateGte = any(), voteAverageGte = any(), voteAverageLte = any(), withOriginalLanguage = any(), withOriginCountry = any(), withKeywords = any(), year = any(), watchRegion = any(), withWatchProviders = any(), withWatchMonetizationTypes = any(), withoutCompanies = any(), withoutGenres = any(), withoutKeywords = any(), withoutWatchProviders = any(), voteCountLte = any(), withRuntimeLte = any(), withRuntimeGte = any(), certificationCountry = "US", certificationLte = "G") }
        coVerify { tmdb.discoverTv(apiKey = any(), language = any(), page = any(), sortBy = "popularity.desc", withCompanies = any(), withNetworks = any(), firstAirDateLte = any(), voteCountGte = any(), withGenres = "10762", firstAirDateGte = any(), voteAverageGte = any(), voteAverageLte = any(), withOriginalLanguage = any(), withOriginCountry = any(), withKeywords = any(), firstAirDateYear = any(), withStatus = any(), watchRegion = any(), withWatchProviders = any(), withWatchMonetizationTypes = any(), withoutCompanies = any(), withoutGenres = any(), withoutKeywords = any(), withoutWatchProviders = any(), voteCountLte = any(), withRuntimeLte = any(), withRuntimeGte = any()) }
    }

    @Test
    fun `with one finished title the shows being watched become seeds`() = runBlocking {
        stubRecs(*(1..60).map { rec(it) }.toTypedArray())
        stubDiscover(emptyList())
        val started = listOf("tt7", "tt8").mapIndexed { index, id ->
            WatchProgress(
                contentId = id, contentType = "series", name = "Show $id", poster = null, backdrop = null, logo = null,
                videoId = id, season = 1, episode = 1, episodeTitle = null, position = 10, duration = 100, lastWatched = now - index
            )
        }
        coEvery { tmdb.getTvRecommendations(any(), any(), any(), any()) } returns
            Response.success(TmdbRecommendationsResponse(results = (61..70).map { rec(it) }))
        val e = engine(listOf(watched("tt1", "One", 3)), started = started)
        e.refreshIfDue(now)
        coVerify(exactly = 2) { tmdb.getTvRecommendations(any(), any(), any(), any()) }
        assertTrue(e.pool.value!!.picked.any { it.tmdbId in 61..70 })
    }

    @Test
    fun `forgetting the shown profile clears its picks`() = runBlocking {
        savePool(2, "Gone")
        val e = engine(emptyList(), activeProfile = MutableStateFlow(2))
        e.refreshIfDue()
        assertEquals("Gone", e.pool.value!!.picked.first().name)
        e.forget(5)
        assertNotNull(e.pool.value)
        e.forget(2)
        assertNull(e.pool.value)
    }
}
