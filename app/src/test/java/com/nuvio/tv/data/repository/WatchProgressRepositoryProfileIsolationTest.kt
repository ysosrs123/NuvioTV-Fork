package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchStateMutationStore
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingHistoryWriterRegistry
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.ProfileStoreLifetime
import com.nuvio.tv.domain.model.WatchedItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async

class WatchProgressRepositoryProfileIsolationTest {
    @Test
    fun `explicit save writes and queues only its playback profile`() = runTest {
        val harness = harness(activeProfileId = 2)
        val progress = progress("item", position = 2_000L, duration = 10_000L)

        harness.repository.saveProgress(progress, profileId = 1, syncRemote = true)

        coVerify(exactly = 1) {
            harness.mutationStore.queueProgressUpserts(mapOf("item" to progress), 1)
        }
        coVerify(exactly = 1) {
            harness.progressPreferences.saveProgress(progress, profileId = 1)
        }
        coVerify(exactly = 0) {
            harness.progressPreferences.saveProgress(progress, profileId = 2)
        }
    }

    @Test
    fun `completion queues progress and watched mutations for one profile`() = runTest {
        val harness = harness(activeProfileId = 2)
        val progress = progress("item", position = 2_000L, duration = 10_000L)

        harness.repository.markAsCompleted(
            progress = progress,
            profileId = 1,
            broadcastTrackingHistory = false
        )

        coVerify(exactly = 1) {
            harness.mutationStore.queueProgressUpserts(
                match { entries ->
                    entries.keys == setOf("item") &&
                        entries.getValue("item").position == 10_000L &&
                        entries.getValue("item").duration == 10_000L
                },
                1
            )
        }
        coVerify(exactly = 1) {
            harness.mutationStore.queueWatchedUpserts(
                match { items -> items.single().contentId == "item" },
                1
            )
        }
        coVerify(exactly = 1) {
            harness.watchedPreferences.markAsWatched(
                match { it.contentId == "item" },
                profileId = 1,
                generation = any()
            )
        }
    }

    @Test
    fun `profile switch during delete key lookup cannot redirect local deletion`() = runTest {
        val harness = harness(activeProfileId = 1)
        val progress = progress("item", position = 2_000L, duration = 10_000L)
        coEvery { harness.progressPreferences.getAllRawEntries(1) } coAnswers {
            harness.activeProfile.value = 2
            mapOf("item" to progress)
        }

        harness.repository.removeProgress("item")

        coVerify(exactly = 1) {
            harness.mutationStore.queueProgressDeletes(listOf("item"), 1)
        }
        coVerify(exactly = 1) {
            harness.progressPreferences.removeProgress("item", null, null, 1)
        }
        coVerify(exactly = 0) {
            harness.progressPreferences.removeProgress("item", null, null, 2)
        }
    }

    @Test
    fun `completion suspended across clear history cannot restore an old watched item`() = runTest {
        val lifetime = ProfileStoreLifetime(TestPreferencesStore())
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns lifetime
        every { factory.isProfileDeleted(any()) } returns false
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns MutableStateFlow(1)
        val watched = WatchedItemsPreferences(factory, manager)
        val harness = harness(1, watched)
        val admitted = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        coEvery { harness.progressPreferences.saveProgress(any(), 1) } coAnswers {
            admitted.complete(Unit); finish.await()
        }
        val completion = async { harness.repository.saveProgress(progress("old", 10000L, 10000L), profileId = 1, syncRemote = false) }
        admitted.await()
        watched.clearAll(1)
        val current = WatchedItem("new", "movie", "fixture", watchedAt = 1)
        watched.markAsWatched(current, 1)
        finish.complete(Unit)
        try { completion.await(); fail("old completion generation admitted") }
        catch (_: CancellationException) { }
        assertEquals(listOf(current), watched.getAllItems(1))
        lifetime.retire()
    }

    @Test fun `quiet metadata batch stays on its explicit profile without remote mutations`() = runTest {
        val harness=harness(activeProfileId=2)
        val items=listOf(progress("one",2000L,10000L),progress("two",3000L,10000L))
        harness.repository.saveProgressBatch(items,profileId=1,syncRemote=false)
        coVerify(exactly=1) { harness.progressPreferences.saveProgressBatch(items,profileId=1) }
        coVerify(exactly=0) { harness.progressPreferences.saveProgressBatch(any(),profileId=2) }
        coVerify(exactly=0) { harness.mutationStore.queueProgressUpserts(any(),any()) }
        coVerify(exactly=0) { harness.mutationStore.queueWatchedUpserts(any(),any()) }
    }

    @Test fun `explicit batch retains progress and completion ownership across a suspended queue`() = runTest {
        val harness=harness(activeProfileId=2)
        val items=listOf(progress("one",10000L,10000L),progress("two",3000L,10000L))
        coEvery { harness.mutationStore.queueProgressUpserts(any(),1) } coAnswers { harness.activeProfile.value=3 }
        harness.repository.saveProgressBatch(items,profileId=1,syncRemote=true)
        coVerify(exactly=1) { harness.progressPreferences.saveProgressBatch(items,profileId=1) }
        coVerify(exactly=1) { harness.mutationStore.queueProgressUpserts(mapOf("one" to items[0],"two" to items[1]),1) }
        coVerify(exactly=1) { harness.watchedPreferences.markAsWatchedBatch(match { it.single().contentId=="one" },1,any()) }
        coVerify(exactly=1) { harness.mutationStore.queueWatchedUpserts(match { it.single().contentId=="one" },1) }
        coVerify(exactly=0) { harness.progressPreferences.saveProgressBatch(any(),profileId=3) }
    }

    @Test fun `legacy batch captures active profile before a suspended queue`() = runTest {
        val harness=harness(activeProfileId=1)
        val items=listOf(progress("one",2000L,10000L))
        coEvery { harness.mutationStore.queueProgressUpserts(any(),1) } coAnswers { harness.activeProfile.value=2 }
        harness.repository.saveProgressBatch(items,syncRemote=true)
        coVerify(exactly=1) { harness.progressPreferences.saveProgressBatch(items,profileId=1) }
        coVerify(exactly=0) { harness.progressPreferences.saveProgressBatch(any(),profileId=2) }
    }

    private fun harness(activeProfileId: Int, watched: WatchedItemsPreferences? = null): Harness {
        val activeProfile = MutableStateFlow(activeProfileId)
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfile

        val progressPreferences = mockk<WatchProgressPreferences>(relaxed = true)
        coEvery { progressPreferences.getAllRawEntries(any()) } returns emptyMap()
        val watchedPreferences = watched ?: mockk<WatchedItemsPreferences>(relaxed = true)
        val mutationStore = mockk<WatchStateMutationStore>(relaxed = true)
        val authManager = mockk<AuthManager>(relaxed = true)
        every { authManager.isAuthenticated } returns false
        val traktSettings = mockk<TraktSettingsDataStore>(relaxed = true)
        every { traktSettings.watchProgressSource } returns MutableStateFlow(WatchProgressSource.NUVIO_SYNC)

        val repository = WatchProgressRepositoryImpl(
            watchProgressPreferences = progressPreferences,
            traktSettingsDataStore = traktSettings,
            layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true),
            watchProgressSyncService = mockk<WatchProgressSyncService>(relaxed = true),
            watchedItemsPreferences = watchedPreferences,
            watchedItemsSyncService = mockk<WatchedItemsSyncService>(relaxed = true),
            authManager = authManager,
            metaRepository = mockk<MetaRepository>(relaxed = true),
            tmdbService = mockk<TmdbService>(relaxed = true),
            profileManager = profileManager,
            trackingProgressProviders = TrackingProgressProviderRegistry(emptySet()),
            trackingHistoryWriters = TrackingHistoryWriterRegistry(emptySet()),
            mutationStore = mutationStore,
            serverWatched = mockk(relaxed = true)
        )
        return Harness(
            repository = repository,
            activeProfile = activeProfile,
            progressPreferences = progressPreferences,
            watchedPreferences = watchedPreferences,
            mutationStore = mutationStore
        )
    }

    private fun progress(contentId: String, position: Long, duration: Long) = WatchProgress(
        contentId = contentId,
        contentType = "movie",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = position,
        duration = duration,
        lastWatched = 100L
    )

    private data class Harness(
        val repository: WatchProgressRepositoryImpl,
        val activeProfile: MutableStateFlow<Int>,
        val progressPreferences: WatchProgressPreferences,
        val watchedPreferences: WatchedItemsPreferences,
        val mutationStore: WatchStateMutationStore
    )
}
