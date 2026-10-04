package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.mediaserver.FakeServerProvider
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerPlayback
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServerUserState
import com.nuvio.tv.data.mediaserver.fakeServerRepository
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerResumeStartTest {
    private val provider = FakeServerProvider()
    private val repository: ServerRepository
    private val connectionId: String
    private val playback: ServerPlayback
    private var saved: WatchProgress? = null
    private val resumes = mutableListOf<WatchProgress?>()

    init {
        val (serverRepository, connection) = fakeServerRepository(provider)
        repository = serverRepository
        connectionId = connection.id
        playback = ServerPlayback(serverRepository, CoroutineScope(Dispatchers.Unconfined)) { 0L }
    }

    private val progressRepository = mockk<WatchProgressRepository> {
        every { getProgress("tt1", 1) } answers { flowOf(saved) }
    }

    private fun controller(streamUrl: String) = mockk<PlayerRuntimeController>(relaxed = true) {
        every { serverPlayback } returns playback
        every { currentStreamUrl } returns streamUrl
        every { contentId } returns "tt1"
        every { contentType } returns "movie"
        every { contentName } returns "Film"
        every { currentVideoId } returns "tt1"
        every { currentSeason } returns null
        every { currentEpisode } returns null
        every { profileId } returns 1
        every { watchProgressRepository } returns progressRepository
        every { pendingResumeProgress = captureNullable(resumes) } just Runs
    }

    private suspend fun serverStream(): String =
        playback.prepare(ServerPlaybackTarget(ServerItemRef(connectionId, "7"), "src-7")).url

    @Test
    fun startsFromTheServerPositionWhenItIsNewer() = runBlocking {
        provider.userStates["7"] = listOf(serverState(lastPlayed = 5_000L))
        saved = nuvioProgress(lastWatched = 1_000L)

        controller(serverStream()).loadSavedProgressSuspend(null, null)

        assertEquals(30_000L, resumes.last()?.position)
    }

    @Test
    fun keepsNuviosPositionWhenItIsNewer() = runBlocking {
        provider.userStates["7"] = listOf(serverState(lastPlayed = 5_000L))
        saved = nuvioProgress(lastWatched = 9_000L)

        controller(serverStream()).loadSavedProgressSuspend(null, null)

        assertEquals(12_000L, resumes.last()?.position)
    }

    @Test
    fun ignoresAServerItemMarkedPlayed() = runBlocking {
        provider.userStates["7"] = listOf(serverState(lastPlayed = 5_000L, played = true))
        saved = nuvioProgress(lastWatched = 1_000L)

        controller(serverStream()).loadSavedProgressSuspend(null, null)

        assertEquals(12_000L, resumes.last()?.position)
    }

    @Test
    fun resumesFromTheServerWithContinueWatchingImportOff() = runBlocking {
        repository.setImportContinueWatching(connectionId, false)
        provider.userStates["7"] = listOf(serverState(lastPlayed = 5_000L))

        controller(serverStream()).loadSavedProgressSuspend(null, null)

        val resume = resumes.last()
        assertEquals(30_000L, resume?.position)
        assertEquals("tt1", resume?.contentId)
    }

    @Test
    fun aSlowServerFallsBackToNuviosPosition() = runTest {
        provider.userStates["7"] = listOf(serverState(lastPlayed = 5_000L))
        provider.detailsDelayMs = 10_000L
        saved = nuvioProgress(lastWatched = 1_000L)

        controller(serverStream()).loadSavedProgressSuspend(null, null)

        assertEquals(12_000L, resumes.last()?.position)
        assertEquals(1_500L, testScheduler.currentTime)
    }

    @Test
    fun otherStreamsNeverAskTheServer() = runBlocking {
        provider.userStates["7"] = listOf(serverState(lastPlayed = 5_000L))
        saved = nuvioProgress(lastWatched = 1_000L)

        controller("https://debrid.example/film.mkv").loadSavedProgressSuspend(null, null)

        assertEquals(12_000L, resumes.last()?.position)
        assertEquals(0, provider.detailsRequests)
    }

    @Test
    fun noProgressAnywhereLeavesTheStartAtZero() = runBlocking {
        controller(serverStream()).loadSavedProgressSuspend(null, null)

        assertNull(resumes.last())
    }

    private fun serverState(lastPlayed: Long?, played: Boolean = false) =
        ServerUserState("v7", positionMs = 30_000L, durationMs = 60_000L, played = played, lastPlayedEpochMs = lastPlayed)

    private fun nuvioProgress(lastWatched: Long) =
        WatchProgress("tt1", "movie", "Film", null, null, null, "tt1", null, null, null, 12_000L, 60_000L, lastWatched = lastWatched)
}
