package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPlaybackTest {
    private val metaRepository = mockk<MetaRepository> {
        every { getCachedMeta(any(), any()) } returns null
    }
    private var now = 0L

    private class Harness(
        val provider: FakeServerProvider,
        val repository: ServerRepository,
        val connection: ServerConnection,
        val streams: ServerStreams,
        val playback: ServerPlayback
    )

    private fun harness(provider: FakeServerProvider = FakeServerProvider()): Harness {
        val (repository, connection) = fakeServerRepository(provider)
        val matcher = ServerMatcher(repository, mockk<TmdbService>(relaxed = true), metaRepository)
        return Harness(
            provider = provider,
            repository = repository,
            connection = connection,
            streams = ServerStreams(repository, matcher),
            playback = ServerPlayback(repository, CoroutineScope(Dispatchers.Unconfined)) { now }
        )
    }

    @Test
    fun fetchesMarkersOncePerFileVersion() = runBlocking {
        val provider = FakeServerProvider().apply {
            segments = listOf(ServerSegment(ServerSegmentKind.INTRO, 30_000L, 90_000L))
        }
        val harness = harness(provider)
        val target = ServerPlaybackTarget(ServerItemRef(harness.connection.id, "42"), "src-42")
        val session = harness.playback.prepare(target)

        assertEquals(provider.segments, harness.playback.segments(session.url))
        assertEquals(provider.segments, harness.playback.segments(session.url))
        assertEquals(listOf("42" to "src-42"), provider.segmentRequests)
        assertTrue(harness.playback.segments("https://elsewhere.example/file.mkv").isEmpty())
    }

    @Test
    fun namesTheProviderOfAnActiveServerStreamOnly() = runBlocking {
        val harness = harness()
        val target = ServerPlaybackTarget(ServerItemRef(harness.connection.id, "42"), "src-42")
        val session = harness.playback.prepare(target)

        assertEquals("Fake", harness.playback.providerName(session.url))
        assertEquals(null, harness.playback.providerName("https://elsewhere.example/file.mkv"))
        assertEquals(null, harness.playback.providerName(null))
    }

    @Test
    fun nativeRequestsProduceOneAttributedSource() = runBlocking {
        val harness = harness()
        val videoId = ServerItemRef(harness.connection.id, "42").encode()

        val source = harness.streams.sources("movie", videoId, null, null).single()
        val stream = source.load().single()

        assertEquals("Fake · Box", source.name)
        assertTrue(source.preferred)
        assertEquals("Fake · Box", stream.addonName)
        assertEquals(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "42"), "src-42"), stream.serverTarget)
        assertNull(stream.getStreamUrl())
        assertFalse(stream.isTorrent())
        assertEquals("item-42.mkv", stream.behaviorHints?.filename)
    }

    @Test
    fun serverItemsAndMatchableCatalogTitlesArePlayable() {
        val harness = harness()
        val streams = harness.streams
        assertTrue(streams.canServe("movie", ServerItemRef(harness.connection.id, "42").encode()))
        assertTrue(streams.canServe("movie", "tt0111161"))
        assertTrue(streams.canServe("series", "tt0944947:1:1"))
        assertFalse(streams.canServe("movie", "someaddon:1"))
        assertFalse(streams.canServe("movie", ServerItemRef("cmissing", "42").encode()))

        harness.repository.setEnabled(harness.connection.id, false)
        assertFalse(streams.canServe("movie", ServerItemRef(harness.connection.id, "42").encode()))
        assertFalse(streams.canServe("movie", "tt0111161"))
    }

    @Test
    fun catalogMetadataListsServerFilesFirst() {
        val harness = harness()
        assertTrue(harness.streams.preferredSourceNames("movie", "tt0111161").isEmpty())

        harness.repository.setCatalogMetadata(harness.connection.id, true)
        val preferred = harness.streams.preferredSourceNames("movie", "tt0111161")
        assertEquals(setOf("Fake · Box"), preferred)

        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            streams = listOf(AddonStreams("Addon", null, emptyList()), AddonStreams("Fake · Box", null, emptyList())),
            installedOrder = listOf("Addon"),
            preferredNames = preferred
        )
        assertEquals(listOf("Fake · Box", "Addon"), ordered.map { it.addonName })
    }

    @Test
    fun autoplayCanChooseServerCandidates() = runBlocking {
        val harness = harness()
        val stream = harness.streams.candidates(ServerItemRef(harness.connection.id, "42")).single()

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(stream),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
            installedAddonNames = emptySet(),
            selectedAddons = emptySet(),
            selectedPlugins = setOf("Some plugin")
        )
        assertEquals(stream, selected)
    }

    @Test
    fun reportsLifecycleInOrderAndStopsOnce() = runBlocking {
        val harness = harness()
        val playback = harness.playback
        val session = playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "42"), "src-42"))
        val url = session.url
        assertTrue(playback.isServerSource(url))

        playback.onPlaybackSnapshot(url, 0L, isPlaying = false, isLoading = true, isEnded = false)
        playback.onPlaybackSnapshot(url, 0L, isPlaying = true, isLoading = false, isEnded = false)
        now += 1_000L
        playback.onPlaybackSnapshot(url, 1_000L, isPlaying = true, isLoading = false, isEnded = false)
        now += 11_000L
        playback.onPlaybackSnapshot(url, 12_000L, isPlaying = true, isLoading = false, isEnded = false)
        playback.onPlaybackSnapshot(url, 12_000L, isPlaying = false, isLoading = false, isEnded = false)
        playback.onPlaybackSnapshot(url, 12_000L, isPlaying = true, isLoading = false, isEnded = false)
        playback.onPlaybackSnapshot(url, 13_000L, isPlaying = false, isLoading = false, isEnded = true)
        playback.stop(url)

        assertEquals(
            listOf(
                ServerPlaybackEventType.START,
                ServerPlaybackEventType.PROGRESS,
                ServerPlaybackEventType.PAUSE,
                ServerPlaybackEventType.RESUME,
                ServerPlaybackEventType.STOP
            ),
            harness.provider.reported.toList()
        )
        assertFalse(playback.isServerSource(url))
    }

    @Test
    fun preparingAnotherSourceDropsUnstartedSessions() = runBlocking {
        val harness = harness()
        val first = harness.playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "1"), "src-1"))
        val second = harness.playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "2"), "src-2"))

        assertFalse(harness.playback.isServerSource(first.url))
        assertTrue(harness.playback.isServerSource(second.url))
    }

    @Test
    fun aSessionThatNeverStartedReportsNothing() = runBlocking {
        val harness = harness()
        val playback = harness.playback
        val first = playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "1"), "src-1"))
        playback.onPlaybackSnapshot(first.url, 0L, isPlaying = false, isLoading = true, isEnded = false)
        val second = playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "2"), "src-2"))
        playback.onPlaybackSnapshot(second.url, 0L, isPlaying = false, isLoading = true, isEnded = false)
        playback.stop(second.url)

        assertFalse(playback.isServerSource(second.url))
        assertTrue(harness.provider.reported.isEmpty())
    }

    @Test
    fun aStopWhileLoadingKeepsTheLastPlayedPosition() = runBlocking {
        val harness = harness()
        val playback = harness.playback
        val url = playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "42"), "src-42")).url
        playback.onPlaybackSnapshot(url, 60_000L, isPlaying = true, isLoading = false, isEnded = false)
        playback.onPlaybackSnapshot(url, 0L, isPlaying = false, isLoading = true, isEnded = false)
        playback.stop(url)

        assertEquals(ServerPlaybackEventType.STOP, harness.provider.reported.last())
        assertEquals(60_000L, harness.provider.reportedPositions.last())
    }

    @Test
    fun directPlayOnlyServersAreNeverAskedForATranscode() = runBlocking {
        val provider = FakeServerProvider()
        val harness = harness(provider)
        val session = harness.playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "7"), "src-7"))

        assertFalse(harness.playback.canFallback(session.url))
        assertNull(harness.playback.fallback(session.url))
        assertTrue(provider.playbackRequests.single().capabilities.allowDirectPlay)
        assertTrue(harness.playback.isServerSource(session.url))
        assertTrue(provider.reported.isEmpty())
    }

    @Test
    fun offeredTranscodeReplacesTheFailedDirectSession() = runBlocking {
        val provider = FakeServerProvider(offersTranscode = true)
        val harness = harness(provider)
        val playback = harness.playback
        val direct = playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "7"), "src-7"))
        playback.onPlaybackSnapshot(direct.url, 0L, isPlaying = true, isLoading = false, isEnded = false)
        playback.onPlaybackSnapshot(direct.url, 90_000L, isPlaying = true, isLoading = false, isEnded = false)

        assertTrue(playback.canFallback(direct.url))
        val transcode = playback.fallback(direct.url)!!

        assertEquals(ServerPlayMethod.TRANSCODE, transcode.playMethod)
        assertEquals(direct.target, transcode.target)
        assertFalse(provider.playbackRequests.last().capabilities.allowDirectPlay)
        assertFalse(playback.isServerSource(direct.url))
        assertTrue(playback.isServerSource(transcode.url))
        assertFalse(playback.canFallback(transcode.url))
        assertEquals(ServerPlaybackEventType.STOP, provider.reported.last())

        playback.onPlaybackSnapshot(transcode.url, 90_000L, isPlaying = true, isLoading = false, isEnded = false)
        assertEquals(ServerPlaybackEventType.START, provider.reported.last())
    }

    @Test
    fun refusedTranscodeIsAskedOnlyOnceAndKeepsTheDirectSession() = runBlocking {
        listOf(ServerFailure.UNSUPPORTED, ServerFailure.FORBIDDEN, ServerFailure.UNREACHABLE).forEach { failure ->
            val provider = FakeServerProvider(offersTranscode = true, transcodeFailure = failure)
            val harness = harness(provider)
            val session = harness.playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "7"), "src-7"))

            assertTrue(harness.playback.canFallback(session.url))
            assertNull(harness.playback.fallback(session.url))
            assertFalse(harness.playback.canFallback(session.url))
            assertNull(harness.playback.fallback(session.url))

            assertEquals(2, provider.playbackRequests.size)
            assertTrue(harness.playback.isServerSource(session.url))
            assertFalse(ServerPlaybackEventType.STOP in provider.reported)
        }
    }

    @Test
    fun aLaterStartTriesDirectPlayAgain() = runBlocking {
        val provider = FakeServerProvider(offersTranscode = true)
        val harness = harness(provider)
        val target = ServerPlaybackTarget(ServerItemRef(harness.connection.id, "7"), "src-7")
        val direct = harness.playback.prepare(target)
        val transcode = harness.playback.fallback(direct.url)!!
        harness.playback.stop(transcode.url)

        val again = harness.playback.prepare(target)

        assertEquals(ServerPlayMethod.DIRECT_PLAY, again.playMethod)
        assertTrue(provider.playbackRequests.last().capabilities.allowDirectPlay)
        assertTrue(harness.playback.canFallback(again.url))
    }

    @Test
    fun firstRequestAllowsDirectPlay() = runBlocking {
        val provider = FakeServerProvider()
        val harness = harness(provider)

        harness.playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "7"), "src-7"))

        assertTrue(provider.playbackRequests.single().capabilities.allowDirectPlay)
    }

    @Test
    fun switchesTranscodeAudioByRestartingTheSession() = runBlocking {
        val provider = FakeServerProvider(transcodes = true)
        val harness = harness(provider)
        val playback = harness.playback
        val session = playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "42"), "src-42"))
        assertFalse(playback.canFallback(session.url))
        assertEquals(listOf(1 to true, 2 to false), playback.audioTracks(session.url).map { it.index to it.selected })
        playback.onPlaybackSnapshot(session.url, 5_000L, isPlaying = true, isLoading = false, isEnded = false)

        val switched = playback.switchAudio(session.url, 2)!!

        val request = provider.playbackRequests.last()
        assertEquals(2, request.audioStreamIndex)
        assertFalse(request.capabilities.allowDirectPlay)
        assertFalse(playback.isServerSource(session.url))
        assertTrue(playback.isServerSource(switched.url))
        assertEquals(listOf(1 to false, 2 to true), playback.audioTracks(switched.url).map { it.index to it.selected })
        assertTrue(ServerPlaybackEventType.STOP in provider.reported)
    }

    @Test
    fun burnsInSubtitlesByRestartingTheTranscode() = runBlocking {
        val provider = FakeServerProvider(transcodes = true)
        val harness = harness(provider)
        val playback = harness.playback
        val session = playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "42"), "src-42"))
        val japanese = playback.switchAudio(session.url, 2)!!

        val burnedIn = playback.switchSubtitle(japanese.url, 3)!!

        val request = provider.playbackRequests.last()
        assertEquals(3, request.subtitleStreamIndex)
        assertEquals(2, request.audioStreamIndex)
        assertFalse(request.capabilities.allowDirectPlay)
        assertEquals(listOf(3), playback.burnInSubtitles(burnedIn.url).filter { it.selected }.map { it.index })

        val english = playback.switchAudio(burnedIn.url, 1)!!
        assertEquals(3, provider.playbackRequests.last().subtitleStreamIndex)

        val cleared = playback.switchSubtitle(english.url, null)!!
        assertNull(provider.playbackRequests.last().subtitleStreamIndex)
        assertTrue(playback.burnInSubtitles(cleared.url).none { it.selected })
    }

    @Test
    fun directPlayLeavesTracksToThePlayer() = runBlocking {
        val harness = harness()
        val url = harness.playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "7"), "src-7")).url

        assertTrue(harness.playback.audioTracks(url).isEmpty())
        assertTrue(harness.playback.burnInSubtitles(url).isEmpty())
        assertNull(harness.playback.switchAudio(url, 2))
        assertNull(harness.playback.switchSubtitle(url, 3))
        assertTrue(harness.playback.isServerSource(url))
    }

    private suspend fun Harness.withAlternate(away: String) {
        provider.locations[away] = ServerLocation(away, "server-1")
        assertEquals(AlternateAddressResult.SAVED, repository.setAlternateAddress(connection.id, away))
    }

    @Test
    fun transcodeFallbackMovesToTheSecondAddress() = runBlocking {
        val provider = FakeServerProvider(offersTranscode = true)
        val harness = harness(provider)
        val home = harness.connection.address
        val away = "https://away.example"
        harness.withAlternate(away)
        val direct = harness.playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "7"), "src-7"))
        provider.downAddresses += home

        val transcode = harness.playback.fallback(direct.url)!!

        assertEquals(ServerPlayMethod.TRANSCODE, transcode.playMethod)
        assertEquals(listOf(home, home, away), provider.playbackAddresses)
        assertEquals(away, harness.repository.session(harness.connection.id)?.address)
        assertNull(harness.repository.uiState.value.failures[harness.connection.id])

        harness.playback.onPlaybackSnapshot(transcode.url, 0L, isPlaying = true, isLoading = false, isEnded = false)
        assertEquals(away, provider.reportAddresses.last())
    }

    @Test
    fun aFailedRestartIsRecordedAsAServerFailure() = runBlocking {
        val provider = FakeServerProvider(transcodes = true)
        val harness = harness(provider)
        val session = harness.playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "42"), "src-42"))
        provider.downAddresses += harness.connection.address

        assertNull(harness.playback.switchAudio(session.url, 2))
        assertTrue(harness.playback.isServerSource(session.url))
        assertEquals(ServerFailure.UNREACHABLE, harness.repository.uiState.value.failures[harness.connection.id])
    }

    @Test
    fun reportsFollowAnAddressSwitchDuringPlayback() = runBlocking {
        val provider = FakeServerProvider()
        val harness = harness(provider)
        val home = harness.connection.address
        val away = "https://away.example"
        harness.withAlternate(away)
        val session = harness.playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "7"), "src-7"))
        val url = session.url
        harness.playback.onPlaybackSnapshot(url, 0L, isPlaying = true, isLoading = false, isEnded = false)

        provider.downAddresses += home
        harness.repository.call(harness.connection.id) { p, current -> p.libraries(current) }
        now += 11_000L
        harness.playback.onPlaybackSnapshot(url, 11_000L, isPlaying = true, isLoading = false, isEnded = false)
        harness.playback.stop(url)

        assertEquals(listOf(home, away, away), provider.reportAddresses.toList())
    }

    @Test
    fun readsTheServerResumePointForThePlayingItem() = runBlocking {
        val provider = FakeServerProvider().apply { userStates["7"] = listOf(serverState()) }
        val harness = harness(provider)
        val url = harness.playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "7"), "src-7")).url

        assertEquals(30_000L, harness.playback.resumeState(url)?.positionMs)
        assertNull(harness.playback.resumeState("https://other.example/7"))
        assertEquals(1, provider.detailsRequests)
    }

    @Test
    fun readsTheResumePointWithContinueWatchingImportOff() = runBlocking {
        val provider = FakeServerProvider().apply { userStates["7"] = listOf(serverState()) }
        val harness = harness(provider)
        harness.repository.setImportContinueWatching(harness.connection.id, false)
        val url = harness.playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "7"), "src-7")).url

        assertEquals(30_000L, harness.playback.resumeState(url)?.positionMs)
    }

    @Test
    fun aSlowServerGivesUpOnTheResumePointAfterTheTimeout() = runTest {
        val provider = FakeServerProvider().apply {
            userStates["7"] = listOf(serverState())
            detailsDelayMs = 10_000L
        }
        val harness = harness(provider)
        val url = harness.playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "7"), "src-7")).url

        assertNull(harness.playback.resumeState(url))
        assertEquals(1_500L, testScheduler.currentTime)
    }

    @Test
    fun aFailingServerGivesNoResumePoint() = runBlocking {
        val provider = FakeServerProvider().apply {
            userStates["7"] = listOf(serverState())
            failingDetails = true
        }
        val harness = harness(provider)
        val url = harness.playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "7"), "src-7")).url

        assertNull(harness.playback.resumeState(url))
    }

    @Test
    fun aNewerServerPositionWinsOverNuviosProgress() {
        val chosen = newerServerResume(nuvioProgress(10_000L, lastWatched = 1_000L), serverState(lastPlayed = 5_000L)) { null }

        assertEquals(30_000L, chosen?.position)
        assertEquals(5_000L, chosen?.lastWatched)
        assertEquals("tt1", chosen?.contentId)
        assertNull(chosen?.progressPercent)
    }

    @Test
    fun newerNuvioProgressWinsOverTheServer() {
        val saved = nuvioProgress(10_000L, lastWatched = 9_000L)

        assertSame(saved, newerServerResume(saved, serverState(lastPlayed = 5_000L)) { null })
        assertSame(saved, newerServerResume(saved, serverState(lastPlayed = 9_000L)) { null })
    }

    @Test
    fun aPlayedOrUndatedServerItemKeepsNuviosProgress() {
        val saved = nuvioProgress(10_000L, lastWatched = 1_000L)

        assertSame(saved, newerServerResume(saved, serverState(played = true)) { null })
        assertSame(saved, newerServerResume(saved, serverState(lastPlayed = null)) { null })
        assertSame(saved, newerServerResume(saved, serverState(positionMs = 0L)) { null })
        assertSame(saved, newerServerResume(saved, null) { null })
    }

    @Test
    fun theServerPositionFillsATitleNuvioHasNoProgressFor() {
        val chosen = newerServerResume(null, serverState()) { nuvioProgress(0L, lastWatched = 0L) }

        assertEquals(30_000L, chosen?.position)
        assertEquals(60_000L, chosen?.duration)
        assertNull(newerServerResume(null, serverState(played = true)) { nuvioProgress(0L, lastWatched = 0L) })
    }

    private fun serverState(positionMs: Long = 30_000L, lastPlayed: Long? = 5_000L, played: Boolean = false) =
        ServerUserState("v7", positionMs = positionMs, durationMs = 60_000L, played = played, lastPlayedEpochMs = lastPlayed)

    private fun nuvioProgress(positionMs: Long, lastWatched: Long) =
        WatchProgress("tt1", "movie", "Film", null, null, null, "tt1", null, null, null, positionMs, 60_000L, lastWatched = lastWatched)
}
