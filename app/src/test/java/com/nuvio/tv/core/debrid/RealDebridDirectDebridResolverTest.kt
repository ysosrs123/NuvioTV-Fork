package com.nuvio.tv.core.debrid

import android.content.Context
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.AllDebridApi
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.dto.RealDebridAddTorrentDto
import com.nuvio.tv.data.remote.dto.RealDebridDeviceCodeDto
import com.nuvio.tv.data.remote.dto.RealDebridDeviceCredentialsDto
import com.nuvio.tv.data.remote.dto.RealDebridTokenDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentListItemDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentFileDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentInfoDto
import com.nuvio.tv.data.remote.dto.RealDebridUnrestrictLinkDto
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class RealDebridDirectDebridResolverTest {
    @Test
    fun `resolve adds magnet selects file and unrestricts generated link`() = runTest {
        val api = FakeRealDebridApi(
            addResponse = Response.success(201, RealDebridAddTorrentDto(id = "rd_torrent")),
            infoResponses = mutableListOf(
                Response.success(
                    RealDebridTorrentInfoDto(
                        id = "rd_torrent",
                        status = "waiting_files_selection",
                        files = listOf(
                            RealDebridTorrentFileDto(id = 1, path = "/wrong.mkv", bytes = 10),
                            RealDebridTorrentFileDto(id = 7, path = "/right.mkv", bytes = 20)
                        )
                    )
                ),
                Response.success(
                    RealDebridTorrentInfoDto(
                        id = "rd_torrent",
                        status = "downloaded",
                        files = listOf(RealDebridTorrentFileDto(id = 7, path = "/right.mkv", bytes = 20, selected = 1)),
                        links = listOf("https://real-debrid.example/link")
                    )
                )
            ),
            unrestrictResponse = Response.success(
                RealDebridUnrestrictLinkDto(
                    filename = "right.mkv",
                    filesize = 20,
                    download = "https://cdn.example/right.mkv"
                )
            )
        )
        val resolver = resolver(api)

        val result = resolver.resolve(stream(fileIdx = 7), season = null, episode = null)

        assertTrue(result is DirectDebridResolveResult.Success)
        result as DirectDebridResolveResult.Success
        assertEquals("https://cdn.example/right.mkv", result.url)
        assertEquals("right.mkv", result.filename)
        assertEquals(20L, result.videoSize)
        assertEquals(1, api.addCalls)
        assertEquals(2, api.infoCalls)
        assertEquals(1, api.selectCalls)
        assertEquals("7", api.lastSelectedFiles)
        assertEquals(1, api.unrestrictCalls)
        assertEquals(0, api.deleteCalls)
    }

    @Test
    fun `resolve deletes transient torrent when cached listing is stale`() = runTest {
        val api = FakeRealDebridApi(
            addResponse = Response.success(201, RealDebridAddTorrentDto(id = "rd_torrent")),
            infoResponses = mutableListOf(
                Response.success(
                    RealDebridTorrentInfoDto(
                        id = "rd_torrent",
                        status = "waiting_files_selection",
                        files = listOf(RealDebridTorrentFileDto(id = 7, path = "/right.mkv", bytes = 20))
                    )
                ),
                Response.success(
                    RealDebridTorrentInfoDto(
                        id = "rd_torrent",
                        status = "downloading",
                        files = listOf(RealDebridTorrentFileDto(id = 7, path = "/right.mkv", bytes = 20, selected = 1)),
                        links = emptyList()
                    )
                )
            )
        )
        val resolver = resolver(api)

        val result = resolver.resolve(stream(fileIdx = 7), season = null, episode = null)

        assertTrue(result is DirectDebridResolveResult.NotCached)
        assertEquals(1, api.deleteCalls)
        assertEquals(0, api.unrestrictCalls)
    }

    @Test
    fun `title already in the account plays without adding it again`() = runTest {
        val api = FakeRealDebridApi(
            addResponse = Response.success(201, RealDebridAddTorrentDto(id = "unused")),
            infoResponses = mutableListOf(
                Response.success(
                    RealDebridTorrentInfoDto(
                        id = "rd_pack",
                        status = "downloaded",
                        files = listOf(
                            RealDebridTorrentFileDto(id = 1, path = "/Show.S01E01.mkv", bytes = 10, selected = 1),
                            RealDebridTorrentFileDto(id = 2, path = "/Show.S01E02.mkv", bytes = 10, selected = 1),
                            RealDebridTorrentFileDto(id = 3, path = "/sample.mkv", bytes = 1, selected = 0)
                        ),
                        links = listOf("https://real-debrid.example/e1", "https://real-debrid.example/e2")
                    )
                )
            ),
            unrestrictResponse = Response.success(RealDebridUnrestrictLinkDto(download = "https://cdn.example/e2.mkv")),
            accountTorrents = listOf(
                RealDebridTorrentListItemDto(id = "rd_pack", hash = "ABCDEF", status = "downloaded")
            )
        )

        val result = resolver(api).resolve(stream(fileIdx = null, filename = null), season = 1, episode = 2)

        assertTrue(result is DirectDebridResolveResult.Success)
        assertEquals(0, api.addCalls)
        assertEquals("https://real-debrid.example/e2", api.lastUnrestricted)
        assertEquals(0, api.deleteCalls)
    }

    @Test
    fun `account-only lookup never adds a title`() = runTest {
        val api = FakeRealDebridApi(
            addResponse = Response.success(201, RealDebridAddTorrentDto(id = "rd_torrent")),
            infoResponses = mutableListOf()
        )

        val result = resolver(api).resolve(stream(fileIdx = 7), season = null, episode = null, mayAddToAccount = false)

        assertTrue(result is DirectDebridResolveResult.NotCached)
        assertEquals(0, api.addCalls)
        assertEquals(0, api.deleteCalls)
    }

    @Test
    fun `episodes of a season pack are selected together`() = runTest {
        val api = FakeRealDebridApi(
            addResponse = Response.success(201, RealDebridAddTorrentDto(id = "rd_pack")),
            infoResponses = mutableListOf(
                Response.success(
                    RealDebridTorrentInfoDto(
                        id = "rd_pack",
                        status = "waiting_files_selection",
                        files = listOf(
                            RealDebridTorrentFileDto(id = 1, path = "/Show.S01E01.mkv", bytes = 10),
                            RealDebridTorrentFileDto(id = 2, path = "/Show.S01E02.mkv", bytes = 10),
                            RealDebridTorrentFileDto(id = 3, path = "/Show.S01E03.mkv", bytes = 10),
                            RealDebridTorrentFileDto(id = 4, path = "/notes.txt", bytes = 1)
                        )
                    )
                ),
                Response.success(
                    RealDebridTorrentInfoDto(
                        id = "rd_pack",
                        status = "downloaded",
                        files = listOf(
                            RealDebridTorrentFileDto(id = 1, path = "/Show.S01E01.mkv", bytes = 10, selected = 1),
                            RealDebridTorrentFileDto(id = 2, path = "/Show.S01E02.mkv", bytes = 10, selected = 1),
                            RealDebridTorrentFileDto(id = 3, path = "/Show.S01E03.mkv", bytes = 10, selected = 1)
                        ),
                        links = listOf("https://rd/e1", "https://rd/e2", "https://rd/e3")
                    )
                )
            ),
            unrestrictResponse = Response.success(RealDebridUnrestrictLinkDto(download = "https://cdn.example/e3.mkv"))
        )

        val result = resolver(api).resolve(stream(fileIdx = null, filename = null), season = 1, episode = 3)

        assertTrue(result is DirectDebridResolveResult.Success)
        assertEquals("1,2,3", api.lastSelectedFiles)
        assertEquals("https://rd/e3", api.lastUnrestricted)
        assertEquals(0, api.deleteCalls)
    }

    @Test
    fun `an uncached pack is retried as the single episode`() = runTest {
        val waiting = Response.success(
            RealDebridTorrentInfoDto(
                id = "rd_pack",
                status = "waiting_files_selection",
                files = listOf(
                    RealDebridTorrentFileDto(id = 1, path = "/Show.S01E01.mkv", bytes = 10),
                    RealDebridTorrentFileDto(id = 2, path = "/Show.S01E02.mkv", bytes = 10)
                )
            )
        )
        val api = FakeRealDebridApi(
            addResponse = Response.success(201, RealDebridAddTorrentDto(id = "rd_pack")),
            infoResponses = mutableListOf(
                waiting,
                Response.success(RealDebridTorrentInfoDto(id = "rd_pack", status = "downloading")),
                waiting,
                Response.success(
                    RealDebridTorrentInfoDto(
                        id = "rd_pack",
                        status = "downloaded",
                        files = listOf(RealDebridTorrentFileDto(id = 2, path = "/Show.S01E02.mkv", bytes = 10, selected = 1)),
                        links = listOf("https://rd/e2")
                    )
                )
            ),
            unrestrictResponse = Response.success(RealDebridUnrestrictLinkDto(download = "https://cdn.example/e2.mkv"))
        )

        val result = resolver(api).resolve(stream(fileIdx = null, filename = null), season = 1, episode = 2)

        assertTrue(result is DirectDebridResolveResult.Success)
        assertEquals(2, api.addCalls)
        assertEquals("2", api.lastSelectedFiles)
        assertEquals(1, api.deleteCalls)
    }

    @Test
    fun `links follow the selected files in order`() {
        val info = RealDebridTorrentInfoDto(
            files = listOf(
                RealDebridTorrentFileDto(id = 5, path = "/b.mkv", selected = 1),
                RealDebridTorrentFileDto(id = 2, path = "/a.mkv", selected = 1),
                RealDebridTorrentFileDto(id = 9, path = "/c.nfo", selected = 0)
            ),
            links = listOf("link-a", "link-b")
        )
        assertEquals("link-a", info.linkFor(RealDebridTorrentFileDto(id = 2)))
        assertEquals("link-b", info.linkFor(RealDebridTorrentFileDto(id = 5)))
        assertEquals(null, info.linkFor(RealDebridTorrentFileDto(id = 9)))
        val packedTogether = info.copy(links = listOf("one-archive-link"))
        assertEquals(null, packedTogether.linkFor(RealDebridTorrentFileDto(id = 5)))
    }

    @Test
    fun `a device sign-in is stored as one value and read back`() {
        val credential = RealDebridDeviceCredential("cid", "secret", "refresh")
        assertEquals(credential, RealDebridDeviceCredential.parse(credential.encode()))
        assertEquals(null, RealDebridDeviceCredential.parse("PRIVATETOKEN123"))
    }

    private fun resolver(api: RealDebridApi): RealDebridDirectDebridResolver {
        val dataStore = mockk<DebridSettingsDataStore>()
        every { dataStore.settings } returns flowOf(
            DebridSettings(
                enabled = true,
                realDebridApiKey = "rd_token"
            )
        )
        val auth = RealDebridAuth(mockk<Context>(relaxed = true), api)
        return RealDebridDirectDebridResolver(
            dataStore = dataStore,
            api = api,
            fileSelector = RealDebridFileSelector(),
            auth = auth,
            accounts = DebridAccounts(mockk<Context>(relaxed = true), dataStore, api, auth, mockk<AllDebridApi>())
        )
    }

    private fun stream(fileIdx: Int?, filename: String? = "right.mkv"): Stream = Stream(
        name = "Direct Debrid",
        title = "Title",
        description = "Description",
        url = null,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = DebridProviders.instantName(DebridProviders.REAL_DEBRID_ID),
        addonLogo = null,
        clientResolve = StreamClientResolve(
            type = "debrid",
            infoHash = "abcdef",
            fileIdx = fileIdx,
            magnetUri = "magnet:?xt=urn:btih:abcdef",
            sources = null,
            torrentName = "Torrent",
            filename = filename,
            mediaType = "movie",
            mediaId = "tt1",
            mediaOnlyId = "tt1",
            title = "Title",
            season = null,
            episode = null,
            service = "realdebrid",
            serviceIndex = 0,
            serviceExtension = null,
            isCached = true
        )
    )

    private class FakeRealDebridApi(
        private val addResponse: Response<RealDebridAddTorrentDto>,
        private val infoResponses: MutableList<Response<RealDebridTorrentInfoDto>>,
        private val unrestrictResponse: Response<RealDebridUnrestrictLinkDto> = Response.success(
            RealDebridUnrestrictLinkDto(download = "")
        ),
        private val accountTorrents: List<RealDebridTorrentListItemDto> = emptyList()
    ) : RealDebridApi {
        var lastUnrestricted: String? = null
        var addCalls = 0
        var infoCalls = 0
        var selectCalls = 0
        var unrestrictCalls = 0
        var deleteCalls = 0
        var lastSelectedFiles: String? = null

        override suspend fun getUser(authorization: String): Response<ResponseBody> {
            return Response.success("{}".toResponseBody())
        }

        override suspend fun addMagnet(
            authorization: String,
            magnet: String
        ): Response<RealDebridAddTorrentDto> {
            addCalls++
            return addResponse
        }

        override suspend fun getTorrentInfo(
            authorization: String,
            id: String
        ): Response<RealDebridTorrentInfoDto> {
            infoCalls++
            return infoResponses.removeAt(0)
        }

        override suspend fun selectFiles(
            authorization: String,
            id: String,
            files: String
        ): Response<ResponseBody> {
            selectCalls++
            lastSelectedFiles = files
            return Response.success("".toResponseBody())
        }

        override suspend fun unrestrictLink(
            authorization: String,
            link: String
        ): Response<RealDebridUnrestrictLinkDto> {
            unrestrictCalls++
            lastUnrestricted = link
            return unrestrictResponse
        }

        override suspend fun deleteTorrent(
            authorization: String,
            id: String
        ): Response<ResponseBody> {
            deleteCalls++
            return Response.success("".toResponseBody())
        }

        override suspend fun listTorrents(
            authorization: String,
            limit: Int,
            page: Int
        ): Response<List<RealDebridTorrentListItemDto>> = Response.success(accountTorrents)

        override suspend fun startDeviceAuthorization(
            clientId: String,
            newCredentials: String
        ): Response<RealDebridDeviceCodeDto> = Response.success(RealDebridDeviceCodeDto())

        override suspend fun deviceCredentials(
            clientId: String,
            deviceCode: String
        ): Response<RealDebridDeviceCredentialsDto> = Response.success(RealDebridDeviceCredentialsDto())

        override suspend fun token(
            clientId: String,
            clientSecret: String,
            code: String,
            grantType: String
        ): Response<RealDebridTokenDto> = Response.success(RealDebridTokenDto())
    }
}
