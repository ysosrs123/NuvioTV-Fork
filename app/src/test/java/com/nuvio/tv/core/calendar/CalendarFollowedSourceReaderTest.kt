package com.nuvio.tv.core.calendar

import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.mdblist.MdbListSyncRepository
import com.nuvio.tv.data.repository.TraktLibraryService
import com.nuvio.tv.data.repository.TraktProgressService
import com.nuvio.tv.data.simkl.SimklSyncRepository
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarFollowedSourceReaderTest {
    @Test fun watchedEpisodesAreFoundByAnyIdOfTheShow() {
        val index = CalendarWatchedIndex.Builder()
            .add("tmdb:5", 1, 2)
            .addAll(mapOf("tt7" to setOf(3 to 4)))
            .add("tt8", null, 1)
            .build()
        val show = CalendarTitleRecord("series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1", tmdb = 5))
        assertTrue(index.isWatched(show, CalendarEntry(season = 1, episode = 2)))
        assertTrue(!index.isWatched(show, CalendarEntry(season = 1, episode = 3)))
        assertTrue(index.isWatched(CalendarIds(imdb = "tt7"), 3, 4))
        assertTrue(!index.isWatched(CalendarIds(imdb = "tt8"), null, 1))
        assertTrue(!index.isWatched(show, CalendarEntry(kind = CalendarEntryKind.MOVIE_DIGITAL, season = 1, episode = 2)))
    }

    @Test fun aKitsuTitleTakesTheImdbIdFromItsCachedDetails() {
        val anime = CalendarTitleCandidate(CalendarIds.fromContentId("kitsu:46474"), CalendarTitleType.SERIES, CalendarOrigin.LIBRARY, contentId = "kitsu:46474")
        val unknown = anime.copy(contentId = "kitsu:1")
        val known = CalendarTitleCandidate(CalendarIds(imdb = "tt5"), CalendarTitleType.SERIES, CalendarOrigin.LIBRARY, contentId = "tt5")
        val meta = mockk<com.nuvio.tv.domain.model.Meta> {
            every { imdbId } returns "tt13706018"
            every { id } returns "kitsu:46474"
            every { type } returns com.nuvio.tv.domain.model.ContentType.SERIES
            every { links } returns emptyList()
        }
        val asked = mutableListOf<Pair<String, String>>()
        val out = calendarResolveUnreadableIds(listOf(anime, unknown, known)) { type, id ->
            asked += type to id
            meta.takeIf { id == "kitsu:46474" }
        }
        assertEquals(CalendarIds(imdb = "tt13706018"), out[0].ids)
        assertEquals("kitsu:46474", out[0].contentId)
        assertTrue(out[1].ids.isEmpty)
        assertEquals(known, out[2])
        assertEquals(listOf("series" to "kitsu:46474", "series" to "kitsu:1"), asked)
    }

    @Test fun detailsWithoutAnImdbIdGiveTheIdsInTheirImdbAndTmdbLinks() {
        fun meta(kind: com.nuvio.tv.domain.model.ContentType, vararg urls: String) = mockk<com.nuvio.tv.domain.model.Meta> {
            every { imdbId } returns null
            every { id } returns "kitsu:46474"
            every { type } returns kind
            every { links } returns urls.map { com.nuvio.tv.domain.model.MetaLink("link", "imdb", it) }
        }
        val series = com.nuvio.tv.domain.model.ContentType.SERIES
        assertEquals(
            CalendarIds(imdb = "tt13706018", tmdb = 209867),
            calendarIdsOf(meta(series, "stremio:///search?search=Frieren", "https://imdb.com/title/tt13706018/", "https://www.themoviedb.org/tv/209867-frieren"))
        )
        assertEquals(CalendarIds(), calendarIdsOf(meta(series, "https://www.themoviedb.org/movie/5", "https://www.imdb.com/name/nm1")))
        assertEquals(
            CalendarIds(tmdb = 5),
            calendarIdsOf(meta(com.nuvio.tv.domain.model.ContentType.MOVIE, "https://www.themoviedb.org/movie/5"))
        )
        assertTrue(calendarIdsOf(meta(series)).isEmpty)
    }

    @Test fun animeIdsAreAskedOfSimklAndOtherIdsAreNot() {
        assertEquals("kitsu" to "46474", calendarSimklLookup("kitsu:46474"))
        assertEquals("mal" to "52991", calendarSimklLookup("MAL:52991"))
        assertEquals("anilist" to "154587", calendarSimklLookup("anilist:154587:1:3"))
        assertEquals("anidb" to "17617", calendarSimklLookup("anidb:17617"))
        assertNull(calendarSimklLookup("kitsu:frieren"))
        assertNull(calendarSimklLookup("tt13706018"))
        assertNull(calendarSimklLookup("mycatalog:46474"))
        assertNull(calendarSimklLookup("kitsu:"))
    }

    @Test fun simklIdsCountOnlyWithAnIdThatGivesDates() {
        assertEquals(
            CalendarIds(imdb = "tt13706018", tmdb = 209867, tvdb = 424536, simkl = 2177400),
            calendarIdsFromSimkl("tt13706018", "209867", "424536", 2177400)
        )
        assertEquals(CalendarIds(tmdb = 209867, simkl = 1), calendarIdsFromSimkl("", " 209867 ", null, 1))
        assertTrue(calendarIdsFromSimkl(null, null, null, 2177400).isEmpty)
        assertTrue(calendarIdsFromSimkl("nm1", "x", "", 2177400).isEmpty)
    }

    @Test fun aLookupPrefersTheAddOnsAndFallsBackToSimkl() {
        val addon = CalendarIds(imdb = "tt1")
        val simkl = CalendarIds(imdb = "tt2", simkl = 2)
        assertEquals(addon, calendarLookedUpIds(addon, simkl))
        assertEquals(simkl, calendarLookedUpIds(CalendarIds(), simkl))
        assertEquals(simkl, calendarLookedUpIds(null, simkl))
        assertEquals(CalendarIds(), calendarLookedUpIds(CalendarIds(), null))
        assertEquals(CalendarIds(), calendarLookedUpIds(null, CalendarIds()))
        assertNull(calendarLookedUpIds(null, null))
    }

    @Test fun aKitsuTitleWithoutCachedDetailsTakesTheIdsFoundBefore() {
        val anime = CalendarTitleCandidate(CalendarIds(), CalendarTitleType.SERIES, CalendarOrigin.LIBRARY, contentId = "kitsu:46474")
        val saved = CalendarIds(imdb = "tt13706018")
        val out = calendarResolveUnreadableIds(listOf(anime, anime.copy(contentId = "kitsu:1")), mapOf("kitsu:46474" to saved)) { _, _ -> null }
        assertEquals(saved, out[0].ids)
        assertTrue(out[1].ids.isEmpty)
    }

    @Test fun watchedUnderAKitsuIdCountsOnceTheShowHasAnImdbId() {
        val ids = CalendarIds(imdb = "tt13706018")
        val index = CalendarWatchedIndex.Builder()
            .add("kitsu:46474", 1, 3)
            .nextUpNotAired("kitsu:46474", java.time.LocalDate.parse("2026-10-30"))
            .alias("kitsu:46474", ids)
            .build()
        val show = CalendarTitleRecord("series:imdb:tt13706018", CalendarTitleType.SERIES, ids)
        assertTrue(index.isWatched(ids, 1, 3))
        assertTrue(index.isWatched(show, CalendarEntry(season = 1, episode = 5, airDate = "2026-10-23")))
        assertFalse(index.isWatched(show, CalendarEntry(season = 1, episode = 6, airDate = "2026-10-30")))
    }

    @Test fun anAddonReleasedValueKeepsOnlyItsDate() {
        assertEquals("2026-10-10", plainReleaseDate("2026-10-10T00:00:00.000Z"))
        assertEquals("2026-10-10", plainReleaseDate("2026-10-10"))
        assertEquals(null, plainReleaseDate("soon"))
    }

    @Test fun contentTypes() {
        assertEquals(CalendarTitleType.MOVIE, calendarTitleType("movie"))
        assertEquals(CalendarTitleType.SERIES, calendarTitleType("series"))
        assertEquals(CalendarTitleType.SERIES, calendarTitleType("anime"))
        assertEquals(null, calendarTitleType("channel"))
        assertEquals(CalendarIds(tmdb = 12), CalendarIds.fromContentId("tmdb:12"))
        assertEquals(CalendarIds(imdb = "tt0944947"), CalendarIds.fromContentId("tt0944947:1:2"))
        assertTrue(CalendarIds.fromContentId("kitsu:1").isEmpty)
    }

    @Test fun partsThatTimeOutOrFailAreNotedWithTheOriginsTheyWouldHaveGiven() = runBlocking {
        val parts = CalendarSourceParts(timeoutMs = 50)
        parts.part("library", CalendarOrigin.LIBRARY) { delay(5_000) }
        parts.part("simkl", CalendarOrigin.SIMKL_WATCHING) { error("offline") }
        parts.part("progress", CalendarOrigin.PROGRESS) { }
        parts.notLoaded("mdblist", CalendarOrigin.MDBLIST_WATCHED)
        assertEquals(null, parts.runPart("posters", counts = false) { delay(5_000) })
        assertEquals(null, parts.runPart("next up", counts = false) { error("bad file") })
        assertEquals(emptyList<String>(), parts.runPart("hidden") { emptyList<String>() })

        assertEquals(mapOf("library" to "timed out", "simkl" to "failed", "mdblist" to "not loaded yet"), parts.reasons)
        assertEquals(setOf(CalendarOrigin.LIBRARY, CalendarOrigin.SIMKL_WATCHING, CalendarOrigin.MDBLIST_WATCHED), parts.origins)
    }

    @Test fun traktWatchedShowsAndListsNotLoadedYetAreReportedAndKept() = runBlocking {
        val tracking = mockk<TrackingProviderRegistry> {
            every { isAuthenticated(any()) } returns false
            every { isAuthenticated(TrackingProviderId.TRAKT) } returns true
        }
        val library = mockk<LibraryPreferences> { every { libraryItems } returns flow { error("disk") } }
        val progress = mockk<WatchProgressPreferences> { every { observeAllProgress(any()) } returns flowOf(emptyList()) }
        val watchedItems = mockk<WatchedItemsPreferences> { every { allItems } returns flowOf(emptyList()) }
        val traktProgress = mockk<TraktProgressService> {
            every { currentWatchedShowSeeds() } returns null
            every { currentDroppedShowIds() } returns emptySet()
            every { getWatchedShowEpisodes() } returns emptyMap()
        }
        val traktLibrary = mockk<TraktLibraryService> { every { currentItemsOrNull() } returns null }
        val traktSettings = mockk<TraktSettingsDataStore> { every { dismissedNextUpKeys } returns flowOf(emptySet()) }
        val enrichment = mockk<ContinueWatchingEnrichmentCache> { coEvery { getNextUpSnapshot(1) } throws IllegalStateException("bad file") }
        val reader = CalendarFollowedSourceReader(
            library, progress, watchedItems, traktLibrary, traktProgress, mockk<SimklSyncRepository>(),
            mockk<MdbListSyncRepository>(), traktSettings, enrichment, tracking, mockk<MetaRepository>(relaxed = true)
        )

        val snapshot = reader.read(1, followOtherServices = true)
        assertFalse(snapshot.traktWatchedLoaded)
        assertFalse(snapshot.complete)
        assertEquals(
            mapOf("library" to "failed", "trakt" to "not loaded yet", "trakt lists" to "not loaded yet"),
            snapshot.notReady
        )
        assertEquals(
            setOf(
                CalendarOrigin.LIBRARY, CalendarOrigin.TRAKT_WATCHED, CalendarOrigin.TRAKT_CALENDAR,
                CalendarOrigin.TRAKT_WATCHLIST, CalendarOrigin.TRAKT_LIST
            ),
            snapshot.unreadOrigins
        )
        assertTrue(snapshot.addonEntries.isEmpty())
    }
}
