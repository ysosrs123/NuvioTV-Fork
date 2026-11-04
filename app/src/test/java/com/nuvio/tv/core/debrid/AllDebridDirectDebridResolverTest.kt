package com.nuvio.tv.core.debrid

import android.content.Context
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.AllDebridApi
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.dto.AllDebridDelayedDto
import com.nuvio.tv.data.remote.dto.AllDebridEnvelopeDto
import com.nuvio.tv.data.remote.dto.AllDebridFileNodeDto
import com.nuvio.tv.data.remote.dto.AllDebridFilesDataDto
import com.nuvio.tv.data.remote.dto.AllDebridMagnetFilesDto
import com.nuvio.tv.data.remote.dto.AllDebridPinCheckDto
import com.nuvio.tv.data.remote.dto.AllDebridPinDto
import com.nuvio.tv.data.remote.dto.AllDebridUnlockDto
import com.nuvio.tv.data.remote.dto.AllDebridUploadDataDto
import com.nuvio.tv.data.remote.dto.AllDebridUploadedMagnetDto
import com.nuvio.tv.data.remote.dto.AllDebridUserDataDto
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class AllDebridDirectDebridResolverTest {
    @Test
    fun `a ready magnet plays the matching episode`() = runTest {
        val api = FakeAllDebridApi(ready = true)

        val result = resolver(api).resolve(stream(), season = 1, episode = 2)

        assertTrue(result is DirectDebridResolveResult.Success)
        result as DirectDebridResolveResult.Success
        assertEquals("https://cdn.example/e2.mkv", result.url)
        assertEquals("https://alldebrid.example/e2", api.lastUnlocked)
        assertEquals(0, api.deleteCalls)
    }

    @Test
    fun `a magnet that is not ready is removed again when the app added it`() = runTest {
        val api = FakeAllDebridApi(ready = false, uploadDateSec = System.currentTimeMillis() / 1000L)

        val result = resolver(api).resolve(stream(), season = 1, episode = 2)

        assertTrue(result is DirectDebridResolveResult.NotCached)
        assertEquals(1, api.deleteCalls)
    }

    @Test
    fun `a magnet that was already in the account is never removed`() = runTest {
        val api = FakeAllDebridApi(ready = false, uploadDateSec = 1_000L)

        val result = resolver(api).resolve(stream(), season = 1, episode = 2)

        assertTrue(result is DirectDebridResolveResult.NotCached)
        assertEquals(0, api.deleteCalls)
    }

    @Test
    fun `a magnet listed in the account before the upload is never removed`() = runTest {
        val api = FakeAllDebridApi(
            ready = false,
            uploadDateSec = System.currentTimeMillis() / 1000L,
            listedIds = listOf(42L)
        )

        val result = resolver(api).resolve(stream(), season = 1, episode = 2)

        assertTrue(result is DirectDebridResolveResult.NotCached)
        assertEquals(0, api.deleteCalls)
    }

    @Test
    fun `a ready magnet the app added is removed when no link comes out of it`() = runTest {
        val api = FakeAllDebridApi(ready = true, uploadDateSec = System.currentTimeMillis() / 1000L, unlockFails = true)

        val result = resolver(api).resolve(stream(), season = 1, episode = 2)

        assertTrue(result !is DirectDebridResolveResult.Success)
        assertEquals(1, api.deleteCalls)
    }

    @Test
    fun `without the account list a magnet added now is still removed`() = runTest {
        val api = FakeAllDebridApi(ready = false, uploadDateSec = System.currentTimeMillis() / 1000L, listFails = true)

        val result = resolver(api).resolve(stream(), season = 1, episode = 2)

        assertTrue(result is DirectDebridResolveResult.NotCached)
        assertEquals(1, api.deleteCalls)
    }

    @Test
    fun `without the account list an older magnet is kept`() = runTest {
        val api = FakeAllDebridApi(ready = false, uploadDateSec = 1_000L, listFails = true)

        val result = resolver(api).resolve(stream(), season = 1, episode = 2)

        assertTrue(result is DirectDebridResolveResult.NotCached)
        assertEquals(0, api.deleteCalls)
    }

    @Test
    fun `two lookups at once share one read of the account list`() = runTest {
        val api = FakeAllDebridApi(ready = true, listedIds = listOf(1L, 2L), listDelayMs = 300L)
        val accounts = accounts(api)

        val first = async { accounts.items(DebridProviders.ALL_DEBRID_ID, "ad_key") }
        val second = async { accounts.items(DebridProviders.ALL_DEBRID_ID, "ad_key") }

        assertEquals(2, first.await()?.size)
        assertEquals(2, second.await()?.size)
        assertEquals(1, api.listCalls)
    }

    @Test
    fun `fields the app does not use are skipped when reading the list`() {
        val parsed = parseAllDebridMagnets(
            """{"data":{"other":[1,{"a":[2]}],"magnets":[{"id":9,"links":[{"x":1}],"size":12.0,"uploadDate":"77"},5]},"status":"success"}"""
        )
        assertEquals(9L, parsed?.single()?.id)
        assertEquals(12L, parsed?.single()?.size)
        assertEquals(77L, parsed?.single()?.uploadDate)
    }

    @Test
    fun `a file index from the add-on is not used against the folder tree`() {
        val files = listOf(
            AllDebridFile(path = "Pack/B.mkv", size = 5, link = "b"),
            AllDebridFile(path = "Pack/A.mkv", size = 9, link = "a")
        )
        val chosen = AllDebridFileSelector().selectFile(
            files,
            stream().clientResolve!!.copy(fileIdx = 0, torrentName = null),
            season = null,
            episode = null
        )
        assertEquals("a", chosen?.link)
    }

    @Test
    fun `account-only lookup does not upload`() = runTest {
        val api = FakeAllDebridApi(ready = true)

        val result = resolver(api).resolve(stream(), season = 1, episode = 2, mayAddToAccount = false)

        assertTrue(result is DirectDebridResolveResult.NotCached)
        assertEquals(0, api.uploadCalls)
    }

    @Test
    fun `magnet status is read as a list or as one object`() {
        val list = parseAllDebridMagnets(
            """{"status":"success","data":{"magnets":[{"id":7,"filename":"A","statusCode":4,"hash":"ABC"}]}}"""
        )
        val single = parseAllDebridMagnets(
            """{"status":"success","data":{"magnets":{"id":8,"filename":"B","statusCode":1,"uploadDate":5}}}"""
        )
        assertEquals(7L, list?.single()?.id)
        assertTrue(list!!.single().isReady)
        assertEquals(5L, single?.single()?.uploadDate)
        assertNull(parseAllDebridMagnets("""{"status":"error","error":{"code":"AUTH_BAD_APIKEY"}}"""))
    }

    @Test
    fun `nested folders are flattened with their paths`() {
        val files = listOf(
            AllDebridFileNodeDto(
                name = "Show S01",
                entries = listOf(
                    AllDebridFileNodeDto(name = "Show.S01E01.mkv", size = 1, link = "l1"),
                    AllDebridFileNodeDto(name = "Subs", entries = listOf(AllDebridFileNodeDto(name = "en.srt", link = "l2")))
                )
            )
        ).flattenAllDebridFiles()
        assertEquals(listOf("Show S01/Show.S01E01.mkv", "Show S01/Subs/en.srt"), files.map { it.path })
    }

    private fun dataStore(): DebridSettingsDataStore {
        val dataStore = mockk<DebridSettingsDataStore>()
        every { dataStore.settings } returns flowOf(DebridSettings(enabled = true, allDebridApiKey = "ad_key"))
        return dataStore
    }

    private fun accounts(api: AllDebridApi): DebridAccounts {
        val rd = mockk<RealDebridApi>()
        return DebridAccounts(
            mockk<Context>(relaxed = true),
            dataStore(),
            rd,
            RealDebridAuth(mockk<Context>(relaxed = true), rd),
            api
        )
    }

    private fun resolver(api: AllDebridApi): AllDebridDirectDebridResolver =
        AllDebridDirectDebridResolver(dataStore(), api, AllDebridFileSelector(), accounts(api))

    private fun stream(): Stream = Stream(
        name = "Direct Debrid",
        title = "Title",
        description = null,
        url = null,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = DebridProviders.instantName(DebridProviders.ALL_DEBRID_ID),
        addonLogo = null,
        clientResolve = StreamClientResolve(
            type = "debrid",
            infoHash = "abcdef",
            fileIdx = null,
            magnetUri = "magnet:?xt=urn:btih:abcdef",
            sources = null,
            torrentName = "Show S01",
            filename = null,
            mediaType = "series",
            mediaId = "tt1",
            mediaOnlyId = "tt1",
            title = "Title",
            season = null,
            episode = null,
            service = "alldebrid",
            serviceIndex = 0,
            serviceExtension = null,
            isCached = true
        )
    )

    private class FakeAllDebridApi(
        private val ready: Boolean,
        private val uploadDateSec: Long = 0L,
        private val listedIds: List<Long> = emptyList(),
        private val listFails: Boolean = false,
        private val unlockFails: Boolean = false,
        private val listDelayMs: Long = 0L
    ) : AllDebridApi {
        var listCalls = 0
        var uploadCalls = 0
        var deleteCalls = 0
        var lastUnlocked: String? = null

        override suspend fun getPin(): Response<AllDebridEnvelopeDto<AllDebridPinDto>> =
            Response.success(AllDebridEnvelopeDto(status = "success", data = AllDebridPinDto()))

        override suspend fun checkPin(check: String, pin: String): Response<AllDebridEnvelopeDto<AllDebridPinCheckDto>> =
            Response.success(AllDebridEnvelopeDto(status = "success", data = AllDebridPinCheckDto()))

        override suspend fun getUser(authorization: String): Response<AllDebridEnvelopeDto<AllDebridUserDataDto>> =
            Response.success(AllDebridEnvelopeDto(status = "success", data = AllDebridUserDataDto()))

        override suspend fun uploadMagnets(
            authorization: String,
            magnets: List<String>
        ): Response<AllDebridEnvelopeDto<AllDebridUploadDataDto>> {
            uploadCalls++
            return Response.success(
                AllDebridEnvelopeDto(
                    status = "success",
                    data = AllDebridUploadDataDto(
                        magnets = listOf(AllDebridUploadedMagnetDto(hash = "abcdef", ready = ready, id = 42L))
                    )
                )
            )
        }

        override suspend fun listMagnets(authorization: String): Response<ResponseBody> {
            listCalls++
            if (listDelayMs > 0) delay(listDelayMs)
            if (listFails) return Response.error(500, "".toResponseBody())
            val magnets = listedIds.joinToString(",") { """{"id":$it,"statusCode":1,"uploadDate":$uploadDateSec}""" }
            return Response.success("""{"status":"success","data":{"magnets":[$magnets]}}""".toResponseBody())
        }

        override suspend fun magnetStatus(authorization: String, id: Long): Response<ResponseBody> =
            Response.success(
                """{"status":"success","data":{"magnets":{"id":$id,"statusCode":1,"uploadDate":$uploadDateSec}}}"""
                    .toResponseBody()
            )

        override suspend fun magnetFiles(
            authorization: String,
            ids: List<Long>
        ): Response<AllDebridEnvelopeDto<AllDebridFilesDataDto>> =
            Response.success(
                AllDebridEnvelopeDto(
                    status = "success",
                    data = AllDebridFilesDataDto(
                        magnets = listOf(
                            AllDebridMagnetFilesDto(
                                id = 42L,
                                files = listOf(
                                    AllDebridFileNodeDto(
                                        name = "Show S01",
                                        entries = listOf(
                                            AllDebridFileNodeDto(name = "Show.S01E01.mkv", size = 10, link = "https://alldebrid.example/e1"),
                                            AllDebridFileNodeDto(name = "Show.S01E02.mkv", size = 10, link = "https://alldebrid.example/e2")
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )

        override suspend fun deleteMagnet(authorization: String, id: Long): Response<AllDebridEnvelopeDto<Any>> {
            deleteCalls++
            return Response.success(AllDebridEnvelopeDto(status = "success"))
        }

        override suspend fun unlockLink(
            authorization: String,
            link: String
        ): Response<AllDebridEnvelopeDto<AllDebridUnlockDto>> {
            lastUnlocked = link
            if (unlockFails) return Response.error(503, "".toResponseBody())
            return Response.success(
                AllDebridEnvelopeDto(status = "success", data = AllDebridUnlockDto(link = "https://cdn.example/e2.mkv", filesize = 10))
            )
        }

        override suspend fun delayedLink(authorization: String, id: Long): Response<AllDebridEnvelopeDto<AllDebridDelayedDto>> =
            Response.success(AllDebridEnvelopeDto(status = "success", data = AllDebridDelayedDto(status = 3)))
    }
}
