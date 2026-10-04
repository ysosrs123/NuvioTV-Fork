package com.nuvio.tv.data.repository

import com.nuvio.tv.data.local.AnimeSkipSettingsDataStore
import com.nuvio.tv.data.local.AnimeSkipSettingsSnapshot
import com.nuvio.tv.data.remote.api.*
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class SkipIntroRepositoryTest {
    private val introDb = mockk<IntroDbApi>()
    private val aniSkip = mockk<AniSkipApi>()
    private val animeSkip = mockk<AnimeSkipApi>()
    private val resolver = mockk<SimklIdResolver>()
    private val settings = mockk<AnimeSkipSettingsDataStore>()
    private var snapshot = AnimeSkipSettingsSnapshot(1, true, "test-client")
    private val ids = SimklIdResolver.ResolvedIds(100, "anime", mal = "20", anilist = "30", imdb = "tt123", tvdbSeason = 2)
    private val repository = SkipIntroRepository(introDb, aniSkip, animeSkip, resolver, settings, mockk(relaxed = true))

    init {
        coEvery { settings.snapshot() } answers { snapshot }
        coEvery { introDb.getSegments(any(), any(), any(), any()) } returns Response.success(IntroDbSegmentsResponse())
        coEvery { resolver.resolveIds(any(), any()) } returns ids
        coEvery { resolver.resolveIdsForImdbEpisode(any(), any(), any()) } returns ids
        coEvery { resolver.resolveEpisodeTvdb(any(), any(), any()) } returns null
        coEvery { resolver.getEpisodeMapping(any(), any()) } returns emptyList()
        coEvery { aniSkip.getSkipTimes(any(), any(), any(), any()) } returns Response.success(
            AniSkipResponse(true, listOf(AniSkipResult(AniSkipInterval(10.0, 80.0), "op"), AniSkipResult(AniSkipInterval(1200.0, 1290.0), "ed")))
        )
        coEvery { animeSkip.query(any(), any()) } answers {
            val request = secondArg<AnimeSkipRequest>()
            Response.success(AnimeSkipResponse(if ("findShowsByExternalId" in request.query) {
                AnimeSkipData(findShowsByExternalId = listOf(AnimeSkipShow("show")))
            } else {
                AnimeSkipData(findEpisodesByShowId = listOf(AnimeSkipEpisode(number = "1", timestamps = listOf(
                    AnimeSkipTimestamp(5.0, AnimeSkipTimestampType("Intro")),
                    AnimeSkipTimestamp(90.0, AnimeSkipTimestampType("Canon"))
                ))))
            }))
        }
    }

    @Test fun `missing TVDB mapping still permits MAL and Kitsu Anime-Skip results`() = runTest {
        for (result in listOf(repository.getSkipIntervalsForMal("20", 1), repository.getSkipIntervalsForKitsu("40", 1))) {
            assertEquals(listOf("animeskip", "aniskip"), result.map { it.provider })
            assertEquals(listOf(5.0, 1200.0), result.map { it.startTime })
        }
        coVerify(exactly = 0) { introDb.getSegments(any(), any(), any(), any()) }
    }

    @Test fun `settings and profile changes cannot reuse stale interval cache`() = runTest {
        assertEquals("animeskip", repository.getSkipIntervalsForMal("20", 1).first().provider)
        snapshot = snapshot.copy(enabled = false)
        assertEquals("aniskip", repository.getSkipIntervalsForMal("20", 1).first().provider)
        snapshot = AnimeSkipSettingsSnapshot(2, true, "other-client")
        assertEquals("animeskip", repository.getSkipIntervalsForMal("20", 1).first().provider)
        coVerify(exactly = 2) { animeSkip.query("other-client", any()) }
        coVerify(exactly = 3) { aniSkip.getSkipTimes("20", 1, any(), any()) }
    }

    @Test fun `explicit IMDb episode context participates in cache identity`() = runTest {
        repository.getSkipIntervalsForMal("20", 1, "tt123", 1, 1)
        repository.getSkipIntervalsForMal("20", 1, "tt123", 2, 1)
        coVerify(exactly = 2) { aniSkip.getSkipTimes("20", 1, any(), any()) }
    }

    @Test fun `IMDb season mapping uses the resolved anime episode`() = runTest {
        coEvery { resolver.getEpisodeMapping(100, "anime") } returns listOf(SimklIdResolver.EpisodeMapping(1, 2, 13))
        repository.getSkipIntervals("tt123", 2, 13)
        coVerify { resolver.resolveIdsForImdbEpisode("tt123", 2, 13) }
        coVerify { aniSkip.getSkipTimes("20", 1, any(), any()) }
    }

    @Test fun `provider cancellation propagates instead of caching an empty result`() = runTest {
        coEvery { aniSkip.getSkipTimes(any(), any(), any(), any()) } throws CancellationException("cancelled")
        try {
            repository.getSkipIntervalsForMal("20", 1)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun `disabled Anime-Skip never sends the client credential`() = runTest {
        snapshot = snapshot.copy(enabled = false)
        repository.getSkipIntervalsForMal("20", 1)
        coVerify(exactly = 0) { animeSkip.query(any(), any()) }
    }
}
