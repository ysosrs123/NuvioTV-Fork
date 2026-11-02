package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class CopyChoiceTest {
    private val ownMs = 7_200_000L
    private val playing = "Film.2019.2160p.UHD.BluRay.REMUX.HDR.HEVC.TrueHD-GRP"

    private fun offer(
        id: String,
        kind: CopyKind = CopyKind.LINK,
        height: Int = 1080,
        codec: CopyCodec = CopyCodec.H264,
        hdr: Boolean = false,
        web: Boolean = true,
        size: Long? = 4_000_000_000L,
        container: String? = "mkv",
        durationMs: Long? = null,
        text: String = "Film.2019.1080p.WEB-DL.H264-GRP",
        remote: Boolean = false,
    ) = CopyOffer(id, id, kind, height, codec, hdr, web, size, container, durationMs, text, remote)

    @Test
    fun editionWordsMustMatch() {
        assertEquals(setOf(CopyChoice.Edition.EXTENDED), CopyChoice.editions("Film.Extended.Cut.1080p"))
        assertEquals(setOf(CopyChoice.Edition.DIRECTORS), CopyChoice.editions("Film Director’s Cut 1080p"))
        assertEquals(setOf(CopyChoice.Edition.DIRECTORS), CopyChoice.editions("Film.Directors.Cut"))
        assertTrue(CopyChoice.editions("Film.2019.1080p.WEB-DL").isEmpty())
        assertNotNull(CopyChoice.rejection(offer("a", text = "Film.Extended.1080p"), playing, ownMs))
        assertNotNull(CopyChoice.rejection(offer("b", text = "Film.IMAX.1080p"), playing, ownMs))
        assertNotNull(CopyChoice.rejection(offer("c"), "Film.2019.2160p.Unrated.REMUX", ownMs))
        assertNull(CopyChoice.rejection(offer("d", text = "Film.Extended.1080p"), "Film.EXTENDED.2160p.REMUX", ownMs))
        assertNull(CopyChoice.rejection(offer("e"), playing, ownMs))
    }

    @Test
    fun editionWordsAreIgnoredWhenTheLengthMatches() {
        val remux = "Film.2019.2160p.Extended.REMUX"
        assertNull(CopyChoice.rejection(offer("a", durationMs = ownMs + 3_000), remux, ownMs))
        assertNull(CopyChoice.rejection(offer("b", text = "Film.IMAX.1080p", durationMs = ownMs), playing, ownMs))
        assertNull(CopyChoice.rejection(offer("c", durationMs = (ownMs * 0.959).toLong()), remux, ownMs))
        assertNotNull(CopyChoice.rejection(offer("d", text = "Film.Extended.1080p", durationMs = ownMs + 600_000), playing, ownMs))
    }

    @Test
    fun editionWordsStillCountWhenTheLengthIsUnknown() {
        assertNotNull(CopyChoice.rejection(offer("a", durationMs = null), "Film.2019.2160p.Extended.REMUX", ownMs))
        assertNotNull(CopyChoice.rejection(offer("b", text = "Film.Directors.Cut.1080p", durationMs = null), playing, ownMs))
        assertNull(CopyChoice.rejection(offer("c", text = "Film.Extended.1080p", durationMs = null), "Film.EXTENDED.2160p", ownMs))
    }

    @Test
    fun resolutionAndContainerRules() {
        assertNull(CopyChoice.rejection(offer("a", height = 720), playing, ownMs))
        assertNotNull(CopyChoice.rejection(offer("b", height = 2160), playing, ownMs))
        assertNotNull(CopyChoice.rejection(offer("c", height = 480), playing, ownMs))
        assertNotNull(CopyChoice.rejection(offer("d", height = 0), playing, ownMs))
        assertNotNull(CopyChoice.rejection(offer("e", container = "avi"), playing, ownMs))
        assertNotNull(CopyChoice.rejection(offer("f", container = "m3u8"), playing, ownMs))
        assertNull(CopyChoice.rejection(offer("g", container = "mp4"), playing, ownMs))
        assertNull(CopyChoice.rejection(offer("h", container = null), playing, ownMs))
        assertEquals("mkv", CopyChoice.containerOf("Film.2019.1080p.WEB.mkv"))
        assertEquals("mp4", CopyChoice.containerOf("https://h/x/Film.MP4?token=1"))
        assertNull(CopyChoice.containerOf("https://h/Videos/abc/stream"))
    }

    @Test
    fun lengthWithinThreeMinutesOrPalRatio() {
        assertNull(CopyChoice.rejection(offer("a", durationMs = ownMs + 1_900), playing, ownMs))
        assertNull(CopyChoice.rejection(offer("a5", durationMs = ownMs + 5_000), playing, ownMs))
        assertNull(CopyChoice.rejection(offer("a8", durationMs = ownMs - 8_000), playing, ownMs))
        // Further apart, up to three minutes, the copy's keyframes decide when it opens.
        assertNull(CopyChoice.rejection(offer("b", durationMs = ownMs + 8_001), playing, ownMs))
        assertNull(CopyChoice.rejection(offer("b12", durationMs = ownMs - 12_000), playing, ownMs))
        assertNull(CopyChoice.rejection(offer("b180", durationMs = ownMs + 180_000), playing, ownMs))
        assertNotNull(CopyChoice.rejection(offer("b181", durationMs = ownMs + 181_000), playing, ownMs))
        assertNotNull(CopyChoice.rejection(offer("bcut", durationMs = ownMs + 60_000, text = "Film.2019.Extended.1080p.WEB-DL"), playing, ownMs))
        assertNull(CopyChoice.rejection(offer("c", durationMs = (ownMs * 0.959).toLong()), playing, ownMs))
        assertNotNull(CopyChoice.rejection(offer("d", durationMs = (ownMs * 0.9).toLong()), playing, ownMs))
        assertEquals(7_200_000L, CopyChoice.parsedDurationMs(7_200L))
        assertEquals(7_200_000L, CopyChoice.parsedDurationMs(7_200_000L))
        assertNull(CopyChoice.parsedDurationMs(0L))
        assertNull(CopyChoice.parsedDurationMs(null))
    }

    @Test
    fun orderPutsThe720pCopyFirstThenFastServersSdrH264WebSmaller() {
        val ordered = CopyChoice.order(listOf(
            offer("debrid", kind = CopyKind.DEBRID, size = 1_000_000_000L),
            offer("720", height = 720, size = 1_000_000_000L),
            offer("hdr", hdr = true, size = 1_000_000_000L),
            offer("hevc", codec = CopyCodec.HEVC, size = 1_000_000_000L),
            offer("bluray", web = false, size = 1_000_000_000L),
            offer("big", size = 9_000_000_000L),
            offer("small", size = 2_000_000_000L),
            offer("server", kind = CopyKind.SERVER, size = 3_000_000_000L),
            offer("nosize", size = null),
        )).map { it.id }
        assertEquals(listOf("720", "debrid", "server", "small", "big", "nosize", "bluray", "hevc", "hdr"), ordered)
    }

    @Test
    fun a720pCopyComesBeforeA1080pOneAndATorBoxCopyBeforeAnUnknownLink() {
        val ordered = CopyChoice.order(listOf(
            offer("hevc1080", codec = CopyCodec.HEVC),
            offer("added720", kind = CopyKind.TORBOX_ADD, height = 720),
            offer("h264720", height = 720),
            offer("h2641080"),
        )).map { it.id }
        assertEquals(listOf("added720", "h264720", "h2641080", "hevc1080"), ordered)
    }

    @Test
    fun batchedServersFirstThenSourceCostThenTheRest() {
        val ordered = CopyChoice.order(listOf(
            offer("usenet", kind = CopyKind.USENET, size = 1_000_000_000L),
            offer("premiumize", kind = CopyKind.PREMIUMIZE, size = 1_000_000_000L),
            offer("added", kind = CopyKind.TORBOX_ADD, size = 1_000_000_000L),
            offer("link720", height = 720),
            offer("link", size = 2_000_000_000L),
            offer("list", kind = CopyKind.DEBRID, size = 2_000_000_000L),
        )).map { it.id }
        assertEquals(listOf("link720", "list", "added", "link", "premiumize", "usenet"), ordered)
    }

    @Test
    fun aHeavyRemuxCopyComesAfterALightWebCopyEvenWhenTheRemuxIsH264() {
        // 2 h film: a 1080p H.264 disc remux of 30 GB is ~33 Mbit/s, a 1080p HEVC WEB-DL of 6 GB ~6.7 Mbit/s.
        val ordered = CopyChoice.order(listOf(
            offer("remux", web = false, size = 30_000_000_000L, text = "Film.2019.1080p.BluRay.REMUX.AVC-GRP"),
            offer("webhevc", codec = CopyCodec.HEVC, size = 6_000_000_000L),
            offer("web720", height = 720, size = 3_000_000_000L),
        ), ownMs = ownMs).map { it.id }
        assertEquals(listOf("web720", "webhevc", "remux"), ordered)
        // Without the film's length the old order stands: H.264 first.
        assertEquals("remux", CopyChoice.order(listOf(
            offer("webhevc", codec = CopyCodec.HEVC, size = 6_000_000_000L),
            offer("remux", web = false, size = 12_000_000_000L),
        )).first().id)
    }

    @Test
    fun picturesRankSmallestFirstAndA4KCopyLastOfAll() {
        val ordered = CopyChoice.order(listOf(
            offer("4k", kind = CopyKind.DEBRID, height = 2160, size = 1_000_000_000L),
            offer("1080", kind = CopyKind.DEBRID),
            offer("720hevc", codec = CopyCodec.HEVC, height = 720, size = 9_000_000_000L),
            offer("720", height = 720),
        ), ownMs = ownMs).map { it.id }
        assertEquals(listOf("720", "720hevc", "1080", "4k"), ordered)
    }

    @Test
    fun copiesFromSourcesThatFailedLatelyRankLastWithinTheirSideEffects() {
        val ordered = CopyChoice.order(listOf(
            offer("bad720", height = 720),
            offer("good1080"),
            offer("usenet", kind = CopyKind.USENET, height = 720),
        ), penalised = { it.id == "bad720" }).map { it.id }
        assertEquals(listOf("good1080", "bad720", "usenet"), ordered)
    }

    @Test
    fun a4KCopyOnlyForA4KTitleFromAnotherBatchedSource() {
        assertNotNull(CopyChoice.rejection(offer("4k", height = 2160), playing, ownMs))
        assertNull(CopyChoice.rejection(offer("4k", height = 2160), playing, ownMs, fourKCopies = true))
        assertEquals("2160p is above the playing 1080p",
            CopyChoice.rejection(offer("4k", height = 2160), playing, ownMs, maxHeight = 1080, fourKCopies = true))
        val tb4k = offer("tb4k", kind = CopyKind.DEBRID, height = 2160)
        val away = offer("p", kind = CopyKind.SERVER, height = 2160, remote = true)
        val tbPlaying = offer("p", kind = CopyKind.TORBOX_ADD, height = 2160)
        assertNull(CopyChoice.fourKCopyRejection(tb4k, away, null))
        assertEquals("a 4K copy from the same source as the film", CopyChoice.fourKCopyRejection(tb4k, tbPlaying, true))
        assertNotNull(CopyChoice.fourKCopyRejection(tb4k, null, true))
        assertNotNull(CopyChoice.fourKCopyRejection(offer("rd", kind = CopyKind.RESOLVE, height = 2160, text = "[RD+] Film"), away, false))
        assertNotNull(CopyChoice.fourKCopyRejection(offer("link", height = 2160), away, null))
        assertNull(CopyChoice.fourKCopyRejection(offer("link", height = 2160), away, true))
        assertNull(CopyChoice.fourKCopyRejection(offer("hd"), tbPlaying, null))
    }

    @Test
    fun providersGroupCopiesThatShareServers() {
        assertEquals(CopyProvider.TORBOX, CopyChoice.providerOf(offer("a", kind = CopyKind.DEBRID)))
        assertEquals(CopyProvider.TORBOX, CopyChoice.providerOf(offer("b", kind = CopyKind.TORBOX_ADD)))
        assertEquals(CopyProvider.TORBOX, CopyChoice.providerOf(offer("c", kind = CopyKind.RESOLVE, text = "[TB+] Film")))
        assertEquals(CopyProvider.OTHER_DEBRID, CopyChoice.providerOf(offer("d", kind = CopyKind.RESOLVE, text = "[RD+] Film")))
        assertEquals(CopyProvider.AWAY_SERVER, CopyChoice.providerOf(offer("e", kind = CopyKind.SERVER, remote = true)))
        assertEquals(CopyProvider.HOME_SERVER, CopyChoice.providerOf(offer("f", kind = CopyKind.SERVER)))
    }

    @Test
    fun anUnknownOwnLengthLeavesTheEditionWordsToDecide() {
        assertNull(CopyChoice.rejection(offer("a", durationMs = ownMs + 600_000), playing, 0L))
        assertNotNull(CopyChoice.rejection(offer("b", durationMs = ownMs, text = "Film.Extended.1080p"), playing, 0L))
    }

    @Test
    fun heavyIsWorkedOutFromTheCopysOwnLengthFirst() {
        assertTrue(CopyChoice.heavy(offer("a", size = 20_000_000_000L), ownMs))           // 22 Mbit/s
        assertFalse(CopyChoice.heavy(offer("b", size = 15_000_000_000L), ownMs))          // 16.7 Mbit/s
        assertFalse(CopyChoice.heavy(offer("c", size = 20_000_000_000L, durationMs = 10_800_000L), ownMs)) // 3 h: 14.8
        assertFalse(CopyChoice.heavy(offer("d", size = null), ownMs))
        assertTrue(CopyChoice.heavy(offer("e", web = false, size = 16_000_000_000L), null))
        assertFalse(CopyChoice.heavy(offer("f", web = true, size = 16_000_000_000L), null))
        assertFalse(CopyChoice.heavy(offer("g", web = false, size = 8_000_000_000L), null))
    }

    @Test
    fun aLightTorBoxCopyComesBeforeAHeavyRemuxLink() {
        val ordered = CopyChoice.order(listOf(
            offer("added", kind = CopyKind.TORBOX_ADD, size = 5_000_000_000L),
            offer("remux", web = false, size = 30_000_000_000L),
        ), ownMs = ownMs).map { it.id }
        assertEquals(listOf("added", "remux"), ordered)
    }

    @Test
    fun aUsenetTitleTakesAnyCopyUpToItsOwnResolutionButNoUsenetLink() {
        val hd = "Film.2019.1080p.WEB-DL.DDP5.1.H264-GRP"
        assertNull(CopyChoice.rejection(offer("same"), hd, ownMs, maxHeight = 1080, usenet = true))
        assertNull(CopyChoice.rejection(offer("720", height = 720), hd, ownMs, maxHeight = 1080, usenet = true))
        assertEquals("2160p is above the playing 1080p",
            CopyChoice.rejection(offer("4k", height = 2160), hd, ownMs, maxHeight = 1080, usenet = true))
        assertEquals("1080p is above the playing 720p",
            CopyChoice.rejection(offer("up"), hd, ownMs, maxHeight = 720, usenet = true))
        assertEquals("another Usenet link",
            CopyChoice.rejection(offer("nzb", kind = CopyKind.USENET), hd, ownMs, maxHeight = 1080, usenet = true))
        assertNotNull(CopyChoice.rejection(offer("cut", text = "Film.Extended.1080p"), hd, ownMs, maxHeight = 1080, usenet = true))
        assertNotNull(CopyChoice.rejection(offer("long", durationMs = ownMs + 200_000), hd, ownMs, maxHeight = 1080, usenet = true))
        // A 4K title from Usenet keeps the smaller-copy rule.
        assertNotNull(CopyChoice.rejection(offer("4k", height = 2160), playing, ownMs, maxHeight = 2160, usenet = true))
        assertNull(CopyChoice.rejection(offer("hd"), playing, ownMs, maxHeight = 2160, usenet = true))
        // A 4K title not from Usenet may still use a Usenet copy.
        assertNull(CopyChoice.rejection(offer("nzb", kind = CopyKind.USENET), playing, ownMs))
    }

    @Test
    fun aUsenetTitleOrdersBySourceFirst() {
        val ordered = CopyChoice.order(listOf(
            offer("added", kind = CopyKind.TORBOX_ADD, size = 1_000_000_000L),
            offer("link", size = 1_000_000_000L),
            offer("premiumize", kind = CopyKind.PREMIUMIZE, size = 1_000_000_000L),
            offer("list720", kind = CopyKind.DEBRID, height = 720),
            offer("list", kind = CopyKind.DEBRID, size = 9_000_000_000L),
            offer("server", kind = CopyKind.SERVER, codec = CopyCodec.HEVC),
        ), usenet = true).map { it.id }
        assertEquals(listOf("server", "list720", "list", "premiumize", "link", "added"), ordered)
    }

    @Test
    fun heightClassesFollowTheWidthForWideFilms() {
        assertEquals(2160, CopyChoice.heightClass(3840, 2160))
        assertEquals(2160, CopyChoice.heightClass(3840, 1600))
        assertEquals(1080, CopyChoice.heightClass(1920, 1080))
        assertEquals(1080, CopyChoice.heightClass(1920, 800))
        assertEquals(1080, CopyChoice.heightClass(1440, 1080))
        assertEquals(720, CopyChoice.heightClass(1280, 720))
        assertEquals(720, CopyChoice.heightClass(1280, 536))
        assertEquals(480, CopyChoice.heightClass(720, 480))
    }

    @Test
    fun usenetMarkingsAreKnown() {
        assertTrue(CopyChoice.usenetMarked("usenet", null))
        assertTrue(CopyChoice.usenetMarked(null, "Usenet"))
        assertTrue(CopyChoice.usenetMarked("nzb", "torbox"))
        assertFalse(CopyChoice.usenetMarked("debrid", "torbox"))
        assertFalse(CopyChoice.usenetMarked(null, null))
    }

    @Test
    fun usenetLinksAreKnown() {
        assertTrue(CopyChoice.isUsenet("http://192.168.1.10:3000/api/v1/usenet/stream/abc/Film.mkv"))
        assertTrue(CopyChoice.isUsenet("http://nas.local/USENET/stream/abc"))
        assertFalse(CopyChoice.isUsenet("https://cdn.example/Film.1080p.mkv"))
        assertFalse(CopyChoice.isUsenet("https://cdn.example/files/usenet-stream.mkv"))
        assertFalse(CopyChoice.isUsenet("https://cdn.example/Film.mkv?x=/usenet/stream/"))
    }

    @Test
    fun copySlotsServeTheSameMoments() {
        val same = CopyChoice.ownSlotsByCopySlot(10, 1.0, 10)
        assertEquals(10, same.size)
        assertArrayEquals(intArrayOf(4), same[4])
        // A 25 fps copy runs 4 % shorter: some copy slots serve two own slots.
        val pal = CopyChoice.ownSlotsByCopySlot(720, 0.959, ReleaseMatch.slotCount((7_200_000 * 0.959).toLong(), 10_000))
        assertEquals(720, pal.values.sumOf { it.size })
        assertTrue(pal.values.any { it.size == 2 })
        assertTrue(pal.keys.all { it in 0 until 691 })
    }

    @Test
    fun fitPadsOrCutsToTheOwnPicture() {
        val same = CopyFit.plan(480, 270, 480, 270)!!
        assertEquals(0, same.top)
        // A scope copy without bars into a letterboxed 16:9 4K picture: bars added.
        val pad = CopyFit.plan(480, 200, 480, 270)!!
        assertEquals(480, pad.renderW)
        assertEquals(200, pad.renderH)
        assertEquals(35, pad.top)
        // A letterboxed copy into a cropped 4K picture: the copy's bars are cut.
        val cut = CopyFit.plan(480, 270, 480, 200)!!
        assertEquals(-35, cut.top)
        assertNull(CopyFit.plan(480, 360, 480, 200))
    }

    @Test
    fun anotherReleaseWithItsBarsKeptFitsOneWithoutThem() {
        // The Crown: the remux keeps its bars (480x270), the RARBG encode has them cut (480x240).
        val cut = CopyFit.plan(480, 270, 480, 240)!!
        assertEquals(480, cut.renderW)
        assertEquals(270, cut.renderH)
        assertEquals(-15, cut.top)
        val pad = CopyFit.plan(480, 240, 480, 270)!!
        assertEquals(15, pad.top)
    }

    @Test
    fun serversLeftAloneAfterTrouble() {
        val b = CopyHostBlocks()
        val now = 1_000_000_000_000L
        b.refused("h", now)
        assertNotNull(b.blocked("h", now + 29 * 60_000L))
        assertNull(b.blocked("h", now + 31 * 60_000L))
        b.trouble("t", now)
        assertNotNull(b.blocked("t", now + 60_000L))
        assertTrue(b.blames(1_000L, 1_400L, 15_000L, 20_000L))
        assertFalse(b.blames(1_000L, 1_400L, 30_000L, 20_000L))
        assertFalse(b.blames(1_000L, 1_400L, 500L, 20_000L))
        assertFalse(b.blames(0L, 0L, 500L, 20_000L))
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals(86_400_000L, CopyHostBlocks.endOfDayMs(3_600_000L, utc))
    }

    @Test
    fun moreEditionWords() {
        assertEquals(setOf(CopyChoice.Edition.UNCUT), CopyChoice.editions("Film.UNCUT.1080p"))
        assertEquals(setOf(CopyChoice.Edition.SPECIAL), CopyChoice.editions("Film Special Edition 1080p"))
        assertEquals(setOf(CopyChoice.Edition.FINAL_CUT), CopyChoice.editions("Film.Final.Cut.1080p"))
        assertEquals(setOf(CopyChoice.Edition.REMASTERED), CopyChoice.editions("Film.REMASTERED.1080p"))
        assertNotNull(CopyChoice.rejection(offer("a", text = "Film.Final.Cut.1080p"), playing, ownMs))
    }

    @Test
    fun plainLinksUsenetAndCachedDebridLinksAreRead() {
        assertNull(CopyChoice.linkRejection("https://cdn.example.org/files/Film.2019.1080p.WEB.mkv", "Film 1080p"))
        assertNull(CopyChoice.linkRejection("http://192.168.1.10:3000/api/v1/usenet/stream/abc/Film.mkv", "[TB+] Film"))
        assertNull(CopyChoice.linkRejection("https://torrentio.strem.fun/resolve/realdebrid/key/hash/1/Film.mkv", "[RD+] Film"))
        assertNull(CopyChoice.linkRejection("https://aio.example.org/api/v1/playback/abc", "[TB\u26a1] Film 1080p"))
        assertNull(CopyChoice.linkRejection("https://comet.example.org/cfg/playback/abc/0/Film.mkv", "[AD\u26a1] Comet"))
        assertNull(CopyChoice.linkRejection("https://x.example.org/Film.mkv", "TB+ cached"))
        assertNull(CopyChoice.linkRejection("https://x.example.org/torbox/abc/Film.mkv", "Film", cached = true))
        assertEquals(CopyKind.RESOLVE, CopyChoice.linkKind("https://aio.example.org/api/v1/playback/abc", "[TB\u26a1] Film"))
        assertEquals(CopyKind.RESOLVE, CopyChoice.linkKind("https://x.example.org/Film.mkv", "[RD+] Film"))
        assertEquals(CopyKind.LINK, CopyChoice.linkKind("https://cdn.example.org/Film.mkv", "Film 1080p"))
        assertEquals(CopyKind.USENET, CopyChoice.linkKind("http://192.168.1.10:3000/api/v1/usenet/stream/abc", "[TB+] Film"))
    }

    @Test
    fun debridLinksNotCachedOrWithoutACachedMarkAreLeftOut() {
        assertNotNull(CopyChoice.linkRejection("https://torrentio.strem.fun/resolve/realdebrid/key/hash/1/Film.mkv", "[RD download] Film"))
        assertNotNull(CopyChoice.linkRejection("https://aio.example.org/api/v1/playback/abc", "[TB\u23f3] Film"))
        assertNotNull(CopyChoice.linkRejection("https://comet.example.org/cfg/playback/abc", "[TB\u2b07\ufe0f] Film"))
        assertNotNull(CopyChoice.linkRejection("https://x.example.org/Film.mkv", "[RD] uncached Film"))
        assertNotNull(CopyChoice.linkRejection("https://aio.example.org/api/v1/debrid/playback/abc", "Film"))
        assertNotNull(CopyChoice.linkRejection("https://x.example.org/torbox/abc/Film.mkv", "Film"))
        assertNotNull(CopyChoice.linkRejection("https://x.example.org/Film.mkv", "[PM] Film"))
        assertNotNull(CopyChoice.linkRejection("https://x.example.org/Film.mkv", "[RD+] Film", cached = false))
    }

    @Test
    fun aServerAwayFromHomeComesAfterTheCopiesThatCostNothing() {
        val ordered = CopyChoice.order(listOf(
            offer("added", kind = CopyKind.TORBOX_ADD),
            offer("remote", kind = CopyKind.SERVER, remote = true),
            offer("home", kind = CopyKind.SERVER, codec = CopyCodec.HEVC),
            offer("resolve", kind = CopyKind.RESOLVE),
        )).map { it.id }
        assertEquals(listOf("home", "added", "resolve", "remote"), ordered)
    }

    @Test
    fun httpProviderLinksAreReadWhateverTheirNamesSay() {
        assertNull(CopyChoice.linkRejection("https://cdn.example.org/v/Film.1080p.mkv", "HDHub Download 1080p"))
        assertNull(CopyChoice.linkRejection("https://x.example.org/Film.mkv", "⬇ ShowBox 1080p"))
        assertNull(CopyChoice.linkRejection("https://x.example.org/Film.mkv", "Film (download)"))
        assertEquals(CopyKind.LINK, CopyChoice.linkKind("https://cdn.example.org/v/Film.1080p.mkv", "HDHub Download 1080p"))
        assertNotNull(CopyChoice.rejection(offer("hls", container = "m3u8"), playing, ownMs))
    }

    @Test
    fun aDebridLinkNamingItsTorrentGivesTheHash() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        assertEquals(hash, CopyChoice.linkHash("https://torrentio.strem.fun/resolve/torbox/key/${hash.uppercase()}/null/1/F.mkv", "[TB+]"))
        assertNull(CopyChoice.linkHash("https://cdn.example.org/$hash/Film.mkv", "Film 1080p"))
        assertNull(CopyChoice.linkHash("https://aio.example.org/api/v1/playback/abc", "[TB\u26a1] Film"))
        assertNull(CopyChoice.linkHash(null, "[TB+]"))
    }

    @Test
    fun aDebridResolveLinkJoinsTheFirstGroupBehindTheCopiesThatAddNothing() {
        val ordered = CopyChoice.order(listOf(
            offer("resolve", kind = CopyKind.RESOLVE, size = 1_000_000_000L),
            offer("usenet", kind = CopyKind.USENET, size = 1_000_000_000L),
            offer("premiumize", kind = CopyKind.PREMIUMIZE, codec = CopyCodec.HEVC, hdr = true),
            offer("added", kind = CopyKind.TORBOX_ADD, codec = CopyCodec.HEVC, hdr = true),
            offer("link", hdr = true),
            offer("resolve720", kind = CopyKind.RESOLVE, height = 720),
            offer("resolveHevc", kind = CopyKind.RESOLVE, codec = CopyCodec.HEVC),
            offer("resolveHdr", kind = CopyKind.RESOLVE, hdr = true),
        )).map { it.id }
        assertEquals(
            listOf("resolve720", "added", "link", "resolve", "resolveHevc", "resolveHdr", "premiumize", "usenet"),
            ordered,
        )
        val usenet = CopyChoice.order(listOf(
            offer("resolve", kind = CopyKind.RESOLVE),
            offer("added", kind = CopyKind.TORBOX_ADD),
            offer("link"),
        ), usenet = true).map { it.id }
        assertEquals(listOf("link", "added", "resolve"), usenet)
    }

    @Test
    fun aDebridResolveCopyStillFollowsTheResolutionRules() {
        assertNotNull(CopyChoice.rejection(offer("a", kind = CopyKind.RESOLVE, height = 2160), playing, ownMs))
        assertNotNull(CopyChoice.rejection(offer("b", kind = CopyKind.RESOLVE, height = 1080), playing, ownMs, maxHeight = 720))
        assertNull(CopyChoice.rejection(offer("c", kind = CopyKind.RESOLVE, height = 720), playing, ownMs, maxHeight = 720))
    }

    @Test fun aSmallerBoxWaitsForTheCopyLookBeforeDecodingThe4KFilm() {
        assertTrue(CopyChoice.ownPassWaitsForLook(strongBox = false, looking = true, sinceStartMs = 0L))
        assertTrue(CopyChoice.ownPassWaitsForLook(false, true, CopyChoice.LOOK_HOLD_MS - 1))
        assertFalse(CopyChoice.ownPassWaitsForLook(false, true, CopyChoice.LOOK_HOLD_MS))
        assertFalse(CopyChoice.ownPassWaitsForLook(false, looking = false, sinceStartMs = 0L))
        assertFalse(CopyChoice.ownPassWaitsForLook(strongBox = true, looking = true, sinceStartMs = 0L))
    }

    @Test
    fun aTitleOnAServerAwayFromHomeNeverTakesACopyFromAServerAwayFromHome() {
        val offers = listOf(
            offer("remote", kind = CopyKind.SERVER, remote = true),
            offer("usenet", kind = CopyKind.USENET),
            offer("added", kind = CopyKind.TORBOX_ADD),
            offer("resolve", kind = CopyKind.RESOLVE),
            offer("premiumize", kind = CopyKind.PREMIUMIZE),
            offer("home", kind = CopyKind.SERVER),
            offer("link"),
        )
        assertEquals(listOf("home", "added", "link", "resolve", "premiumize", "usenet"),
            CopyChoice.order(offers, awayServer = true).map { it.id })
        assertEquals("remote", CopyChoice.order(offers).map { it.id }[5])
    }

    @Test
    fun aServerAwayFromHomeIsNoCopyForATitleAwayFromHome() {
        val remote = offer("remote", kind = CopyKind.SERVER, remote = true)
        assertFalse(CopyChoice.awayUsable(remote))
        assertTrue(CopyChoice.awayUsable(offer("home", kind = CopyKind.SERVER)))
        assertTrue(CopyChoice.order(listOf(remote), ownMs = ownMs, awayServer = true).isEmpty())
        assertFalse(CopyChoice.anyFits(CopyOffers(playing, listOf(remote)), ownMs))
    }

    @Test
    fun aRemuxAwayFromHomeTakesALighterCopyOfTheSameHeight() {
        val remux = "Film (2019) [Remux-1080p 8-bit AVC DTS-HD MA 5.1]-GRP.mkv"
        val web1080 = offer("web1080", kind = CopyKind.RESOLVE, size = 3_500_000_000L)
        val web720 = offer("web720", kind = CopyKind.RESOLVE, height = 720, size = 2_000_000_000L)
        val otherRemux = offer("remux", kind = CopyKind.DEBRID, web = false, size = 21_700_000_000L, text = remux)
        val offers = CopyOffers(remux, listOf(otherRemux, web720, web1080))
        assertTrue(CopyChoice.anyFits(offers, ownMs))
        assertEquals(listOf(null, null, null), CopyChoice.awayVerdicts(offers, ownMs).map { it.second })
        for (o in offers.offers) assertNull(o.id, CopyChoice.rejection(o, remux, ownMs, maxHeight = 1080))
        assertEquals(listOf("web720", "web1080", "remux"), CopyChoice.order(offers.offers, ownMs = ownMs, awayServer = true).map { it.id })
    }

    @Test
    fun theAwayVerdictsSayWhyEachOfferIsNotUsed() {
        val offers = CopyOffers(playing, listOf(
            offer("remote", kind = CopyKind.SERVER, remote = true),
            offer("big", height = 2160),
            offer("big2", height = 2160),
            offer("noRes", height = 0),
            offer("short", durationMs = 3_600_000L),
            offer("long", durationMs = 9_000_000L),
        ))
        val reasons = CopyChoice.awayVerdicts(offers, ownMs).map { it.second }
        assertEquals("a media server away from home", reasons[0])
        assertEquals("resolution not stated", reasons[3])
        assertTrue(reasons.all { it != null })
        assertEquals("2 x 2160p is not a smaller copy; 2 x another length; 1 x a media server away from home; " +
            "1 x resolution not stated", CopyChoice.reasonTally(reasons.filterNotNull()))
    }

    @Test
    fun anyFitsNeedsOneCopyThatMayStandInAndNoTranscode() {
        val fits = CopyOffers(playing, listOf(offer("big", height = 2160), offer("ok")))
        assertTrue(CopyChoice.anyFits(fits, ownMs))
        assertFalse(CopyChoice.anyFits(CopyOffers(playing, listOf(offer("big", height = 2160))), ownMs))
        assertFalse(CopyChoice.anyFits(CopyOffers(playing, emptyList()), ownMs))
        assertFalse(CopyChoice.anyFits(CopyOffers(playing, listOf(offer("short", durationMs = 3_600_000L))), ownMs))
        assertFalse(CopyChoice.anyFits(CopyOffers(playing, listOf(offer("ok")), playingTranscoded = true), ownMs))
    }

    @Test
    fun serversKnownToBatchKeyframesComeFirstAmongFreeCopies() {
        val known = mapOf("fast" to true, "slow" to false)
        val ordered = CopyChoice.order(listOf(
            offer("slow"),
            offer("unknown"),
            offer("fast", codec = CopyCodec.HEVC),
            offer("premiumize", kind = CopyKind.PREMIUMIZE),
            offer("added", kind = CopyKind.TORBOX_ADD),
            offer("list", kind = CopyKind.DEBRID),
        )) { known[it.id] }.map { it.id }
        assertEquals(listOf("list", "fast", "added", "unknown", "slow", "premiumize"), ordered)
    }

    @Test
    fun aTorBoxResolveLinkIsBatchedAndRealDebridIsNot() {
        assertEquals(true, CopyChoice.resolveBatched("https://addon.example/torbox/KEY/abc/0/Film.mkv", "Comet 1080p"))
        assertEquals(true, CopyChoice.resolveBatched("https://addon.example/resolve/x", "[TB+] Torrentio 1080p"))
        assertEquals(false, CopyChoice.resolveBatched("https://addon.example/realdebrid/KEY/abc", "Comet"))
        assertEquals(false, CopyChoice.resolveBatched("https://addon.example/resolve/x", "[RD+] Torrentio"))
        assertNull(CopyChoice.resolveBatched("https://addon.example/resolve/x", "[AD+] Torrentio"))
        val known = mapOf("tbResolve" to true, "rdResolve" to false)
        val ordered = CopyChoice.order(listOf(
            offer("rdResolve", kind = CopyKind.RESOLVE),
            offer("link"),
            offer("tbResolve", kind = CopyKind.RESOLVE),
            offer("added", kind = CopyKind.TORBOX_ADD),
        )) { known[it.id] }.map { it.id }
        assertEquals(listOf("added", "tbResolve", "link", "rdResolve"), ordered)
    }
}
