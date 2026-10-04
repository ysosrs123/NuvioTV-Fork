package com.nuvio.tv.data.mediaserver

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerResumeAfterAccountPullTest {
    private val dir: File = Files.createTempDirectory("server-resume-").toFile()
    private val context = mockk<Context> {
        every { filesDir } returns dir
        every { applicationContext } returns this@mockk
    }
    private val factory = ProfileDataStoreFactory(context)
    private val profileManager = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
    private val preferences = WatchProgressPreferences(factory, profileManager)
    private val progressRepository = mockk<WatchProgressRepository>(relaxed = true) {
        coEvery { saveProgressBatch(any(), any<Int>(), any()) } coAnswers {
            preferences.saveProgressBatch(firstArg(), profileId = secondArg())
        }
    }
    private val provider = FakeServerProvider()
    private val server = fakeServerRepository(provider)
    private val connection = server.second
    private val projection = ServerUserStateProjection(
        catalog = mockk {
            every { detailsLoaded } returns MutableSharedFlow()
            every { libraryLoaded } returns MutableSharedFlow()
        },
        repository = server.first,
        watchProgressRepository = progressRepository,
        watchProgressPreferences = preferences,
        watchedItemsPreferences = mockk(relaxed = true),
        profileManager = profileManager,
        resumeImports = MemoryResumeImports(),
        clock = { 0L }
    )

    private val nuvioPosition = WatchProgress(
        contentId = IMDB, contentType = "movie", name = "Beverly Hills Cop", poster = null, backdrop = null, logo = null,
        videoId = IMDB, season = null, episode = null, episodeTitle = null,
        position = 2_226_000L, duration = 6_300_000L, lastWatched = NUVIO_LAST_WATCHED
    )

    @After
    fun cleanUp() = runBlocking {
        factory.clearProfileScopedData()
        dir.deleteRecursively()
        Unit
    }

    private suspend fun stored(contentId: String): WatchProgress? =
        preferences.getAllRawEntries(1).values.firstOrNull { it.contentId == contentId }

    private suspend fun tracked(): WatchProgress? = stored(IMDB)

    private suspend fun accountPull() =
        preferences.mergeRemoteEntries(mapOf(IMDB to nuvioPosition), profileId = 1)

    private fun serverHasNewerPosition() {
        server.first.setCatalogMetadata(connection.id, true)
        provider.indexedIds["3"] = TrackingExternalIds(imdb = IMDB)
        provider.resumeEntries += provider.resumeMovie(connection, "3", positionMs = 1_200_000L, lastPlayed = SERVER_LAST_PLAYED)
    }

    @Test
    fun aNewerServerPositionComesBackAfterTheAccountPullRestoresTheOlderOne() = runBlocking {
        preferences.saveProgress(nuvioPosition, 1)
        serverHasNewerPosition()

        projection.importResume(connection.id, force = true)
        assertEquals(1_200_000L, tracked()?.position)

        accountPull()
        assertEquals(2_226_000L, tracked()?.position)
        awaitNuvioSees(2_226_000L)

        projection.importResume(connection.id, force = true)
        assertEquals(1_200_000L to SERVER_LAST_PLAYED, tracked()?.let { it.position to it.lastWatched })
    }

    @Test
    fun theAccountPullItselfBringsTheNewerServerPositionBack() = runBlocking {
        preferences.saveProgress(nuvioPosition, 1)
        serverHasNewerPosition()
        projection.start()

        awaitTrackedPosition(1_200_000L)
        accountPull()
        awaitTrackedPosition(1_200_000L)
        assertEquals(SERVER_LAST_PLAYED, tracked()?.lastWatched)
    }

    @Test
    fun aTitleRemovedAfterItsImportStaysRemoved() = runBlocking {
        preferences.saveProgress(nuvioPosition, 1)
        serverHasNewerPosition()
        projection.importResume(connection.id, force = true)
        assertEquals(1_200_000L, tracked()?.position)
        preferences.removeProgress(IMDB, profileId = 1)

        projection.importResume(connection.id, force = true)

        assertEquals(null, tracked())
        assertEquals(null, stored(ServerItemRef(connection.id, "3").encode()))
    }

    private suspend fun awaitTrackedPosition(position: Long) = withTimeout(10_000L) {
        while (tracked()?.position != position || tracked()?.lastWatched != SERVER_LAST_PLAYED) delay(20L)
    }

    private suspend fun awaitNuvioSees(position: Long) = withTimeout(10_000L) {
        while (preferences.getProgress(IMDB, 1).first()?.position != position) delay(20L)
    }

    private class MemoryResumeImports : ServerResumeImports {
        private val values = mutableMapOf<String, Map<String, Long>>()
        override fun read(profileId: Int, connectionId: String): Map<String, Long> = values["$profileId:$connectionId"].orEmpty()
        override fun write(profileId: Int, connectionId: String, imported: Map<String, Long>) {
            values["$profileId:$connectionId"] = imported
        }
    }

    private companion object {
        const val IMDB = "tt0086960"
        const val NUVIO_LAST_WATCHED = 1_759_571_280_000L
        const val SERVER_LAST_PLAYED = 1_759_605_120_000L
    }
}
