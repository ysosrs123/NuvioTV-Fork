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
        val enrichment = mockk<ContinueWatchingEnrichmentCache> { coEvery { getNextUpSnapshot() } throws IllegalStateException("bad file") }
        val reader = CalendarFollowedSourceReader(
            library, progress, watchedItems, traktLibrary, traktProgress, mockk<SimklSyncRepository>(),
            mockk<MdbListSyncRepository>(), traktSettings, enrichment, tracking, mockk<MetaRepository>(relaxed = true)
        )

        val snapshot = reader.read(1)
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
