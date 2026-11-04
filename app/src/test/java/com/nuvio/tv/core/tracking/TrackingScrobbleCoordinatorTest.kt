package com.nuvio.tv.core.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingScrobbleCoordinatorTest {
    @Test
    fun `fanout isolates a provider failure`() = runTest {
        val successful = FakeScrobbler(TrackingProviderId.TRAKT)
        val failing = FakeScrobbler(TrackingProviderId.SIMKL, IllegalStateException("offline"))
        val mdblist = FakeScrobbler(TrackingProviderId.MDBLIST)
        val event = TrackingScrobbleEvent(
            media = TrackingMediaReference(
                kind = TrackingMediaKind.MOVIE,
                ids = TrackingExternalIds(imdb = "tt0111161")
            ),
            progressPercent = 42.5
        )

        val failures = dispatchTrackingScrobble(
            scrobblers = listOf(successful, failing, mdblist),
            action = TrackingScrobbleAction.PAUSE,
            event = event
        )

        assertEquals(1, successful.callCount)
        assertEquals(1, failing.callCount)
        assertEquals(listOf(TrackingScrobbleAction.PAUSE), mdblist.actions)
        assertEquals(listOf(TrackingScrobbleAction.PAUSE), successful.actions)
        assertEquals(listOf(TrackingScrobbleAction.PAUSE), failing.actions)
        assertEquals(listOf(TrackingProviderId.SIMKL), failures.map { it.providerId })
    }

    @Test
    fun `services a media server already scrobbles to are left out`() = runTest {
        val trakt = FakeScrobbler(TrackingProviderId.TRAKT)
        val simkl = FakeScrobbler(TrackingProviderId.SIMKL)
        val event = TrackingScrobbleEvent(
            media = TrackingMediaReference(
                kind = TrackingMediaKind.MOVIE,
                ids = TrackingExternalIds(imdb = "tt0111161")
            ),
            progressPercent = 10.0,
            skippedProviders = setOf(TrackingProviderId.TRAKT)
        )

        dispatchTrackingScrobble(listOf(trakt, simkl), TrackingScrobbleAction.START, event)

        assertEquals(0, trakt.callCount)
        assertEquals(1, simkl.callCount)
    }

    @Test
    fun `a tracker cancelled from inside does not cancel the others`() = runTest {
        val log = mutableListOf<String>()
        val trakt = FakeScrobbler(TrackingProviderId.TRAKT, log = log, delayMs = 5_000L)
        val floppy = FakeScrobbler(TrackingProviderId.FLOPPY, CancellationException("account changed"), log = log)

        val failures = dispatchTrackingScrobble(listOf(trakt, floppy), TrackingScrobbleAction.STOP, event(80.0))

        assertEquals(listOf("FLOPPY stop", "TRAKT stop"), log)
        assertEquals(listOf(TrackingProviderId.FLOPPY), failures.map { it.providerId })
    }

    @Test
    fun `cancelling the caller still cancels the trackers`() = runTest {
        val log = mutableListOf<String>()
        val trakt = FakeScrobbler(TrackingProviderId.TRAKT, log = log, delayMs = 5_000L)
        val job = launch { dispatchTrackingScrobble(listOf(trakt), TrackingScrobbleAction.STOP, event(80.0)) }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `seek restart goes per tracker and only to trackers that restart`() = runTest {
        val log = mutableListOf<String>()
        val trakt = FakeScrobbler(TrackingProviderId.TRAKT, log = log, delayMs = 10_000L, seekPolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART)
        val floppy = FakeScrobbler(TrackingProviderId.FLOPPY, log = log, seekPolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART)
        val simkl = FakeScrobbler(TrackingProviderId.SIMKL, log = log)

        dispatchTrackingSeekRestart(listOf(trakt, floppy, simkl), event(40.0)) { event(41.0) }

        assertEquals(listOf("FLOPPY stop", "FLOPPY start", "TRAKT stop", "TRAKT start"), log)
    }

    @Test
    fun `seek restart skips the start when playback moved on`() = runTest {
        val log = mutableListOf<String>()
        val trakt = FakeScrobbler(TrackingProviderId.TRAKT, log = log, seekPolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART)
        val skipped = event(40.0).copy(skippedProviders = setOf(TrackingProviderId.FLOPPY))
        val floppy = FakeScrobbler(TrackingProviderId.FLOPPY, log = log, seekPolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART)

        dispatchTrackingSeekRestart(listOf(trakt, floppy), skipped) { null }

        assertEquals(listOf("TRAKT stop"), log)
    }

    private fun event(percent: Double) = TrackingScrobbleEvent(
        media = TrackingMediaReference(kind = TrackingMediaKind.MOVIE, ids = TrackingExternalIds(imdb = "tt0111161")),
        progressPercent = percent
    )

    private class FakeScrobbler(
        override val providerId: TrackingProviderId,
        private val failure: Throwable? = null,
        private val seekPolicy: TrackingSeekScrobblePolicy = TrackingSeekScrobblePolicy.NONE,
        private val log: MutableList<String>? = null,
        private val delayMs: Long = 0L
    ) : TrackingScrobbler {
        var callCount = 0
        val actions = mutableListOf<TrackingScrobbleAction>()
        override val seekScrobblePolicy = seekPolicy

        override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
            callCount += 1
            actions += action
            if (delayMs > 0L) delay(delayMs)
            log?.add("${providerId.name} ${action.wireValue}")
            failure?.let { throw it }
        }
    }
}
