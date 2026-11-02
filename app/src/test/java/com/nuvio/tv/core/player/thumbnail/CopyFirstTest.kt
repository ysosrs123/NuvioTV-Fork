package com.nuvio.tv.core.player.thumbnail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyFirstTest {
    private val ownMs = 7_200_000L
    private val gb = 1_000_000_000L

    private fun offer(
        id: String,
        kind: CopyKind = CopyKind.LINK,
        height: Int = 1080,
        codec: CopyCodec = CopyCodec.H264,
        web: Boolean = true,
        size: Long? = 4 * gb,
        durationMs: Long? = null,
        text: String = "Film.2019.1080p.WEB-DL.H264-GRP",
        remote: Boolean = false,
        source: String = "AIOStreams",
    ) = CopyOffer(id, id, kind, height, codec, false, web, size, "mkv", durationMs, text, remote, source)

    private fun offers(playing: CopyOffer?, vararg o: CopyOffer) =
        CopyOffers("Film.2019.2160p.UHD.BluRay.REMUX.HDR.HEVC-GRP", o.toList(), playing = playing)

    @Test
    fun aCopyOfATitleThatIsNot4KMustBeClearlyLighter() {
        assertNull(CopyFirst.notLighter(offer("web", size = 6 * gb), 30 * gb, ownMs))
        assertNull(CopyFirst.notLighter(offer("edge", size = 18 * gb), 30 * gb, ownMs))
        assertNotNull(CopyFirst.notLighter(offer("same", size = 25 * gb), 30 * gb, ownMs))
        assertNotNull(CopyFirst.notLighter(offer("nosize", size = null), 30 * gb, ownMs))
        assertNotNull(CopyFirst.notLighter(offer("noown", size = 2 * gb), null, ownMs))
        // A copy twice as long carries twice the bytes for the same bitrate.
        assertNotNull(CopyFirst.notLighter(offer("long", size = 15 * gb, durationMs = ownMs / 2), 30 * gb, ownMs))
        assertNull(CopyFirst.notLighter(offer("short", size = 9 * gb, durationMs = ownMs), 30 * gb, null))
    }

    @Test
    fun theScreenWaitsForTheLinkTwentySecondsThenForTheCheckFortySeconds() {
        assertTrue(CopyFirst.screenWaits(ended = false, linkOpen = false, confirmed = false, sincePickMs = 19_999))
        assertFalse(CopyFirst.screenWaits(ended = false, linkOpen = false, confirmed = false, sincePickMs = 20_000))
        assertTrue(CopyFirst.screenWaits(ended = false, linkOpen = true, confirmed = false, sincePickMs = 39_999))
        assertFalse(CopyFirst.screenWaits(ended = false, linkOpen = true, confirmed = false, sincePickMs = 40_000))
        assertFalse(CopyFirst.screenWaits(ended = false, linkOpen = true, confirmed = true, sincePickMs = 1_000))
        assertFalse(CopyFirst.screenWaits(ended = true, linkOpen = false, confirmed = false, sincePickMs = 1_000))
        assertTrue(CopyFirst.screenWaits(ended = false, linkOpen = false, confirmed = false, sincePickMs = 0))
    }

    @Test
    fun withoutItsLinkTheCopyFallsBackTwentySecondsAfterThePick() {
        assertFalse(CopyFirst.fallsBack(linkOpen = false, sincePickMs = 19_999))
        assertTrue(CopyFirst.fallsBack(linkOpen = false, sincePickMs = 20_000))
        assertFalse(CopyFirst.fallsBack(linkOpen = true, sincePickMs = 60_000))
    }

    @Test
    fun duringPlayTheCopyStartsAtTheFirstFrameAndBehindTheScreenAtOnce() {
        assertFalse(CopyFirst.startsNow(screenUp = false, firstFrameShown = false))
        assertTrue(CopyFirst.startsNow(screenUp = false, firstFrameShown = true))
        assertTrue(CopyFirst.startsNow(screenUp = true, firstFrameShown = false))
    }

    @Test
    fun theCopyNeverTakesMoreThanFourRequests() {
        assertEquals(4, CopyFirst.MAX_LANES)
        assertEquals(4, UsenetCopy.fetches(screenUp = true, playing = false, serverCap = 8, smallBox = false, rateLimited = false))
        assertEquals(2, UsenetCopy.fetches(screenUp = true, playing = false, serverCap = 2, smallBox = false, rateLimited = false))
    }

    @Test
    fun theHeadStartPicksThe720pCopyFirstThen1080pForA4KTitle() {
        val playing = offer("p", kind = CopyKind.DEBRID, height = 2160, size = 60 * gb, durationMs = ownMs)
        val list = offers(playing,
            offer("1080", kind = CopyKind.TORBOX_ADD, size = 8 * gb),
            offer("720", kind = CopyKind.TORBOX_ADD, height = 720, size = 3 * gb),
            offer("4k", kind = CopyKind.DEBRID, height = 2160, size = 50 * gb),
        )
        assertEquals("720", CopyFirst.headStartPick(list, null)?.id)
        val no720 = offers(playing, offer("1080", kind = CopyKind.TORBOX_ADD, size = 8 * gb),
            offer("4k", kind = CopyKind.DEBRID, height = 2160, size = 50 * gb))
        assertEquals("1080", CopyFirst.headStartPick(no720, null)?.id)
        // A 4K copy from TorBox while the film itself plays from TorBox: no gain, the film makes its own.
        assertNull(CopyFirst.headStartPick(offers(playing, offer("4k", kind = CopyKind.DEBRID, height = 2160, size = 50 * gb)), null))
    }

    @Test
    fun aTorBox4KCopyIsTheLastOptionForAFilmFromASlowerSource() {
        val away = offer("p", kind = CopyKind.SERVER, height = 2160, size = 60 * gb, durationMs = ownMs, remote = true)
        val list = offers(away, offer("4k", kind = CopyKind.DEBRID, height = 2160, size = 50 * gb, source = "Torrentio"))
        assertEquals("4k", CopyFirst.headStartPick(list, null)?.id)
        val both = offers(away, offer("4k", kind = CopyKind.DEBRID, height = 2160, size = 50 * gb),
            offer("1080", kind = CopyKind.LINK, size = 8 * gb))
        assertEquals("1080", CopyFirst.headStartPick(both, null)?.id)
    }

    @Test
    fun theHeadStartKeepsTheStructuredRules() {
        val playing = offer("p", kind = CopyKind.DEBRID, height = 2160, size = 60 * gb, durationMs = ownMs)
        val list = offers(playing,
            offer("long", durationMs = ownMs + 600_000),
            offer("usenet", kind = CopyKind.USENET),
            offer("cut", text = "Film.2019.Extended.1080p.WEB"),
            offer("ok", durationMs = ownMs + 2_000),
        )
        val v = CopyFirst.headStartVerdicts(list, null).associate { it.first.id to it.second }
        assertNotNull(v["long"])
        assertNotNull(v["usenet"])
        assertNotNull(v["cut"])
        assertNull(v["ok"])
        assertEquals("ok", CopyFirst.headStartPick(list, null)?.id)
    }

    @Test
    fun theHeadStartLeavesItToThePassWhenThePlayingPictureIsNotKnown() {
        assertNull(CopyFirst.headStartPick(offers(null, offer("a")), null))
        assertNull(CopyFirst.headStartPick(offers(offer("p", height = 0), offer("a")), null))
        assertTrue(CopyFirst.headStartVerdicts(offers(null, offer("a")), null).isEmpty())
    }

    @Test
    fun aTitleThatIsNot4KOnlyTakesALighterCopyAtOrBelowItsHeight() {
        val remux = offer("p", height = 1080, size = 30 * gb, durationMs = ownMs, web = false)
        val list = offers(remux,
            offer("4k", height = 2160, size = 10 * gb),
            offer("heavy", size = 25 * gb),
            offer("web", size = 6 * gb),
        )
        val v = CopyFirst.headStartVerdicts(list, null).associate { it.first.id to it.second }
        assertNotNull(v["4k"])
        assertNotNull(v["heavy"])
        assertNull(v["web"])
        assertEquals("web", CopyFirst.headStartPick(list, null)?.id)
        // The stream's own stated size wins over the source list's.
        assertNull(CopyFirst.headStartPick(list, 8 * gb))
    }

    @Test
    fun sourcesThatFailedLatelyAreRankedLastAtTheHeadStart() {
        val playing = offer("p", kind = CopyKind.DEBRID, height = 2160, size = 60 * gb, durationMs = ownMs)
        val list = offers(playing, offer("bad", height = 720, source = "Bad"), offer("good", source = "Good"))
        assertEquals("bad", CopyFirst.headStartPick(list, null)?.id)
        assertEquals("good", CopyFirst.headStartPick(list, null, penalised = { it.source == "Bad" })?.id)
    }

    private class Memory : HardwareFailureMemory {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
    }

    @Test
    fun theSourceBookCountsDecaysAndClears() {
        var now = 1_000_000L
        val memory = Memory()
        val book = CopySourceBook(memory) { now }
        val bad = offer("a", kind = CopyKind.TORBOX_ADD, source = "Comet")
        val sameSource = offer("b", kind = CopyKind.DEBRID, source = " comet ")
        val other = offer("c", kind = CopyKind.LINK, source = "Comet")
        book.count(bad, CopySourceBook.SLOW)
        assertFalse(book.penalised(bad))
        book.count(bad, CopySourceBook.SLOW)
        assertTrue(book.penalised(bad))
        assertTrue(book.penalised(sameSource))
        assertFalse(book.penalised(other))
        now += CopySourceBook.HALF_LIFE_MS
        assertEquals(0.5, book.score(bad), 1e-6)
        assertFalse(book.penalised(bad))
        book.count(bad, CopySourceBook.WRONG)
        assertTrue(book.penalised(bad))
        // Kept across runs.
        val again = CopySourceBook(memory) { now }
        assertEquals(1.5, again.score(bad), 1e-6)
        again.cleared(sameSource)
        assertEquals(0.0, again.score(bad), 1e-6)
        assertEquals(0.0, CopySourceBook(memory) { now }.score(bad), 1e-6)
    }

    @Test
    fun theSourceBookForgetsOldEntriesAndReadsBadDataAsNothing() {
        var now = 0L
        val memory = Memory()
        val book = CopySourceBook(memory) { now }
        book.count(offer("a", source = "Old"), CopySourceBook.WRONG)
        now += 20 * CopySourceBook.HALF_LIFE_MS
        book.count(offer("b", source = "New"), CopySourceBook.SLOW)
        assertFalse(memory.map.values.single().contains("old"))
        memory.map["copy_sources_2"] = "garbage\nlink|x\tnot-a-number\t5"
        assertEquals(0.0, CopySourceBook(memory) { now }.score(offer("x", source = "x")), 1e-6)
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
    fun theHeadStartHandsItsLinkToThePassOnce() = runBlocking {
        val playing = offer("p", kind = CopyKind.DEBRID, height = 2160, size = 60 * gb, durationMs = ownMs)
        val top = offer("720", kind = CopyKind.TORBOX_ADD, height = 720, size = 3 * gb)
        var done = 0
        val link = CopyLink("https://x/y", emptyMap(), "a TorBox copy added for thumbnails") { done++ }
        val sources = Sources(offers(playing, top, offer("1080")), mapOf("720" to link))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val head = CopyHeadStart("tt1", pickedAtMs = 0L)
            head.start(scope, sources, null, null)
            assertNull(head.take(offer("1080")))
            assertSame(link, head.take(top))
            assertNull(head.take(top))
            assertTrue(head.opened)
            head.release()
            assertEquals(0, done)
            assertEquals(1, sources.opened.count { it == "720" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aLinkNobodyTookIsGivenBack() = runBlocking {
        val playing = offer("p", kind = CopyKind.DEBRID, height = 2160, size = 60 * gb, durationMs = ownMs)
        val top = offer("720", kind = CopyKind.TORBOX_ADD, height = 720, size = 3 * gb)
        var done = 0
        val link = CopyLink("https://x/y", emptyMap(), "a TorBox copy added for thumbnails") { done++ }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val head = CopyHeadStart("tt1", pickedAtMs = 0L)
            head.start(scope, Sources(offers(playing, top), mapOf("720" to link)), null, null)
            while (!head.opened) kotlinx.coroutines.delay(10)
            head.release()
            assertEquals(1, done)
            head.release()
            assertEquals(1, done)
            assertNull(head.take(top))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun noFittingCopyMeansNoLinkAndNothingOpened() = runBlocking {
        val playing = offer("p", kind = CopyKind.DEBRID, height = 2160, size = 60 * gb, durationMs = ownMs)
        val sources = Sources(offers(playing, offer("long", durationMs = ownMs + 600_000)), emptyMap())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val head = CopyHeadStart("tt1", pickedAtMs = 0L)
            head.start(scope, sources, null, null)
            assertNull(head.take(offer("long", durationMs = ownMs + 600_000)))
            kotlinx.coroutines.delay(100)
            assertFalse(head.opened)
            assertTrue(sources.opened.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
