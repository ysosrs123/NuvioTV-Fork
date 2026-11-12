package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.player.thumbnail.ConnectionLedger
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.PremiumizeApi
import com.nuvio.tv.data.remote.api.TorboxApi
import com.nuvio.tv.data.remote.dto.PremiumizeDirectDownloadDto
import com.nuvio.tv.data.remote.dto.PremiumizeDirectDownloadFileDto
import com.nuvio.tv.data.remote.dto.TorboxCachedItemDto
import com.nuvio.tv.data.remote.dto.TorboxCheckCachedRequestDto
import com.nuvio.tv.data.remote.dto.TorboxCloudItemDto
import com.nuvio.tv.data.remote.dto.TorboxControlTorrentRequestDto
import com.nuvio.tv.data.remote.dto.TorboxCreateTorrentDataDto
import com.nuvio.tv.data.remote.dto.TorboxDeviceAuthorizationDto
import com.nuvio.tv.data.remote.dto.TorboxDeviceTokenDto
import com.nuvio.tv.data.remote.dto.TorboxDeviceTokenRequestDto
import com.nuvio.tv.data.remote.dto.TorboxEnvelopeDto
import com.nuvio.tv.data.remote.dto.TorboxTorrentDataDto
import com.nuvio.tv.data.remote.dto.TorboxTorrentFileDto
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Headers
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ThumbnailCopyDebridTest {
    private val hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val other = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

    private class MemoryStorage(var value: String? = null) : AddedCopyStorage {
        override fun load(): String? = value
        override fun save(value: String): Boolean {
            this.value = value
            return true
        }
    }

    @Test
    fun aCopyInTheListIsUsedAndNeverRemoved() = runBlocking {
        val api = FakeTorbox().apply { inList(5, hash) }
        val disk = MemoryStorage()
        val debrid = debrid(api, disk)
        val copy = debrid.openTorbox(direct(hash), null, null, "film")
        assertNotNull(copy)
        assertNull(copy!!.torrentId)
        assertNull(debrid.release(copy, null))
        assertFalse(api.calls.any { it.startsWith("checkcached") || it.startsWith("create") || it.startsWith("delete") })
        assertTrue(AddedCopyRecords.decode(disk.value).isEmpty())
    }

    @Test
    fun nothingIsAddedWhenNotCached() = runBlocking {
        val api = FakeTorbox()
        val disk = MemoryStorage()
        assertNull(debrid(api, disk).openTorbox(direct(hash), null, null, "film"))
        assertTrue("checkcached" in api.calls)
        assertFalse(api.calls.any { it.startsWith("create") })
        assertTrue(AddedCopyRecords.decode(disk.value).isEmpty())
    }

    @Test
    fun aCachedCopyIsAddedRecordedFirstAndRemovedAfter() = runBlocking {
        val api = FakeTorbox().apply { cached += hash }
        val disk = MemoryStorage()
        var recordedBeforeRead: Boolean? = null
        api.beforeRead = { id -> if (recordedBeforeRead == null) recordedBeforeRead = AddedCopyRecords.decode(disk.value).any { it.torrentId == id } }
        val debrid = debrid(api, disk)
        val copy = debrid.openTorbox(addon(hash), null, null, "film")!!
        assertEquals(77, copy.torrentId)
        assertEquals("true", api.addOnlyIfCached)
        assertEquals("false", api.allowZip)
        assertEquals(true, recordedBeforeRead)
        debrid.release(copy, playingHash = other)!!.join()
        assertTrue("delete 77" in api.calls)
        assertTrue(AddedCopyRecords.decode(disk.value).isEmpty())
        assertEquals(listOf("list", "checkcached", "list", "create", "get 77", "link 77"), api.calls.take(6))
        assertEquals(listOf(false, true), api.bypass.take(2))
    }

    @Test
    fun anItemTorboxAlreadyHadIsUsedAndKept() = runBlocking {
        val api = FakeTorbox().apply {
            cached += hash
            hidden(90, hash)
        }
        val disk = MemoryStorage()
        val debrid = debrid(api, disk)
        val copy = debrid.openTorbox(direct(hash), null, null, "film")!!
        assertTrue("create" in api.calls)
        assertNull(copy.torrentId)
        assertNull(debrid.release(copy, null))
        assertTrue(AddedCopyRecords.decode(disk.value).isEmpty())

        val now = System.currentTimeMillis()
        disk.value = AddedCopyRecords.encode(listOf(AddedCopy(90, hash, now - 11 * 60_000L, now - 60_000L, now)))
        debrid(api, disk).sweepLeftovers()!!.join()
        assertTrue("get 90" in api.calls)
        assertFalse(api.calls.any { it.startsWith("delete") })
        assertTrue(AddedCopyRecords.decode(disk.value).isEmpty())
    }

    @Test
    fun theWholeListIsReadBeforeAnythingIsAdded() = runBlocking {
        val api = FakeTorbox().apply {
            filler = 1000
            cached += hash
            inList(5, hash)
        }
        val copy = debrid(api, MemoryStorage()).openTorbox(direct(hash), null, null, "film")!!
        assertNull(copy.torrentId)
        assertEquals(2, api.calls.count { it == "list" })
        assertFalse(api.calls.any { it.startsWith("checkcached") || it.startsWith("create") })
    }

    @Test
    fun aListThatCannotBeReadInFullAddsNothing() = runBlocking {
        val api = FakeTorbox().apply {
            filler = 1000
            cached += hash
            listFailAtOffset = 1000
        }
        val disk = MemoryStorage()
        assertNull(debrid(api, disk).openTorbox(direct(hash), null, null, "film"))
        assertFalse(api.calls.any { it.startsWith("create") })
        assertTrue(AddedCopyRecords.decode(disk.value).isEmpty())
    }

    @Test
    fun anAddWithoutAnAnswerIsFoundAndRemoved() = runBlocking {
        val api = FakeTorbox().apply {
            cached += hash
            createThrowsAfterAdd = true
        }
        val disk = MemoryStorage()
        val debrid = debrid(api, disk)
        val copy = debrid.openTorbox(direct(hash), null, null, "film")!!
        assertEquals(77, copy.torrentId)
        assertEquals(77, AddedCopyRecords.decode(disk.value).single().torrentId)
        debrid.release(copy, null)!!.join()
        assertTrue("delete 77" in api.calls)
        assertTrue(AddedCopyRecords.decode(disk.value).isEmpty())
    }

    @Test
    fun withoutTorboxTimesNothingIsRemoved() = runBlocking {
        val api = FakeTorbox().apply {
            cached += hash
            dates = false
        }
        val disk = MemoryStorage()
        val debrid = debrid(api, disk)
        val copy = debrid.openTorbox(direct(hash), null, null, "film")!!
        assertNull(copy.torrentId)
        assertNull(debrid.release(copy, null))
        assertFalse(api.calls.any { it.startsWith("delete") })
        assertTrue(AddedCopyRecords.decode(disk.value).isEmpty())
    }

    @Test
    fun aClaimWhileTheRemovalReadsKeepsTheCopy() = runBlocking {
        val api = FakeTorbox().apply { cached += hash }
        val disk = MemoryStorage()
        val added = ThumbnailCopyAdded(AddedCopyRecords(disk))
        val debrid = debrid(api, disk, added = added)
        val copy = debrid.openTorbox(direct(hash), null, null, "film")!!
        var reads = 0
        api.beforeRead = { if (reads++ == 0) added.claim(hash) }
        debrid.release(copy, null)!!.join()
        assertEquals(1, reads)
        assertFalse(api.calls.any { it.startsWith("delete") })
    }

    @Test
    fun aCopyKeptForASourceWithoutAHashIsNeverRemoved() = runBlocking {
        val api = FakeTorbox().apply { cached += hash }
        val disk = MemoryStorage()
        val debrid = debrid(api, disk)
        val copy = debrid.openTorbox(direct(hash), null, null, "film")!!
        debrid.keep(copy)
        debrid.removeAdded().join()
        debrid(api, disk).removeAdded().join()
        assertFalse(api.calls.any { it.startsWith("delete") })
    }

    @Test
    fun clearingLeavesCopiesOlderThanSixHoursInTheList() = runBlocking {
        val now = System.currentTimeMillis()
        val api = FakeTorbox().apply { inList(77, hash, createdAtMs = now - 7 * 3_600_000L) }
        val disk = MemoryStorage(AddedCopyRecords.encode(listOf(AddedCopy(77, hash, now - 7 * 3_600_000L, 1L, now))))
        debrid(api, disk).removeAdded().join()
        assertFalse(api.calls.any { it.startsWith("get") || it.startsWith("delete") })
        assertTrue(AddedCopyRecords.decode(disk.value).isEmpty())
    }

    @Test
    fun aLeftoverOlderThanSixHoursIsStillRemoved() = runBlocking {
        val now = System.currentTimeMillis()
        val api = FakeTorbox().apply { inList(77, hash, createdAtMs = now - 7 * 3_600_000L) }
        val disk = MemoryStorage(AddedCopyRecords.encode(listOf(AddedCopy(77, hash, now - 7 * 3_600_000L, 1L, now))))
        debrid(api, disk).sweepLeftovers()!!.join()
        assertTrue("delete 77" in api.calls)
        assertTrue(AddedCopyRecords.decode(disk.value).isEmpty())
    }

    @Test
    fun aLeftoverOlderThanSixHoursIsGivenUpAfterAFailedRemoval() = runBlocking {
        val now = System.currentTimeMillis()
        val api = FakeTorbox().apply {
            inList(77, hash, createdAtMs = now - 7 * 3_600_000L)
            deleteCode = 500
        }
        val disk = MemoryStorage(AddedCopyRecords.encode(listOf(AddedCopy(77, hash, now - 7 * 3_600_000L, 1L, now))))
        debrid(api, disk).sweepLeftovers()!!.join()
        assertEquals(1, api.calls.count { it == "delete 77" })
        assertTrue(77 in api.items)
        assertTrue(AddedCopyRecords.decode(disk.value).isEmpty())
    }

    @Test
    fun anOldLeftoverTorboxMadeAtAnotherTimeIsLeftInTheList() = runBlocking {
        val now = System.currentTimeMillis()
        val addedAt = now - 7 * 3_600_000L
        val api = FakeTorbox().apply { inList(77, hash, createdAtMs = addedAt - 3_600_000L) }
        val disk = MemoryStorage(AddedCopyRecords.encode(listOf(AddedCopy(77, hash, addedAt, addedAt - 60_000L, addedAt))))
        debrid(api, disk).sweepLeftovers()!!.join()
        assertTrue("get 77" in api.calls)
        assertFalse(api.calls.any { it.startsWith("delete") })
        assertTrue(AddedCopyRecords.decode(disk.value).isEmpty())
    }

    @Test
    fun aCopyPlaybackClaimedIsKept() = runBlocking {
        val api = FakeTorbox().apply { cached += hash }
        val disk = MemoryStorage()
        val added = ThumbnailCopyAdded(AddedCopyRecords(disk))
        val debrid = debrid(api, disk, added = added)
        val copy = debrid.openTorbox(direct(hash), null, null, "film")!!
        added.claim(hash.uppercase())
        debrid.release(copy, null)?.join()
        assertFalse(api.calls.any { it.startsWith("delete") })
        assertTrue(AddedCopyRecords.decode(disk.value).isEmpty())

        val api2 = FakeTorbox().apply { cached += other }
        val debrid2 = debrid(api2, MemoryStorage())
        val copy2 = debrid2.openTorbox(direct(other), null, null, "film")!!
        debrid2.release(copy2, playingHash = other)?.join()
        assertFalse(api2.calls.any { it.startsWith("delete") })
    }

    @Test
    fun anItemWhoseHashChangedIsLeftAlone() = runBlocking {
        val api = FakeTorbox().apply { cached += hash }
        val disk = MemoryStorage()
        val debrid = debrid(api, disk)
        val copy = debrid.openTorbox(direct(hash), null, null, "film")!!
        api.items[77] = api.items[77]!!.copy(hash = other)
        debrid.release(copy, null)!!.join()
        assertFalse(api.calls.any { it.startsWith("delete") })
        assertTrue(AddedCopyRecords.decode(disk.value).isEmpty())
    }

    @Test
    fun aFailedRemovalIsRetriedAtTheNextStart() = runBlocking {
        val api = FakeTorbox().apply {
            cached += hash
            deleteCode = 500
        }
        val disk = MemoryStorage()
        val copy = debrid(api, disk).let { d ->
            d.openTorbox(direct(hash), null, null, "film")!!.also { d.release(it, null)!!.join() }
        }
        val kept = AddedCopyRecords.decode(disk.value).single()
        assertEquals(copy.torrentId, kept.torrentId)

        disk.value = AddedCopyRecords.encode(listOf(kept.copy(addedAtMs = System.currentTimeMillis() - 11 * 60_000L)))
        api.deleteCode = 200
        api.calls.clear()
        debrid(api, disk).sweepLeftovers()!!.join()
        assertTrue("delete 77" in api.calls)
        assertTrue(AddedCopyRecords.decode(disk.value).isEmpty())
    }

    @Test
    fun aCallCancelledWhileItsRequestIsCountedGivesTheSlotBack() {
        val torbox = "https://api.torbox.app/v1/api"
        val ledger = ConnectionLedger.app
        val before = ledger.inFlight(torbox)
        val thread = Executors.newSingleThreadExecutor()
        try {
            val held = CountDownLatch(1)
            val blocked = CountDownLatch(1)
            val resume = CountDownLatch(1)
            thread.execute { held.await() }
            val debrid = debrid(FakeTorbox(), MemoryStorage())
            val job = CoroutineScope(thread.asCoroutineDispatcher()).launch {
                debrid.openTorbox(direct(hash), null, null, "film")
            }
            // Queued behind the call's start: the caller's thread is busy when the counted slot comes back.
            thread.execute {
                blocked.countDown()
                resume.await()
            }
            held.countDown()
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            assertTrue(waitFor { ledger.inFlight(torbox) == before + 1 })
            job.cancel()
            resume.countDown()
            runBlocking { job.join() }
            assertTrue(waitFor { ledger.inFlight(torbox) == before })
        } finally {
            thread.shutdownNow()
        }
    }

    private fun waitFor(what: () -> Boolean): Boolean {
        val end = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < end) {
            if (what()) return true
            Thread.sleep(10)
        }
        return what()
    }

    @Test
    fun tooManyRequestsStopsAddsForTheSession() = runBlocking {
        val api = FakeTorbox().apply {
            cached += hash
            cached += other
            createCode = 429
        }
        val debrid = debrid(api, MemoryStorage())
        assertNull(debrid.openTorbox(direct(hash), null, null, "film"))
        assertNull(debrid.openTorbox(direct(other), null, null, "another film"))
        assertEquals(1, api.calls.count { it.startsWith("create") })
        assertNotNull(debrid.limits.stoppedWhy)
    }

    @Test
    fun atMostThreeAddsPerTitle() = runBlocking {
        val hashes = (1..4).map { n -> n.toString().repeat(40) }
        val api = FakeTorbox().apply { cached += hashes }
        val debrid = debrid(api, MemoryStorage())
        val copies = hashes.map { debrid.openTorbox(direct(it), null, null, "film") }
        assertEquals(3, copies.count { it?.torrentId != null })
        assertNull(copies.last())
        assertEquals(3, api.calls.count { it.startsWith("create") })
    }

    @Test
    fun premiumizeUsesADirectDownloadOnly() = runBlocking {
        val pm = mockk<PremiumizeApi>()
        coEvery { pm.directDownload(any(), match { it.contains(hash) }) } returns Response.success(
            PremiumizeDirectDownloadDto(
                status = "success",
                content = listOf(PremiumizeDirectDownloadFileDto("Film.1080p/Film.1080p.mkv", 900L, "https://pm.example/f.mkv")),
            )
        )
        coEvery { pm.directDownload(any(), match { it.contains(other) }) } returns Response.success(
            PremiumizeDirectDownloadDto(status = "error", message = "content not in cache")
        )
        val settings = DebridSettings(enabled = true, premiumizeApiKey = "pm_key")
        val debrid = debrid(FakeTorbox(), MemoryStorage(), settings, pm)
        assertEquals("https://pm.example/f.mkv", debrid.openPremiumize(addon(hash), null, null))
        assertNull(debrid.openPremiumize(addon(other), null, null))
    }

    @Test
    fun routesFollowTheAppKeys() {
        val none = DebridSettings(enabled = true)
        val tb = DebridSettings(enabled = true, torboxApiKey = "tb_key")
        val pm = DebridSettings(enabled = true, premiumizeApiKey = "pm_key")
        val d = debrid(FakeTorbox(), MemoryStorage())
        assertNull(d.routeOf(addon(hash), none))
        assertEquals(CopyRoute.Torbox, d.routeOf(addon(hash), tb))
        assertEquals(CopyRoute.Premiumize, d.routeOf(addon(hash), pm))
        assertEquals(CopyRoute.Torbox, d.routeOf(direct(hash), tb))
        assertEquals(CopyRoute.Resolve, d.routeOf(direct(hash, DebridProviders.REAL_DEBRID_ID), tb))
        assertEquals(CopyRoute.Premiumize, d.routeOf(direct(hash, DebridProviders.PREMIUMIZE_ID), pm))
        assertNull(d.routeOf(addon(null, "https://cdn.example/Film.1080p.mkv"), tb))
        assertNull(d.routeOf(addon(hash), tb.copy(enabled = false)))
        val linked = "0123456789abcdef0123456789abcdef01234567"
        val resolve = addon(null, "https://addon.example.com/resolve/torbox/key/$linked/null/1/Film.mkv")
        assertEquals(CopyRoute.Torbox, d.routeOf(resolve, tb))
        assertNull(d.routeOf(resolve, none))
    }

    private fun debrid(
        api: TorboxApi,
        disk: MemoryStorage,
        settings: DebridSettings = DebridSettings(enabled = true, torboxApiKey = "tb_key"),
        pm: PremiumizeApi = mockk(),
        added: ThumbnailCopyAdded = ThumbnailCopyAdded(AddedCopyRecords(disk)),
    ): ThumbnailCopyDebrid {
        val dataStore = mockk<DebridSettingsDataStore>()
        every { dataStore.settings } returns flowOf(settings)
        return ThumbnailCopyDebrid(dataStore, api, TorboxFileSelector(), pm, PremiumizeDirectDownloadFileSelector(), added)
    }

    private fun direct(hash: String, service: String = DebridProviders.TORBOX_ID) = Stream(
        name = "Film 1080p", title = "Film", description = null, url = null, ytId = null, infoHash = null, fileIdx = null,
        externalUrl = null, behaviorHints = null, addonName = DebridProviders.instantName(service), addonLogo = null,
        clientResolve = StreamClientResolve(
            type = "debrid", infoHash = hash, fileIdx = null, magnetUri = "magnet:?xt=urn:btih:$hash", sources = null,
            torrentName = "Film.1080p", filename = "Film.1080p.mkv", mediaType = "movie", mediaId = "tt1",
            mediaOnlyId = "tt1", title = "Film", season = null, episode = null, service = service, serviceIndex = 0,
            serviceExtension = null, isCached = true,
        ),
    )

    private fun addon(hash: String?, url: String? = null) = Stream(
        name = "Film 1080p", title = "Film", description = null, url = url, ytId = null, infoHash = hash, fileIdx = null,
        externalUrl = null, behaviorHints = null, addonName = "Add-on", addonLogo = null,
    )

    private class FakeTorbox : TorboxApi {
        val calls = mutableListOf<String>()
        val cached = mutableSetOf<String>()
        val items = LinkedHashMap<Int, TorboxTorrentDataDto>()
        private val listed = mutableSetOf<Int>()
        private val existing = HashMap<String, Int>()
        val bypass = mutableListOf<Boolean>()
        var createCode = 200
        var deleteCode = 200
        var addOnlyIfCached: String? = null
        var allowZip: String? = null
        var beforeRead: (Int) -> Unit = {}
        var filler = 0
        var listFailAtOffset: Int? = null
        var createThrowsAfterAdd = false
        var dates = true
        private var nextId = 77

        fun inList(id: Int, hash: String, createdAtMs: Long = System.currentTimeMillis() - 86_400_000L) {
            items[id] = torrent(id, hash, createdAtMs)
            listed += id
        }

        /** In the user's list but missed by the list read; adding the hash again gives this id back. */
        fun hidden(id: Int, hash: String) {
            items[id] = torrent(id, hash, System.currentTimeMillis() - 86_400_000L)
            existing[hash] = id
        }

        private fun torrent(id: Int, hash: String, createdAtMs: Long) = TorboxTorrentDataDto(
            id = id, hash = hash, name = "Film.1080p",
            files = listOf(TorboxTorrentFileDto(id = 3, name = "Film.1080p/Film.1080p.mkv", size = 900L)),
            createdAt = Instant.ofEpochMilli(createdAtMs).toString(),
        )

        private fun headers(): Headers = if (dates) {
            Headers.headersOf(
                "Date",
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date()),
            )
        } else {
            Headers.headersOf()
        }

        private fun <T> ok(body: T): Response<T> = Response.success(body, headers())

        private fun <T> notFound(): Response<T> = Response.error(
            json("""{"success":false}"""),
            okhttp3.Response.Builder().code(404).message("Not Found").protocol(okhttp3.Protocol.HTTP_1_1)
                .request(okhttp3.Request.Builder().url("https://api.torbox.app/v1/api/torrents/mylist").build())
                .headers(headers()).build(),
        )

        private fun json(text: String): ResponseBody = text.toResponseBody("application/json".toMediaType())

        override suspend fun getUser(authorization: String): Response<ResponseBody> = Response.success(json("{}"))

        override suspend fun startDeviceAuthorization(appName: String): Response<TorboxEnvelopeDto<TorboxDeviceAuthorizationDto>> =
            throw AssertionError()

        override suspend fun redeemDeviceAuthorization(body: TorboxDeviceTokenRequestDto): Response<TorboxEnvelopeDto<TorboxDeviceTokenDto>> =
            throw AssertionError()

        override suspend fun checkCached(
            authorization: String, format: String, body: TorboxCheckCachedRequestDto,
        ): Response<TorboxEnvelopeDto<Map<String, TorboxCachedItemDto>>> {
            calls += "checkcached"
            return Response.success(TorboxEnvelopeDto(success = true, data = body.hashes.filter { it in cached }.associateWith { TorboxCachedItemDto() }))
        }

        override suspend fun createTorrent(
            authorization: String, magnet: RequestBody, addOnlyIfCached: RequestBody, allowZip: RequestBody,
        ): Response<TorboxEnvelopeDto<TorboxCreateTorrentDataDto>> {
            calls += "create"
            this.addOnlyIfCached = addOnlyIfCached.text()
            this.allowZip = allowZip.text()
            if (createCode != 200) return Response.error(createCode, json("""{"success":false,"detail":"too many requests"}"""))
            val hash = magnet.text().substringAfter("btih:").substringBefore('&').lowercase()
            if (hash !in cached) return Response.error(409, json("""{"success":false}"""))
            existing[hash]?.let { return ok(TorboxEnvelopeDto(success = true, data = TorboxCreateTorrentDataDto(torrentId = it, hash = hash))) }
            val id = nextId++
            inList(id, hash, System.currentTimeMillis())
            if (createThrowsAfterAdd) throw IOException("timeout")
            return ok(TorboxEnvelopeDto(success = true, data = TorboxCreateTorrentDataDto(torrentId = id, hash = hash)))
        }

        override suspend fun controlTorrent(authorization: String, body: TorboxControlTorrentRequestDto): Response<TorboxEnvelopeDto<Any>> {
            assertEquals("delete", body.operation)
            calls += "delete ${body.torrentId}"
            if (deleteCode != 200) return Response.error(deleteCode, json("""{"success":false}"""))
            items.remove(body.torrentId)
            listed -= body.torrentId
            return Response.success(TorboxEnvelopeDto(success = true))
        }

        override suspend fun getTorrent(authorization: String, id: Int, bypassCache: Boolean): Response<TorboxEnvelopeDto<TorboxTorrentDataDto>> {
            calls += "get $id"
            beforeRead(id)
            val item = items[id] ?: return Response.error(404, json("""{"success":false}"""))
            return ok(TorboxEnvelopeDto(success = true, data = item))
        }

        override suspend fun listCloudTorrents(authorization: String): Response<TorboxEnvelopeDto<List<TorboxCloudItemDto>>> =
            throw AssertionError("the list is read page by page")

        override suspend fun listTorrents(
            authorization: String, bypassCache: Boolean, offset: Int, limit: Int,
        ): Response<TorboxEnvelopeDto<List<TorboxCloudItemDto>>> {
            calls += "list"
            bypass += bypassCache
            if (offset == listFailAtOffset) return Response.error(500, json("""{"success":false}"""))
            val all = (1..filler).map { TorboxCloudItemDto(id = 100_000 + it, hash = "f".repeat(39) + it % 10, downloadState = "completed") } +
                listed.map { TorboxCloudItemDto(id = it, hash = items[it]?.hash, downloadState = "completed") }
            val page = all.drop(offset).take(limit)
            if (page.isEmpty()) return notFound()
            return ok(TorboxEnvelopeDto(success = true, data = page))
        }

        override suspend fun listCloudUsenet(authorization: String): Response<TorboxEnvelopeDto<List<TorboxCloudItemDto>>> =
            throw AssertionError()

        override suspend fun listCloudWebDownloads(authorization: String): Response<TorboxEnvelopeDto<List<TorboxCloudItemDto>>> =
            throw AssertionError()

        override suspend fun requestDownloadLink(
            authorization: String, token: String, torrentId: Int, fileId: Int?, zipLink: Boolean, redirect: Boolean,
            appendName: Boolean,
        ): Response<TorboxEnvelopeDto<String>> {
            calls += "link $torrentId"
            return Response.success(TorboxEnvelopeDto(success = true, data = "https://tb-cdn.example/$torrentId/$fileId.mkv"))
        }

        override suspend fun requestCloudTorrentDownloadLink(
            authorization: String, token: String, torrentId: String, fileId: String?, zipLink: Boolean, redirect: Boolean,
            appendName: Boolean,
        ): Response<TorboxEnvelopeDto<String>> = throw AssertionError()

        override suspend fun requestCloudUsenetDownloadLink(
            authorization: String, token: String, usenetId: String, fileId: String?, zipLink: Boolean, redirect: Boolean,
            appendName: Boolean,
        ): Response<TorboxEnvelopeDto<String>> = throw AssertionError()

        override suspend fun requestCloudWebDownloadLink(
            authorization: String, token: String, webId: String, fileId: String?, zipLink: Boolean, redirect: Boolean,
            appendName: Boolean,
        ): Response<TorboxEnvelopeDto<String>> = throw AssertionError()

        private fun RequestBody.text(): String = Buffer().also { writeTo(it) }.readUtf8()
    }
}
