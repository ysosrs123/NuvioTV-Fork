package com.nuvio.tv.core.calendar

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingProviderId.MDBLIST
import com.nuvio.tv.core.tracking.TrackingProviderId.SIMKL
import com.nuvio.tv.core.tracking.TrackingProviderId.TRAKT
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.mdblist.MdbListSyncRepository
import com.nuvio.tv.data.repository.TraktLibraryService
import com.nuvio.tv.data.repository.TraktProgressService
import com.nuvio.tv.data.simkl.SimklListStatus
import com.nuvio.tv.data.simkl.SimklSyncRepository
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarSourcePlanTest {
    private val all = setOf(TRAKT, SIMKL, MDBLIST)

    @Test fun nuvioLibraryFollowsOnlyTheNuvioLibraryAndTheProgressSource() {
        val plan = calendarSourcePlan(LibrarySourceMode.LOCAL, WatchProgressSource.NUVIO_SYNC, all, followOtherServices = false)
        assertEquals(CalendarSourcePlan(nuvioLibrary = true, libraryFrom = emptySet(), progressFrom = emptySet()), plan)
        all.forEach { assertFalse(plan.reads(it)) }

        val withTrakt = calendarSourcePlan(LibrarySourceMode.LOCAL, WatchProgressSource.TRAKT, all, followOtherServices = false)
        assertEquals(CalendarSourcePlan(nuvioLibrary = true, libraryFrom = emptySet(), progressFrom = setOf(TRAKT)), withTrakt)
    }

    @Test fun eachServiceAsLibrarySourceIsReadWithTheNuvioLibraryAndNoOtherService() {
        mapOf(LibrarySourceMode.TRAKT to TRAKT, LibrarySourceMode.SIMKL to SIMKL, LibrarySourceMode.MDBLIST to MDBLIST)
            .forEach { (mode, provider) ->
                val plan = calendarSourcePlan(mode, WatchProgressSource.NUVIO_SYNC, all, followOtherServices = false)
                assertEquals(CalendarSourcePlan(nuvioLibrary = true, libraryFrom = setOf(provider), progressFrom = emptySet()), plan)
                (all - provider).forEach { assertFalse(plan.reads(it)) }
            }
        val mixed = calendarSourcePlan(LibrarySourceMode.SIMKL, WatchProgressSource.TRAKT, all, followOtherServices = false)
        assertEquals(CalendarSourcePlan(nuvioLibrary = true, libraryFrom = setOf(SIMKL), progressFrom = setOf(TRAKT)), mixed)
        assertFalse(mixed.reads(MDBLIST))
    }

    @Test fun aSourceThatIsNotSignedInFallsBackToNuvio() {
        val plan = calendarSourcePlan(LibrarySourceMode.MDBLIST, WatchProgressSource.SIMKL, setOf(TRAKT), followOtherServices = false)
        assertEquals(CalendarSourcePlan(nuvioLibrary = true, libraryFrom = emptySet(), progressFrom = emptySet()), plan)
    }

    @Test fun theSwitchFollowsEverySignedInService() {
        val plan = calendarSourcePlan(LibrarySourceMode.TRAKT, WatchProgressSource.TRAKT, setOf(TRAKT, MDBLIST), followOtherServices = true)
        assertEquals(CalendarSourcePlan(nuvioLibrary = true, libraryFrom = setOf(TRAKT, MDBLIST), progressFrom = setOf(TRAKT, MDBLIST)), plan)
        assertFalse(plan.reads(SIMKL))
    }

    @Test fun theSwitchIsOffByDefaultAndStored() {
        assertFalse(emptyPreferences().toCalendarSettings().followOtherServices)
        val on = mutablePreferencesOf(CalendarSettingsKeys.followOtherServices to true)
        assertTrue(on.toCalendarSettings().followOtherServices)
    }

    @Test fun simklPlanToWatchCountsOnlyAsLibrary() {
        assertEquals(CalendarOrigin.SIMKL_PLAN_TO_WATCH, calendarSimklOrigin(SimklListStatus.PLAN_TO_WATCH, asLibrary = true))
        assertNull(calendarSimklOrigin(SimklListStatus.PLAN_TO_WATCH, asLibrary = false))
        assertEquals(CalendarOrigin.SIMKL_WATCHING, calendarSimklOrigin(SimklListStatus.WATCHING, asLibrary = false))
        assertEquals(CalendarOrigin.SIMKL_COMPLETED, calendarSimklOrigin(SimklListStatus.COMPLETED, asLibrary = false))
        assertEquals(CalendarOrigin.DROPPED, calendarSimklOrigin(SimklListStatus.DROPPED, asLibrary = false))
        assertNull(calendarSimklOrigin(null, asLibrary = true))
    }

    @Test fun mdbListLikedAndExternalListsAreNeverFollowed() {
        assertTrue(calendarFollowsMdbListList("mdblist:watchlist"))
        assertTrue(calendarFollowsMdbListList("mdblist:list:12"))
        assertTrue(calendarFollowsMdbListList("mdblist:dynamic:12"))
        assertFalse(calendarFollowsMdbListList("mdblist:liked:12"))
        assertFalse(calendarFollowsMdbListList("mdblist:external:12"))
    }

    @Test fun onlyDynamicMdbListListsAreCapped() {
        assertEquals(CALENDAR_DYNAMIC_LIST_LIMIT, calendarMdbListItemLimit("mdblist:dynamic:12"))
        assertEquals(Int.MAX_VALUE, calendarMdbListItemLimit("mdblist:list:12"))
        assertEquals(Int.MAX_VALUE, calendarMdbListItemLimit("mdblist:watchlist"))
    }

    private fun reader(
        librarySource: LibrarySourceMode,
        progressSource: WatchProgressSource,
        traktLibrary: TraktLibraryService = mockk(),
        traktProgress: TraktProgressService = mockk()
    ): CalendarFollowedSourceReader {
        val tracking = mockk<TrackingProviderRegistry> {
            every { isAuthenticated(any()) } returns false
            every { isAuthenticated(TrackingProviderId.TRAKT) } returns true
        }
        val saved = SavedLibraryItem(
            "tt1", "series", "Saved Show", null, PosterShape.POSTER, null, null, null, null, emptyList(), null, addedAt = 1_000L
        )
        val library = mockk<LibraryPreferences> { every { libraryItems } returns flowOf(listOf(saved)) }
        val progress = mockk<WatchProgressPreferences> { every { observeAllProgress(any()) } returns flowOf(emptyList()) }
        val watchedItems = mockk<WatchedItemsPreferences> { every { allItems } returns flowOf(emptyList()) }
        val traktSettings = mockk<TraktSettingsDataStore> {
            every { dismissedNextUpKeys } returns flowOf(emptySet())
            coEvery { getLibrarySourceMode(1) } returns librarySource
            coEvery { getWatchProgressSource(1) } returns progressSource
        }
        val enrichment = mockk<ContinueWatchingEnrichmentCache> {
            coEvery { getNextUpSnapshot(1) } returns emptyList()
            coEvery { getInProgressSnapshot(1) } returns emptyList()
        }
        return CalendarFollowedSourceReader(
            library, progress, watchedItems, traktLibrary, traktProgress, mockk<SimklSyncRepository>(),
            mockk<MdbListSyncRepository>(), mockk<com.nuvio.tv.data.wetrakr.WeTrakrSyncRepository>(), traktSettings, enrichment, tracking, mockk<MetaRepository>(relaxed = true)
        )
    }

    @Test fun withTheNuvioLibraryASignedInTraktIsNotRead() = runBlocking {
        val traktLibrary = mockk<TraktLibraryService>()
        val traktProgress = mockk<TraktProgressService>()
        val snapshot = reader(LibrarySourceMode.LOCAL, WatchProgressSource.NUVIO_SYNC, traktLibrary, traktProgress).read(1)

        assertEquals(emptyMap<String, String>(), snapshot.notReady)
        assertEquals(listOf(CalendarOrigin.LIBRARY), snapshot.candidates.map { it.origin })
        assertFalse(snapshot.traktFollowed)
        assertTrue(snapshot.traktWatchedLoaded)
        verify(exactly = 0) { traktLibrary.currentItemsOrNull() }
        verify(exactly = 0) { traktProgress.currentWatchedShowSeeds() }
    }

    @Test fun withTraktAsLibrarySourceTheNuvioLibraryIsStillRead() = runBlocking {
        val entry = LibraryEntry(
            id = "tt2", type = "series", name = "Listed Show", poster = null, background = null, logo = null,
            description = null, releaseInfo = null, imdbRating = null, genres = emptyList(), addonBaseUrl = null,
            listKeys = setOf(TraktLibraryService.WATCHLIST_KEY)
        )
        val traktLibrary = mockk<TraktLibraryService> { every { currentItemsOrNull() } returns listOf(entry) }
        val snapshot = reader(LibrarySourceMode.TRAKT, WatchProgressSource.NUVIO_SYNC, traktLibrary).read(1)

        assertEquals(emptyMap<String, String>(), snapshot.notReady)
        assertEquals(listOf(CalendarOrigin.LIBRARY, CalendarOrigin.TRAKT_WATCHLIST), snapshot.candidates.map { it.origin })
        assertFalse(snapshot.traktFollowed)
    }

    @Test fun withTheSwitchOnEverySignedInServiceIsReadAsBefore() = runBlocking {
        val traktLibrary = mockk<TraktLibraryService> { every { currentItemsOrNull() } returns emptyList() }
        val traktProgress = mockk<TraktProgressService> {
            every { currentWatchedShowSeeds() } returns emptyList()
            every { currentDroppedShowIds() } returns emptySet()
            every { getWatchedShowEpisodes() } returns emptyMap()
        }
        val snapshot = reader(LibrarySourceMode.TRAKT, WatchProgressSource.NUVIO_SYNC, traktLibrary, traktProgress)
            .read(1, followOtherServices = true)

        assertEquals(emptyMap<String, String>(), snapshot.notReady)
        assertEquals(listOf(CalendarOrigin.LIBRARY), snapshot.candidates.map { it.origin })
        assertTrue(snapshot.traktFollowed)
        verify(exactly = 1) { traktProgress.currentWatchedShowSeeds() }
    }
}
