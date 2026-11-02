package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Requests in flight per server: playback never waits, thumbnails only start while there is room. */
class ConnectionLedgerTest {
    private var now = 1_000_000L
    private var wallNow = 50L * 24 * 60 * 60 * 1000
    private fun ledger() = ConnectionLedger(clock = { now }, wallClock = { wallNow })

    private class FakeMemory(initial: Map<String, String> = emptyMap()) : ConnectionLedger.Memory {
        val saved = HashMap(initial)
        val forgotten = HashSet<String>()
        override fun load(): Map<String, String> = HashMap(saved)
        override fun save(key: String, value: String) {
            saved[key] = value
        }
        override fun forget(keys: Collection<String>) {
            forgotten += keys
            keys.forEach { saved.remove(it) }
        }
    }

    private val tb = "https://store-031.weur.tb-cdn.st/dl/abc"
    private val tb2 = "https://nexus-114.japn.tb-cdn.pw/dl/xyz"
    private val jelly = "http://192.168.1.10:8096/Videos/1/stream.mkv"
    private val cdn = "https://cdn.example.com/film.mkv"

    private fun noSlot(l: ConnectionLedger, url: String): Boolean = try {
        l.acquire(url, maxWaitMs = 0).close()
        false
    } catch (_: ConnectionLedger.NoSlotException) {
        true
    }

    /** One playback connection: each lone thumbnail fetch uses what it has, so it counts toward a step up. */
    private fun cleanFetches(l: ConnectionLedger, url: String, n: Int = ConnectionBudget.CLEAN_PER_STEP) {
        repeat(n) {
            now += 10
            l.acquire(url, maxWaitMs = 0).run { succeeded(); close() }
        }
    }

    @Test fun inFlightNoteCountsPlaybackAndThumbnailsApart() {
        val l = ledger()
        assertEquals("in use playback 0, thumbnails 0", l.inFlightNote(jelly))
        val p = l.playback(jelly)
        val t = l.acquire(jelly, maxWaitMs = 0)
        assertEquals("in use playback 1, thumbnails 1", l.inFlightNote(jelly))
        t.close()
        p?.close()
        assertEquals("in use playback 0, thumbnails 0", l.inFlightNote(jelly))
        assertEquals("not counted", l.inFlightNote(null))
    }

    @Test fun torboxHostsShareOneKey() {
        assertEquals(LedgerKey.TORBOX, LedgerKey.of(tb))
        assertEquals(LedgerKey.TORBOX, LedgerKey.of(tb2))
        assertEquals(LedgerKey.TORBOX, LedgerKey.of("https://api.torbox.app/v1/api/torrents/requestdl?x=1"))
        assertEquals(LedgerKey.TORBOX, LedgerKey.of("https://torrentio.strem.fun/resolve/torbox/key/hash/1/file.mkv"))
    }

    @Test fun otherServersAreKeyedByHostAndPort() {
        assertEquals("192.168.1.10:8096", LedgerKey.of(jelly))
        assertEquals("192.168.1.10:8920", LedgerKey.of("https://192.168.1.10:8920/Videos/2/stream"))
        assertEquals("stremthru.example.xyz", LedgerKey.of("https://stremthru.example.xyz/v0/store/link/x"))
        assertEquals("notb-cdn.example.com", LedgerKey.of("https://notb-cdn.example.com/dl/abc"))
        assertEquals("cdn.example.com", LedgerKey.of("https://user:pw@CDN.Example.com/a?b#c"))
        assertNull(LedgerKey.of(null))
        assertNull(LedgerKey.of("not a url"))
    }

    @Test fun playbackNeverWaitsEvenAboveTheBudget() {
        val l = ledger()
        val slots = (1..6).map { l.playback(tb) }
        assertEquals(6, l.inFlight(tb2))
        slots.forEach { it?.close() }
        assertEquals(0, l.inFlight(tb))
    }

    @Test fun torboxSharesEightAcrossAllItsHostsWithPlaybackReserved() {
        val l = ledger()
        l.notePlaybackConnections(2)
        assertEquals(6, l.thumbnailCap(tb))
        val p1 = l.playback(tb)
        val p2 = l.playback(tb2)
        val held = (1..4).map { l.acquire(if (it % 2 == 0) tb else tb2, maxWaitMs = 0) }
        assertTrue(noSlot(l, tb))
        assertEquals(6, l.inFlight(tb))
        p1?.close()
        // Playback never waits: its whole setting stays reserved while it plays.
        assertTrue(noSlot(l, tb))
        held.forEach { it.close() }
        p2?.close()
    }

    @Test fun torboxNeverGoesAboveEightWithPlayback() {
        val l = ledger()
        l.notePlaybackConnections(6)
        val playback = (1..6).map { l.playback(tb) }
        val t = (1..2).map { l.acquire(tb2, maxWaitMs = 0) }
        assertEquals(8, l.inFlight(tb))
        assertTrue(noSlot(l, tb))
        t.forEach { it.close() }
        playback.forEach { it?.close() }
    }

    @Test fun torboxThumbnailsFollowPlaybacksLiveDownloadCount() {
        val l = ledger()
        l.notePlaybackConnections(2)
        l.notePlaybackDownloads(tb, 5)
        assertEquals(3, l.thumbnailRoom(tb))
        val playback = (1..5).map { l.playback(tb) }
        val t = (1..3).map { l.acquire(tb, maxWaitMs = 0) }
        assertTrue(noSlot(l, tb))
        playback.drop(3).forEach { it?.close() }
        l.notePlaybackDownloads(tb2, 3)
        assertEquals(4, l.thumbnailRoom(tb))
        val fourth = l.acquire(tb, maxWaitMs = 0)
        assertTrue(noSlot(l, tb))
        assertEquals(4, l.playbackRoom(tb, 5))
        (t + fourth).forEach { it.close() }
        playback.take(3).forEach { it?.close() }
    }

    @Test fun aNewPlaybackForgetsTheLiveDownloadCount() {
        val l = ledger()
        l.notePlaybackConnections(2)
        l.notePlaybackDownloads(tb, 6)
        assertEquals(2, l.thumbnailRoom(tb))
        l.notePlaybackConnections(2)
        assertEquals(4, l.thumbnailRoom(tb))
    }

    @Test fun thumbnailRoomDoesNotJumpWithGapsBetweenPlaybackDownloads() {
        val l = ledger()
        l.notePlaybackConnections(1)
        val p = l.playback(jelly)
        assertEquals(1, l.thumbnailRoom(jelly))
        p?.close()
        assertEquals(1, l.thumbnailRoom(jelly))
        l.notePlaybackState(healthy = true, quiet = true)
        assertEquals(2, l.thumbnailRoom(jelly))
        assertEquals(ConnectionLedger.UNLIMITED, l.thumbnailRoom(null))
    }

    @Test fun playbackRoomIsWhatTheTotalLeavesNextToThumbnails() {
        val l = ledger()
        assertEquals(5, l.playbackRoom(tb, 5))
        val t = (1..4).map { l.acquire(tb, maxWaitMs = 0) }
        assertEquals(4, l.playbackRoom(tb2, 5))
        assertEquals(3, l.playbackRoom(tb, 3))
        t.forEach { it.close() }
        assertEquals(8, l.playbackRoom(tb, 9))
    }

    @Test fun aNextEpisodeStartingEarlyCountsAgainstPlaybacksRoom() {
        val l = ledger()
        // Six playback requests on the account, four of them the playing stream's: two belong to another stream.
        val playback = (1..6).map { l.playback(tb) }
        assertEquals(6, l.playbackRoom(tb, 8, own = 4))
        assertEquals(8, l.playbackRoom(tb, 8))
        val t = l.acquire(tb, maxWaitMs = 0)
        assertEquals(5, l.playbackRoom(tb, 8, own = 4))
        // The stream's own requests never count twice.
        assertEquals(7, l.playbackRoom(tb, 8, own = 6))
        t.close()
        playback.forEach { it?.close() }
    }

    @Test fun playbackRoomAsksForAtLeastOne() {
        val l = ledger()
        l.notePlaybackConnections(1)
        l.noteYield(true)
        val t = (1..5).map { l.acquire(tb, maxWaitMs = 0) }
        assertEquals(3, l.playbackRoom(tb, 5))
        assertEquals(1, l.playbackRoom(tb, 0))
        t.forEach { it.close() }
    }

    @Test fun playbackRoomIsWhatWasWantedWithoutAKnownTotal() {
        val l = ledger()
        val t = (1..2).map { l.acquire(jelly, maxWaitMs = 0) }
        assertEquals(6, l.playbackRoom(jelly, 6))
        assertEquals(6, l.playbackRoom(null, 6))
        assertEquals(1, l.playbackRoom(jelly, -2))
        t.forEach { it.close() }
    }

    @Test fun torboxStepsBackWhenItSaysTooManyAndKeepsNothing() {
        val memory = FakeMemory()
        val l = ledger()
        l.attach(memory)
        l.notePlaybackConnections(1)
        assertEquals(5, l.thumbnailCap(tb))
        val s = l.acquire(tb2, maxWaitMs = 0)
        now += 100
        s.failed(RateLimitedException(null))
        s.close()
        assertEquals(4, l.thumbnailCap(tb))
        assertNull(memory.saved[LedgerKey.TORBOX])
    }

    @Test fun anOldLearnedTorBoxLevelIsNotUsed() {
        val l = ledger()
        l.attach(FakeMemory(mapOf(LedgerKey.TORBOX to "0,1,$wallNow")))
        l.notePlaybackConnections(4)
        assertEquals(8, l.thumbnailCap(tb))
    }

    @Test fun tooManyFromPlaybackOrAThumbnailQuietsTheSameServer() {
        val l = ledger()
        l.notePlaybackConnections(5)
        assertEquals(0L, l.refusalQuietMs(tb))
        assertFalse(l.refusedLately(tb))
        val p = l.playback(tb)
        now += 100
        p?.failed(RateLimitedException(null))
        p?.close()
        assertEquals(120_000L, ConnectionLedger.REFUSAL_QUIET_MS)
        assertEquals(ConnectionLedger.REFUSAL_QUIET_MS, l.refusalQuietMs(tb2))
        assertTrue(l.refusedLately(tb))
        assertEquals(0L, l.refusalQuietMs(jelly))
        now += ConnectionLedger.REFUSAL_QUIET_MS - 1_000
        assertEquals(1_000L, l.refusalQuietMs(tb))
        val t = l.acquire(tb, maxWaitMs = 0)
        now += 500
        t.failed(RateLimitedException(null))
        t.close()
        assertEquals(ConnectionLedger.REFUSAL_QUIET_MS, l.refusalQuietMs(tb))
        now += ConnectionLedger.REFUSAL_QUIET_MS
        assertEquals(0L, l.refusalQuietMs(tb))
        assertTrue(l.refusedLately(tb))
        now += ConnectionLedger.REFUSED_LATELY_MS
        assertFalse(l.refusedLately(tb))
    }

    @Test fun playbacksTooManyStepsTheThumbnailsBack() {
        val l = ledger()
        l.notePlaybackConnections(2)
        assertEquals(6, l.thumbnailCap(tb))
        val p = l.playback(tb)
        now += 100
        p?.failed(RateLimitedException(null))
        p?.close()
        assertEquals(5, l.thumbnailCap(tb))
        val other = l.playback(jelly)
        other?.failed(ConnectException("refused"))
        other?.close()
        assertEquals(3, l.thumbnailCap(jelly))
    }

    @Test fun onlyTooManyStartsTheQuiet() {
        val l = ledger()
        val t = l.acquire(jelly, maxWaitMs = 0)
        t.failed(RateLimitedException(null, 503))
        t.close()
        assertEquals(0L, l.refusalQuietMs(jelly))
        assertFalse(l.refusedLately(jelly))
    }

    @Test fun anUnknownServerStartsOneAbovePlayback() {
        val l = ledger()
        l.notePlaybackConnections(2)
        assertEquals(3, l.thumbnailCap(jelly))
        l.notePlaybackConnections(1)
        assertEquals(2, l.thumbnailCap(jelly))
        assertEquals(ConnectionLedger.UNLIMITED, l.thumbnailCap(null))
    }

    @Test fun alwaysOneAboveLivePlayback() {
        val l = ledger()
        l.notePlaybackConnections(1)
        repeat(5) { l.playback(jelly) }
        val t = l.acquire(jelly, maxWaitMs = 0)
        assertTrue(noSlot(l, jelly))
        t.close()
    }

    @Test fun playbackPeakCountsUntilTheNextPlayback() {
        val l = ledger()
        l.notePlaybackConnections(1)
        val ps = (1..3).map { l.playback(jelly) }
        ps.forEach { it?.close() }
        assertEquals(4, l.thumbnailCap(jelly))
        l.notePlaybackConnections(1)
        assertEquals(2, l.thumbnailCap(jelly))
    }

    @Test fun closingTwiceFreesOnce() {
        val l = ledger()
        val a = l.playback(jelly)
        val b = l.playback(jelly)
        a?.close()
        a?.close()
        assertEquals(1, l.inFlight(jelly))
        b?.close()
    }

    @Test fun aRedirectMovesTheRequestToTheServerThatAnswers() {
        val l = ledger()
        val s = l.playback("https://addon.example.com/resolve/realdebrid/x")
        s?.moveTo(tb)
        assertEquals(0, l.inFlight("https://addon.example.com/"))
        assertEquals(1, l.inFlight(tb))
        s?.close()
        assertEquals(0, l.inFlight(tb))
    }

    @Test fun aRedirectedThumbnailDoesNotCountTowardAStepUp() {
        val l = ledger()
        l.notePlaybackConnections(1)
        l.notePlaybackState(healthy = true, quiet = false)
        repeat(ConnectionBudget.CLEAN_PER_STEP * 2) {
            now += 10
            l.acquire("https://addon.example.com/x", maxWaitMs = 0).run { moveTo(jelly); succeeded(); close() }
        }
        assertEquals(2, l.thumbnailCap(jelly))
    }

    @Test fun aCancelledWaitEnds() {
        val l = ledger()
        l.notePlaybackConnections(4)
        repeat(4) { l.playback(jelly) }
        l.acquire(jelly, maxWaitMs = 0)
        try {
            l.acquire(jelly, maxWaitMs = 60_000L) { true }
            fail("should not wait")
        } catch (e: ConnectionLedger.NoSlotException) {
            fail("cancelled, not timed out")
        } catch (_: IOException) {
        }
    }

    @Test fun aWaitingThumbnailStartsWhenPlaybackFinishesARequest() {
        val l = ConnectionLedger()
        l.notePlaybackConnections(4)
        val playback = (1..4).map { l.playback(jelly) }
        l.acquire(jelly, maxWaitMs = 0)
        val got = AtomicReference<ConnectionLedger.Slot?>()
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val t = Thread {
            started.countDown()
            got.set(runCatching { l.acquire(jelly, maxWaitMs = 10_000L) }.getOrNull())
            done.countDown()
        }
        t.isDaemon = true
        t.start()
        started.await()
        assertFalse(done.await(300, TimeUnit.MILLISECONDS))
        playback[0]?.close()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNotNull(got.get())
        assertEquals(5, l.inFlight(jelly))
    }

    @Test fun busyCleanFetchesWhilePlaybackIsHealthyStepUp() {
        val l = ledger()
        l.notePlaybackConnections(1)
        l.notePlaybackState(healthy = true, quiet = false)
        cleanFetches(l, jelly)
        assertEquals(3, l.thumbnailCap(jelly))
    }

    @Test fun fetchesWithRoomToSpareDoNotStepUp() {
        val l = ledger()
        l.notePlaybackConnections(3)
        l.notePlaybackState(healthy = true, quiet = false)
        cleanFetches(l, jelly, ConnectionBudget.CLEAN_PER_STEP * 3)
        assertEquals(4, l.thumbnailCap(jelly))
    }

    @Test fun healthGoesStale() {
        val l = ledger()
        l.notePlaybackConnections(1)
        l.notePlaybackState(healthy = true, quiet = false)
        now += ConnectionLedger.HEALTH_TTL_MS + 1
        cleanFetches(l, jelly, ConnectionBudget.CLEAN_PER_STEP * 2)
        assertEquals(2, l.thumbnailCap(jelly))
    }

    @Test fun aTooManySoonAfterAStepUpIsKeptRelativeToPlayback() {
        val memory = FakeMemory()
        val l = ledger()
        l.attach(memory)
        l.notePlaybackConnections(1)
        l.notePlaybackState(healthy = true, quiet = false)
        cleanFetches(l, jelly)
        assertEquals(3, l.thumbnailCap(jelly))
        val s = l.acquire(jelly, maxWaitMs = 0)
        now += 100
        s.failed(RateLimitedException(null))
        s.close()
        assertEquals(2, l.thumbnailCap(jelly))
        assertEquals(ConnectionBudget.Remembered(1, true, wallNow),
            ConnectionMemory.decode(memory.saved["192.168.1.10:8096"], wallNow))

        val next = ledger()
        next.attach(FakeMemory(memory.saved))
        next.notePlaybackConnections(4)
        assertEquals(5, next.thumbnailCap(jelly))
    }

    @Test fun aRefusalAtTheStartingLevelIsNotSaved() {
        val memory = FakeMemory()
        val l = ledger()
        l.attach(memory)
        l.notePlaybackConnections(1)
        val s = l.acquire(jelly, maxWaitMs = 0)
        s.failed(RateLimitedException(null))
        s.close()
        l.thumbnailCap(jelly)
        assertTrue(memory.saved.isEmpty())
    }

    @Test fun playbackTurnedAwaySoonAfterAStepUpStepsDown() {
        val l = ledger()
        l.notePlaybackConnections(1)
        l.notePlaybackState(healthy = true, quiet = false)
        cleanFetches(l, jelly)
        assertEquals(3, l.thumbnailCap(jelly))
        now += 5_000
        val p = l.playback(jelly)
        p?.failed(ConnectException("refused"))
        p?.close()
        assertEquals(2, l.thumbnailCap(jelly))
    }

    @Test fun aStallSoonAfterAStepUpStepsDownThePlayingServerOnly() {
        val l = ledger()
        l.notePlaybackConnections(1)
        l.notePlaybackState(healthy = true, quiet = false)
        cleanFetches(l, jelly)
        cleanFetches(l, cdn)
        l.notePlayingUrl(cdn)
        assertEquals(3, l.thumbnailCap(cdn))
        l.playbackTrouble(now + 1_000)
        assertEquals(3, l.thumbnailCap(jelly))
        assertEquals(2, l.thumbnailCap(cdn))
        l.playbackTrouble(now + 1_000, jelly)
        assertEquals(2, l.thumbnailCap(jelly))
        assertEquals(2, l.thumbnailCap(cdn))
    }

    @Test fun troubleRightAfterTheYieldEndsIsIgnored() {
        val l = ledger()
        l.notePlaybackConnections(1)
        l.notePlaybackState(healthy = true, quiet = false)
        cleanFetches(l, jelly)
        l.noteYield(true)
        now += 100
        l.noteYield(false)
        now += ConnectionLedger.AFTER_YIELD_QUIET_MS - 1_000
        l.playbackTrouble(now, jelly)
        assertEquals(3, l.thumbnailCap(jelly))
        now += 2_000
        l.playbackTrouble(now, jelly)
        assertEquals(2, l.thumbnailCap(jelly))
    }

    @Test fun anIdleStreamDoesNotCountWhileYielding() {
        val l = ledger()
        l.notePlaybackConnections(1)
        val stream = l.playback(jelly, stream = true)
        val first = l.acquire(jelly, maxWaitMs = 0)
        assertTrue(noSlot(l, jelly))
        l.noteYield(true)
        val second = l.acquire(jelly, maxWaitMs = 0)
        assertEquals(3, l.inFlight(jelly))
        listOf(first, second).forEach { it.close() }
        stream?.close()

        l.noteYield(false)
        l.notePlaybackConnections(3)
        val tbStream = l.playback(tb, stream = true)
        l.noteYield(true)
        val held = (1..7).map { l.acquire(tb, maxWaitMs = 0) }
        assertTrue(noSlot(l, tb))
        held.forEach { it.close() }
        tbStream?.close()
    }

    @Test fun theGeneratingScreenStartsTwoAbovePlayback() {
        val l = ledger()
        l.notePlaybackConnections(2)
        assertEquals(3, l.thumbnailCap(jelly))
        l.noteYield(true)
        assertEquals(4, l.thumbnailCap(jelly))
        now += PlaybackYield.LEASE_MS
        assertEquals(3, l.thumbnailCap(jelly))
    }

    @Test fun oldEntriesAreForgottenOnAttach() {
        val day = 24L * 60 * 60 * 1000
        val memory = FakeMemory(mapOf(
            "old.example.com" to ConnectionMemory.encode(ConnectionBudget.Remembered(4, false, wallNow - 31 * day)),
            "new.example.com" to ConnectionMemory.encode(ConnectionBudget.Remembered(4, false, wallNow - day)),
            "bad.example.com" to "garbage",
        ))
        val l = ledger()
        l.attach(memory)
        assertEquals(setOf("old.example.com", "bad.example.com"), memory.forgotten)
        l.notePlaybackConnections(2)
        assertEquals(6, l.thumbnailCap("https://new.example.com/f.mkv"))
        assertEquals(3, l.thumbnailCap("https://old.example.com/f.mkv"))
    }

    @Test fun refusalsAreSorted() {
        assertEquals(Refusal.TOO_MANY, ConnectionLedger.refusalOf(RateLimitedException(null)))
        assertEquals(Refusal.UNAVAILABLE, ConnectionLedger.refusalOf(RateLimitedException(null, 503)))
        assertEquals(Refusal.CONNECTION, ConnectionLedger.refusalOf(IOException("chunk", ConnectException("Connection refused"))))
        assertEquals(Refusal.CONNECTION, ConnectionLedger.refusalOf(SocketException("Connection reset")))
        assertNull(ConnectionLedger.refusalOf(IOException("Cancelled")))
        assertNull(ConnectionLedger.refusalOf(SocketException("Socket closed")))
        assertNull(ConnectionLedger.refusalOf(null))
        assertTrue(ConnectionLedger.saysTooMany("Too many active streams"))
        assertFalse(ConnectionLedger.saysTooMany("Forbidden"))
    }

    @Test fun healthyMeansPausedOrWellBuffered() {
        assertTrue(ConnectionLedger.healthy(playing = false, buffering = false, aheadMs = 0, toEnd = false))
        assertTrue(ConnectionLedger.healthy(playing = true, buffering = false, aheadMs = 30_000, toEnd = false))
        assertTrue(ConnectionLedger.healthy(playing = true, buffering = false, aheadMs = 2_000, toEnd = true))
        assertFalse(ConnectionLedger.healthy(playing = true, buffering = false, aheadMs = 29_000, toEnd = false))
        assertFalse(ConnectionLedger.healthy(playing = false, buffering = true, aheadMs = 60_000, toEnd = false))
    }

    @Test fun onlyIoPlayerErrorsCountAsServerTrouble() {
        assertTrue(ConnectionLedger.sourceTrouble(2001))
        assertTrue(ConnectionLedger.sourceTrouble(2004))
        assertFalse(ConnectionLedger.sourceTrouble(5001))
        assertFalse(ConnectionLedger.sourceTrouble(4001))
        assertFalse(ConnectionLedger.sourceTrouble(null))
    }

    @Test fun aCappedServerNeverCarriesMoreThumbnailRequestsWhoeverAsks() {
        val l = ledger()
        val away = "https://silo.friend.example.com/api/v2/stream/abc"
        val other = "https://silo.friend.example.com/Videos/2/stream?static=true"
        assertTrue(l.thumbnailCap(away) > 1)
        l.capThumbnails(away, 1)
        assertEquals(1, l.thumbnailCap(away))
        assertEquals(1, l.thumbnailRoom(other))
        val seekSpot = l.acquire(away, maxWaitMs = 0)
        assertTrue(noSlot(l, other))
        seekSpot.close()
        val copyRead = l.acquire(other, maxWaitMs = 0)
        assertTrue(noSlot(l, away))
        copyRead.close()
        l.capThumbnails(away, 4)
        assertEquals(1, l.thumbnailCap(away))
        assertTrue(l.thumbnailCap(cdn) > 1)
    }

    @Test fun nothingPlayingLeavesTorBoxRoomUntilPlaybackOpensARequest() {
        val l = ledger()
        l.notePlaybackConnections(8)
        assertTrue(noSlot(l, tb))
        l.noteNothingPlays(true)
        assertFalse(noSlot(l, tb))
        val p = l.playback(tb)
        assertTrue(noSlot(l, tb))
        p?.close()
        l.noteNothingPlays(false)
        assertTrue(noSlot(l, tb))
    }

    @Test fun nothingPlayingLapsesAfterItsLease() {
        val l = ledger()
        l.notePlaybackConnections(8)
        l.noteNothingPlays(true)
        now += ConnectionLedger.IDLE_LEASE_MS
        assertTrue(noSlot(l, tb))
    }
}
