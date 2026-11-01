package com.nuvio.tv.core.player.thumbnail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class CopyStartSoonerTest {

    private class BytesReader(private val b: ByteArray) : RangeReader {
        val reads: MutableList<Pair<Long, Int>> = Collections.synchronizedList(ArrayList())
        val atOnce = AtomicInteger()
        @Volatile var mostAtOnce = 0
        override val totalLength: Long get() = b.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray {
            reads += offset to length
            val now = atOnce.incrementAndGet()
            synchronized(this) { mostAtOnce = maxOf(mostAtOnce, now) }
            try {
                Thread.sleep(if (length >= SplitRangeReader.MIN_PART / 2) 30L else 0L)
                if (offset >= b.size) return ByteArray(0)
                val end = minOf(b.size.toLong(), offset + length).toInt()
                return b.copyOfRange(offset.toInt(), end)
            } finally {
                atOnce.decrementAndGet()
            }
        }
    }

    private fun bytes(n: Int) = ByteArray(n) { (it * 31 + it / 7).toByte() }

    @Test
    fun aBigReadIsCutIntoContiguousParts() {
        val mb = 1024 * 1024
        val plan = SplitRangeReader.plan(1000L, 4 * mb, 3)
        assertEquals(3, plan.size)
        assertEquals(1000L, plan.first().first)
        var at = 1000L
        for ((o, len) in plan) {
            assertEquals(at, o)
            at += len
        }
        assertEquals(1000L + 4 * mb, at)
        assertEquals(1, SplitRangeReader.plan(0L, mb - 1, 3).size)
        assertEquals(1, SplitRangeReader.plan(0L, 4 * mb, 1).size)
        // Never parts under half a megabyte.
        assertEquals(2, SplitRangeReader.plan(0L, mb + 10, 4).size)
    }

    @Test
    fun theSplitReaderGivesTheSameBytesAtOnce() {
        val data = bytes(3 * 1024 * 1024 + 123)
        val inner = BytesReader(data)
        val split = SplitRangeReader(inner, 3)
        assertArrayEquals(data.copyOfRange(77, 77 + 2_500_000), split.read(77, 2_500_000))
        assertTrue("parts read at once: ${inner.mostAtOnce}", inner.mostAtOnce >= 2)
        // Past the end: what is there, in order.
        val tail = split.read(data.size - 1_200_000L, 2_000_000)
        assertArrayEquals(data.copyOfRange(data.size - 1_200_000, data.size), tail)
        assertArrayEquals(data.copyOfRange(10, 20), split.read(10, 10))
        assertEquals(data.size.toLong(), split.totalLength)
    }

    @Test
    fun aPartThatFindsNoConnectionIsReadAgainAlone() {
        val data = bytes(2 * 1024 * 1024)
        val failedOnce = AtomicInteger()
        val inner = object : RangeReader {
            override val totalLength: Long get() = data.size.toLong()
            override fun read(offset: Long, length: Int): ByteArray {
                if (offset > 0 && failedOnce.getAndIncrement() == 0) throw ConnectionLedger.NoSlotException()
                return data.copyOfRange(offset.toInt(), minOf(data.size, (offset + length).toInt()))
            }
        }
        assertArrayEquals(data, SplitRangeReader(inner, 2).read(0, data.size))
        assertTrue(failedOnce.get() >= 1)
    }

    @Test(expected = java.io.IOException::class)
    fun otherReadFailuresStillFail() {
        val inner = object : RangeReader {
            override val totalLength: Long get() = -1
            override fun read(offset: Long, length: Int): ByteArray = throw java.io.IOException("gone")
        }
        SplitRangeReader(inner, 3).read(0, 2 * 1024 * 1024)
    }

    @Test
    fun indexPartsStayInsideTheCopyBudget() {
        assertEquals(3, CopyFirst.indexParts(strongBox = true, linkLanes = Int.MAX_VALUE, room = 8))
        assertEquals(2, CopyFirst.indexParts(strongBox = false, linkLanes = Int.MAX_VALUE, room = 8))
        assertEquals(1, CopyFirst.indexParts(strongBox = true, linkLanes = 1, room = 8))
        assertEquals(1, CopyFirst.indexParts(strongBox = true, linkLanes = 4, room = 0))
        assertTrue(CopyFirst.INDEX_PARTS < CopyFirst.MAX_LANES)
    }

    @Test
    fun theReadAheadWaitsForRateLimitsAndMemory() {
        assertNull(CopyFirst.prereadBlocked(false, false, 0L, null))
        assertNotNull(CopyFirst.prereadBlocked(true, false, 0L, null))
        assertNotNull(CopyFirst.prereadBlocked(false, true, 0L, null))
        assertNotNull(CopyFirst.prereadBlocked(false, false, 5_000L, null))
        assertNotNull(CopyFirst.prereadBlocked(false, false, 0L, "MemAvailable 90 MB < 150"))
    }

    // --- Matroska with a Cues element of about 1.7 MB, past the first 256 KB ---

    private class Out {
        val o = ByteArrayOutputStream()
        fun id(v: Long) {
            val n = when { v > 0xFFFFFF -> 4; v > 0xFFFF -> 3; v > 0xFF -> 2; else -> 1 }
            for (i in 0 until n) o.write(((v shr (8 * (n - 1 - i))) and 0xFF).toInt())
        }
        fun size8(v: Long) {
            o.write(0x01)
            for (i in 1 until 8) o.write(((v shr (8 * (7 - i))) and 0xFF).toInt())
        }
        fun el(idv: Long, body: ByteArray) {
            id(idv); size8(body.size.toLong()); o.write(body)
        }
        fun bytes(): ByteArray = o.toByteArray()
    }

    private fun el(idv: Long, vararg body: ByteArray) = Out().apply {
        val b = ByteArrayOutputStream().also { s -> body.forEach { s.write(it) } }.toByteArray()
        el(idv, b)
    }.bytes()

    private fun uint(idv: Long, v: Long, n: Int = 4) = el(idv, ByteArray(n) { ((v shr (8 * (n - 1 - it))) and 0xFF).toByte() })
    private fun str(idv: Long, s: String) = el(idv, s.toByteArray())

    private fun hvcc(): ByteArray {
        val h = ByteArray(23)
        h[0] = 1
        h[21] = 0x03
        h[22] = 1
        val sps = byteArrayOf(0x42, 0x01, 0x11, 0x22)
        return h + byteArrayOf((0x80 or 33).toByte(), 0, 1, 0, sps.size.toByte()) + sps
    }

    private val cuePoints = 30_000

    private fun bigMkv(): ByteArray {
        val ebml = el(0x1A45DFA3, str(0x4282, "matroska"))
        val info = el(0x1549A966, uint(0x2AD7B1, 1_000_000), el(0x4489, java.nio.ByteBuffer.allocate(8)
            .putDouble(cuePoints * 2_000.0).array()))
        val track = el(0xAE, uint(0xD7, 1, 1), uint(0x83, 1, 1), str(0x86, "V_MPEGH/ISO/HEVC"), el(0x63A2, hvcc()),
            el(0xE0, uint(0xB0, 1280, 2), uint(0xBA, 544, 2)))
        val tracks = el(0x1654AE6B, track)
        val void = el(0xEC, ByteArray(300 * 1024))
        val cues = Out().apply {
            val body = ByteArrayOutputStream()
            for (k in 0 until cuePoints) {
                body.write(el(0xBB, uint(0xB3, k * 2_000L), el(0xB7, uint(0xF7, 1, 1), uint(0xF1, 1_000_000L + k * 50_000L, 8))))
            }
            el(0x1C53BB6B, body.toByteArray())
        }.bytes()
        fun seek(target: Long, pos: Long) = el(0x4DBB, el(0x53AB, Out().apply { id(target) }.bytes()), uint(0x53AC, pos, 8))
        val seekHeadLen = el(0x114D9B74, seek(0x1549A966, 0), seek(0x1654AE6B, 0), seek(0x1C53BB6B, 0)).size
        val infoPos = seekHeadLen.toLong()
        val tracksPos = infoPos + info.size
        val cuesPos = tracksPos + tracks.size + void.size
        val seekHead = el(0x114D9B74, seek(0x1549A966, infoPos), seek(0x1654AE6B, tracksPos), seek(0x1C53BB6B, cuesPos))
        val segHeader = Out().apply { id(0x18538067) }.bytes() +
            byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        return ebml + segHeader + seekHead + info + tracks + void + cues
    }

    @Test
    fun theIndexReadInPartsIsTheSameIndex() {
        val file = bigMkv()
        val plain = BytesReader(file)
        val head = plain.read(0, MkvIndexReader.HEAD_BYTES)
        val a = MkvIndexReader.read(plain, head)
        val inner = BytesReader(file)
        val b = MkvIndexReader.read(SplitRangeReader(inner, 3), head)
        assertEquals(cuePoints, a.keyframes.count)
        assertArrayEquals(a.keyframes.ptsUs, b.keyframes.ptsUs)
        assertArrayEquals(a.keyframes.offset, b.keyframes.offset)
        assertEquals(a.durationUs, b.durationUs)
        assertTrue("the Cues came in parts at once (${inner.mostAtOnce})", inner.mostAtOnce >= 2)
    }

    // --- read ahead on the link as it opens ---

    private class RangeServer(private val file: ByteArray) : Dispatcher() {
        val ranges: MutableList<String> = Collections.synchronizedList(ArrayList())
        override fun dispatch(request: RecordedRequest): MockResponse {
            val range = request.getHeader("Range") ?: return MockResponse().setResponseCode(400)
            ranges += range
            val (a, b) = range.removePrefix("bytes=").split("-").map { it.toLong() }
            val end = minOf(b, file.size - 1L)
            val body = Buffer().write(file, a.toInt(), (end - a + 1).toInt())
            return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $a-$end/${file.size}").setBody(body)
        }
    }

    private val gb = 1_000_000_000L

    private fun offer(id: String, kind: CopyKind, height: Int, size: Long, durationMs: Long? = null) =
        CopyOffer(id, id, kind, height, CopyCodec.H264, false, true, size, "mkv", durationMs, "Film.2019.${height}p.WEB-DL", false, "AIOStreams")

    private class Sources(private val list: CopyOffers, private val links: Map<String, CopyLink?>) : CopySources {
        override suspend fun offers(): CopyOffers = list
        override suspend fun open(offer: CopyOffer): CopyLink? = links[offer.id]
    }

    @Test
    fun theHeadStartReadsHeaderAndIndexAsTheLinkOpens() = runBlocking {
        val file = bigMkv()
        val server = MockWebServer()
        val ranges = RangeServer(file)
        server.dispatcher = ranges
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val ownMs = cuePoints * 2_000L
            val playing = offer("p", CopyKind.DEBRID, 2160, 60 * gb, ownMs)
            val top = offer("720", CopyKind.LINK, 720, 1 * gb)
            // The first bytes came with the link's redirects: only the Cues are read.
            val link = CopyLink(server.url("/copy.mkv").toString(), emptyMap(), "the add-on's debrid link", prewarmed = true,
                head = file.copyOfRange(0, MkvIndexReader.HEAD_BYTES), length = file.size.toLong())
            val head = CopyHeadStart("tt1", pickedAtMs = 0L, client = { OkHttpClient() })
            head.start(scope, Sources(CopyOffers("Film.2019.2160p.REMUX", listOf(top), playing = playing), mapOf("720" to link)),
                null, 60 * gb)
            assertSame(link, head.take(top))
            val pre = withTimeout(20_000) { link.takePreread(20_000) }
            assertNotNull(pre)
            pre!!
            assertEquals(cuePoints, pre.index.keyframes.count)
            assertEquals(file.size.toLong(), pre.index.fileLength)
            assertTrue(pre.mkv)
            assertEquals(0L, pre.headMs)
            assertTrue("no request for the first bytes: ${ranges.ranges}", ranges.ranges.none { it.startsWith("bytes=0-") })
            // The Cues' element header, then the Cues: as many ranges at once as this server's budget leaves.
            assertTrue("${ranges.ranges}", ranges.ranges.size >= 2 && ranges.ranges.all { !it.startsWith("bytes=0-") })
            assertNull(link.preread)
            assertNull(link.takePreread(10))
            pre.reader.close()
        } finally {
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun aReadAheadNobodyTakesIsStopped() = runBlocking {
        val link = CopyLink("https://x/y", emptyMap(), "a ready link")
        val pending = CompletableDeferred<CopyPreread?>()
        link.preread = pending
        assertNull(link.takePreread(30))
        assertTrue(pending.isCancelled)
        assertNull(link.preread)

        val failed = CompletableDeferred<CopyPreread?>()
        failed.completeExceptionally(java.io.IOException("x"))
        link.preread = failed
        assertNull(link.takePreread(30))

        val later = CompletableDeferred<CopyPreread?>()
        link.preread = later
        link.dropPreread()
        assertTrue(later.isCancelled)
        delay(1)
        assertFalse(later.isActive)
    }

    @Test
    fun sameOfferMeansTheSameListEntry() {
        val a = offer("s3", CopyKind.RESOLVE, 720, 1 * gb)
        assertTrue(CopyFirst.sameOffer(a, offer("s3", CopyKind.RESOLVE, 720, 1 * gb)))
        assertFalse(CopyFirst.sameOffer(a, offer("s4", CopyKind.RESOLVE, 720, 1 * gb)))
        assertFalse(CopyFirst.sameOffer(a, offer("s3", CopyKind.RESOLVE, 720, 2 * gb)))
    }
}
