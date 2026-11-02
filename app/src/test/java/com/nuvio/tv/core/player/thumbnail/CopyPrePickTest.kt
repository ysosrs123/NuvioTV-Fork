package com.nuvio.tv.core.player.thumbnail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyPrePickTest {
    private val ownMs = 7_200_000L
    private val gb = 1_000_000_000L

    private fun offer(
        id: String,
        label: String = id,
        kind: CopyKind = CopyKind.LINK,
        height: Int = 1080,
        size: Long? = 4 * gb,
        durationMs: Long? = null,
        text: String = "Film.2019.1080p.WEB-DL.H264-GRP",
        remote: Boolean = false,
    ) = CopyOffer(id, label, kind, height, CopyCodec.H264, false, true, size, "mkv", durationMs, text, remote, "AIOStreams")

    private fun offers(playingText: String, playing: CopyOffer?, vararg o: CopyOffer) =
        CopyOffers(playingText, o.toList(), playing = playing)

    private class Counted {
        val givenBack = ArrayList<String>()
    }

    private fun slot(clock: () -> Long, c: Counted) = PrePickSlot<String>(clock) { t, _ -> c.givenBack += t }

    @Test
    fun theListOpensACopyOnlyWhenThumbnailsBeforePlayWillRun() {
        fun starts(
            prepare: ThumbPrepareMode = ThumbPrepareMode.ALWAYS, mode: SeekThumbMode = SeekThumbMode.ALL,
            exact: Boolean = true, exo: Boolean = true, shown: Boolean = true, picked: Boolean = false,
        ) = CopyPrePick.starts(prepare, mode, exact, exo, shown, picked)
        assertTrue(starts())
        assertFalse(starts(prepare = ThumbPrepareMode.OFF))
        assertFalse(starts(mode = SeekThumbMode.OFF))
        assertFalse(starts(exact = false))
        assertFalse(starts(exo = false))
        assertFalse(starts(shown = false))
        assertFalse(starts(picked = true))
    }

    @Test
    fun theFirst4KStreamOfTheListIsTakenAsTheOneThatPlays() {
        val list = listOf(
            offer("1080"),
            offer("usenet", kind = CopyKind.USENET, height = 2160),
            offer("away", kind = CopyKind.SERVER, height = 2160, remote = true),
            offer("4k", kind = CopyKind.DEBRID, height = 2160, size = 60 * gb),
            offer("4k-b", kind = CopyKind.DEBRID, height = 2160, size = 50 * gb),
        )
        assertEquals("4k", CopyPrePick.assumedPlaying(list)?.id)
        assertNull(CopyPrePick.assumedPlaying(listOf(offer("1080"), offer("720", height = 720))))
    }

    @Test
    fun torrentsUsenetAndAwayServersNeverTakeTheCopy() {
        assertTrue(CopyPrePick.pickUsable(onList = true, torrent = false, usenet = false, awayServer = false))
        assertFalse(CopyPrePick.pickUsable(onList = false, torrent = false, usenet = false, awayServer = false))
        assertFalse(CopyPrePick.pickUsable(onList = true, torrent = true, usenet = false, awayServer = false))
        assertFalse(CopyPrePick.pickUsable(onList = true, torrent = false, usenet = true, awayServer = false))
        assertFalse(CopyPrePick.pickUsable(onList = true, torrent = false, usenet = false, awayServer = true))
    }

    @Test
    fun theCopyIsHandedOverOnlyWhenItStillFitsTheStreamPicked() {
        val copy = offer("720", kind = CopyKind.TORBOX_ADD, height = 720, size = 3 * gb)
        val uhd = "Film.2019.2160p.UHD.BluRay.REMUX.HDR.HEVC-GRP"
        val assumed = offer("4k", kind = CopyKind.DEBRID, height = 2160, size = 60 * gb, durationMs = ownMs)
        assertTrue(CopyPrePick.fits(copy, CopyFirst.headStartVerdicts(offers(uhd, assumed, copy), null)))
        // Another 4K stream picked: the same rules, the copy still fits.
        val other4k = offer("4k-b", kind = CopyKind.LINK, height = 2160, size = 50 * gb, durationMs = ownMs)
        assertTrue(CopyPrePick.fits(copy, CopyFirst.headStartVerdicts(offers(uhd, other4k, copy), null)))
        // A 1080p stream picked: the copy must be clearly lighter than it.
        val big1080 = offer("1080", height = 1080, size = 12 * gb, durationMs = ownMs)
        assertTrue(CopyPrePick.fits(copy, CopyFirst.headStartVerdicts(offers("Film.2019.1080p.BluRay", big1080, copy), null)))
        val small1080 = offer("1080s", height = 1080, size = 4 * gb, durationMs = ownMs)
        assertFalse(CopyPrePick.fits(copy, CopyFirst.headStartVerdicts(offers("Film.2019.1080p.WEB", small1080, copy), null)))
        // Another cut picked: the copy of the theatrical cut no longer fits.
        assertFalse(CopyPrePick.fits(copy,
            CopyFirst.headStartVerdicts(offers("Film.2019.Extended.2160p.UHD.BluRay", assumed, copy), null)))
        // The copy itself picked: it is not on the list of copies any more.
        assertFalse(CopyPrePick.fits(copy, CopyFirst.headStartVerdicts(offers("Film.2019.720p", copy, assumed), null)))
        assertFalse(CopyPrePick.fits(null, CopyFirst.headStartVerdicts(offers(uhd, assumed, copy), null)))
    }

    @Test
    fun aCopyNobodyTookIsGivenBackInTime() {
        assertFalse(CopyPrePick.expired(heldAtMs = 0L, pickedAtMs = null, nowMs = CopyPrePick.HOLD_MAX_MS - 1))
        assertTrue(CopyPrePick.expired(heldAtMs = 0L, pickedAtMs = null, nowMs = CopyPrePick.HOLD_MAX_MS))
        assertFalse(CopyPrePick.expired(heldAtMs = 0L, pickedAtMs = 1_000L, nowMs = 1_000L + CopyPrePick.TAKE_WINDOW_MS - 1))
        assertTrue(CopyPrePick.expired(heldAtMs = 0L, pickedAtMs = 1_000L, nowMs = 1_000L + CopyPrePick.TAKE_WINDOW_MS))
    }

    @Test
    fun anAddedTorBoxCopyStaysOnlyWhenThePickMayReadIt() {
        val copy = offer("720", kind = CopyKind.TORBOX_ADD, height = 720, size = 3 * gb)
        val film = offer("4k", height = 2160, size = 60 * gb)
        assertFalse(CopyPrePick.keepsAdded(picked = false, pickedHash = null, pickedOffer = null, copy = copy))
        assertFalse(CopyPrePick.keepsAdded(picked = true, pickedHash = "abc", pickedOffer = copy, copy = copy))
        assertFalse(CopyPrePick.keepsAdded(picked = true, pickedHash = null, pickedOffer = film, copy = copy))
        assertTrue(CopyPrePick.keepsAdded(picked = true, pickedHash = null, pickedOffer = null, copy = copy))
        assertTrue(CopyPrePick.keepsAdded(picked = true, pickedHash = null,
            pickedOffer = offer("other", label = "Torrentio: 720p", height = 720, size = 3 * gb), copy = copy))
    }

    @Test
    fun onlyOneIsHeldAndItIsTakenOnceAfterThePick() {
        var now = 0L
        val c = Counted()
        val s = slot({ now }, c)
        s.hold("tt1", "a")
        s.hold("tt2", "b")
        assertEquals(listOf("a"), c.givenBack)
        assertEquals("tt2", s.heldFor)
        assertNull(s.picked("tt1"))
        assertEquals(listOf("a", "b"), c.givenBack)
        assertNull(s.heldFor)

        s.hold("tt3", "c")
        now = 500L
        assertEquals("c", s.picked("tt3"))
        assertEquals("c", s.take("tt3"))
        assertNull(s.take("tt3"))
        assertEquals(listOf("a", "b"), c.givenBack)
    }

    @Test
    fun withoutAPickOrForAnotherTitleTheThumbnailsGetNothingAndItIsGivenBack() {
        val c = Counted()
        val s = slot({ 0L }, c)
        s.hold("tt1", "a")
        assertNull(s.take("tt1"))
        assertEquals(listOf("a"), c.givenBack)
        s.hold("tt1", "b")
        assertEquals("b", s.picked("tt1"))
        assertNull(s.take("tt2"))
        assertEquals(listOf("a", "b"), c.givenBack)
    }

    @Test
    fun leavingTheListGivesItBackUnlessAStreamWasPicked() {
        var now = 0L
        val c = Counted()
        val s = slot({ now }, c)
        s.hold("tt1", "a")
        s.leave("tt2")
        assertTrue(c.givenBack.isEmpty())
        s.leave("tt1")
        assertEquals(listOf("a"), c.givenBack)

        s.hold("tt1", "b")
        now = 1_000L
        s.picked("tt1")
        s.leave("tt1")
        assertEquals(listOf("a"), c.givenBack)
        now = 1_000L + CopyPrePick.TAKE_WINDOW_MS
        s.expire()
        assertEquals(listOf("a", "b"), c.givenBack)
        assertNull(s.heldFor)
    }

    @Test
    fun aHeldCopyIsGivenBackAfterItsTimeAndDropOrReleaseEndIt() {
        var now = 0L
        val c = Counted()
        val s = slot({ now }, c)
        s.hold("tt1", "a")
        now = CopyPrePick.HOLD_MAX_MS - 1
        s.expire()
        assertTrue(c.givenBack.isEmpty())
        now = CopyPrePick.HOLD_MAX_MS
        s.expire()
        assertEquals(listOf("a"), c.givenBack)
        s.hold("tt1", "b")
        s.drop("tt2")
        s.drop("tt1")
        assertEquals(listOf("a", "b"), c.givenBack)
        s.hold("tt1", "c")
        s.release("c", "test")
        assertNull(s.heldFor)
        assertEquals(listOf("a", "b", "c"), c.givenBack)
    }

    private class Sources(private val list: CopyOffers?, private val links: Map<String, CopyLink?>) : CopySources {
        val opened = ArrayList<String>()
        override suspend fun offers(): CopyOffers? = list
        override suspend fun open(offer: CopyOffer): CopyLink? {
            opened += offer.id
            return links[offer.id]
        }
    }

    @Test
    fun aCopyOpenedBeforeThePickIsFoundOnThePlayersOwnList() = runBlocking {
        val uhd = "Film.2019.2160p.UHD.BluRay.REMUX.HDR.HEVC-GRP"
        val assumed = offer("s0", kind = CopyKind.DEBRID, height = 2160, size = 60 * gb, durationMs = ownMs)
        val top = offer("s3", label = "TorBox: 720p", kind = CopyKind.TORBOX_ADD, height = 720, size = 3 * gb)
        var done = 0
        val link = CopyLink("https://x/y", emptyMap(), "a TorBox copy added for thumbnails") { done++ }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val head = CopyHeadStart("tt1", pickedAtMs = 0L, beforePick = true)
            head.start(scope, Sources(offers(uhd, assumed, top), mapOf("s3" to link)), null, null)
            assertSame(top, head.chosenOffer())
            val picked = offers(uhd, offer("s1", kind = CopyKind.LINK, height = 2160, size = 55 * gb, durationMs = ownMs), top)
            head.repicked(5_000L, picked)
            assertEquals(5_000L, head.pickedAtMs)
            assertSame(picked, head.listed)
            // The player numbers its sources otherwise: the same listing is the same copy.
            val onPlayerList = offer("s7", label = "TorBox: 720p", kind = CopyKind.DEBRID, height = 720, size = 3 * gb)
            assertTrue(head.owns(onPlayerList))
            assertNull(head.take(offer("s3", label = "Other: 720p", height = 720, size = 3 * gb)))
            assertSame(link, head.take(onPlayerList))
            assertNull(head.take(onPlayerList))
            head.release()
            assertEquals(0, done)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun atThePickTheHeadStartStillMatchesOnlyItsOwnEntry() = runBlocking {
        val uhd = "Film.2019.2160p.UHD.BluRay.REMUX.HDR.HEVC-GRP"
        val playing = offer("p", kind = CopyKind.DEBRID, height = 2160, size = 60 * gb, durationMs = ownMs)
        val top = offer("720", kind = CopyKind.TORBOX_ADD, height = 720, size = 3 * gb)
        val link = CopyLink("https://x/y", emptyMap(), "a TorBox copy added for thumbnails")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val head = CopyHeadStart("tt1", pickedAtMs = 0L)
            head.start(scope, Sources(offers(uhd, playing, top), mapOf("720" to link)), null, null)
            assertNull(head.take(offer("other", label = "720", height = 720, size = 3 * gb)))
            assertSame(link, head.take(top))
        } finally {
            scope.cancel()
        }
    }
}
