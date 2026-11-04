package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.MetaDto
import com.nuvio.tv.data.remote.dto.MetaResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * A Shield with six media servers asked two addons for about 1,800 titles in its first minute and
 * got 2,000 429s back, because every lookup kept asking a host that had just said "slow down".
 */
class MetaRepositoryRateLimitTest {

    private val requested = Collections.synchronizedList(mutableListOf<String>())

    @Test
    fun `a 429 pauses that host for the following lookups`() = runTest {
        val repository = newRepository(
            apiAnswering { url -> if (url.startsWith("https://busy.example")) tooMany("30") else found(url) },
            addon("first", "https://busy.example/one"),
            addon("second", "https://calm.example")
        )

        assertTrue(repository.getMetaFromAllAddons("movie", "tt0000001").last() is NetworkResult.Success)
        assertTrue(repository.getMetaFromAllAddons("movie", "tt0000002").last() is NetworkResult.Success)

        assertEquals(1, requested.count { it.startsWith("https://busy.example") })
        assertEquals(2, requested.count { it.startsWith("https://calm.example") })
    }

    @Test
    fun `a second addon on the rate limited host is skipped in the same lookup`() = runTest {
        val repository = newRepository(
            apiAnswering { tooMany(null) },
            addon("one", "https://aio.example/config-one"),
            addon("two", "https://aio.example/config-two")
        )

        val result = repository.getMetaFromAllAddons("movie", "tt0000001").last()

        assertEquals(1, requested.size)
        assertNull("a rate limited lookup stays retryable", (result as NetworkResult.Error).code)
    }

    @Test
    fun `a paused host is asked again once the pause is over`() = runTest {
        var limited = true
        val repository = newRepository(
            apiAnswering { url -> if (limited) tooMany("1") else found(url) },
            addon("one", "https://busy.example")
        )
        repository.getMetaFromAllAddons("movie", "tt0000001").last()
        repository.getMetaFromAllAddons("movie", "tt0000002").last()
        assertEquals(1, requested.size)

        limited = false
        repository.rateLimits.clear()

        assertTrue(repository.getMetaFromAllAddons("movie", "tt0000002").last() is NetworkResult.Success)
        assertEquals(2, requested.size)
    }

    @Test
    fun `a caller joining a failed lookup does not run it again`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } coAnswers {
            requested += firstArg<String>()
            entered.complete(Unit)
            release.await()
            Response.error(500, "".toResponseBody("application/json".toMediaType()))
        }
        val repository = newRepository(api, addon("one", "https://flaky.example"))

        val results = withContext(Dispatchers.Default) {
            val first = async { repository.getMetaFromAllAddons("movie", "tt0000001").last() }
            entered.await()
            val second = async {
                repository.getMetaFromAllAddons("movie", "tt0000001").first { it !is NetworkResult.Loading }
            }
            Thread.sleep(100)
            release.complete(Unit)
            listOf(first.await(), second.await())
        }

        assertEquals(1, requested.size)
        assertTrue(results.all { it is NetworkResult.Error && it.code == null })
    }

    @Test
    fun `the requesting feature is counted without changing the result`() = runTest {
        val repository = newRepository(apiAnswering { url -> found(url) }, addon("one", "https://calm.example"))

        val result = withContext(MetaCaller("server-art")) {
            repository.getMetaFromAllAddons("movie", "tt0000001").last()
        }

        assertTrue(result is NetworkResult.Success)
        assertEquals(1, requested.size)
    }

    @Test
    fun `an AIOStreams error item is not taken as the title and pauses its host`() = runTest {
        val repository = newRepository(
            apiAnswering { url ->
                if (url.startsWith("https://aio.example")) errorItem() else found(url)
            },
            addon("aios", "https://aio.example/config"),
            addon("cinemeta", "https://calm.example")
        )

        val first = repository.getMetaFromAllAddons("series", "tt0000001").last()
        val second = repository.getMetaFromAllAddons("series", "tt0000002").last()

        assertEquals("Found tt0000001", (first as NetworkResult.Success).data.name)
        assertTrue(second is NetworkResult.Success)
        assertEquals(1, requested.count { it.startsWith("https://aio.example") })
    }

    @Test
    fun `an addon answering not found to title after title stops being asked`() = runTest {
        val repository = newRepository(
            apiAnswering { notFound() },
            addon("aios", "https://aio.example/config")
        )

        repeat(12) { n -> repository.getMetaFromAllAddons("series", "tt10000%02d".format(n)).last() }

        assertEquals(8, requested.size)
    }

    @Test
    fun `an addon whose meta covers only its own ids stops being asked once it keeps finding nothing`() = runTest {
        val repository = newRepository(apiAnswering { notFound() }, ownIdsOnly("aios", "https://aio.example/config"))

        val first = repository.getMetaFromAllAddons("movie", "tt0000001").last()
        repository.getMetaFromAllAddons("movie", "tt0000001").last()
        repeat(12) { n -> repository.getMetaFromAllAddons("movie", "tt20000%02d".format(n)).last() }

        assertEquals(8, requested.size)
        assertEquals(NetworkResult.META_NOT_FOUND_CODE, (first as NetworkResult.Error).code)
    }

    @Test
    fun `an addon that answers for ids its manifest leaves out still gives the details`() = runTest {
        val repository = newRepository(
            apiAnswering { url -> if (url.startsWith("https://aio.example")) notFound() else found(url) },
            ownIdsOnly("aios", "https://aio.example/config"),
            ownIdsOnly("bingecat", "https://cat.example/config")
        )

        val results = (1..12).map { n -> repository.getMetaFromAllAddons("series", "tt30000%02d".format(n)).last() }

        assertTrue(results.all { it is NetworkResult.Success })
        assertEquals(12, requested.count { it.startsWith("https://cat.example") })
        assertEquals(8, requested.count { it.startsWith("https://aio.example") })
    }

    @Test
    fun `a catalog addon without declared meta is asked when AIOStreams only has meta for its own ids`() = runTest {
        val bingecat = addon("bingecat", "https://cat.example/config").copy(
            catalogs = listOf(
                CatalogDescriptor(type = ContentType.MOVIE, id = "ai-movies", name = "AI movies"),
                CatalogDescriptor(type = ContentType.SERIES, id = "ai-series", name = "AI series")
            ),
            resources = listOf(AddonResource(name = "catalog", types = listOf("movie", "series"), idPrefixes = null)),
            idPrefixes = emptyList()
        )
        val repository = newRepository(
            apiAnswering { url -> if (url.startsWith("https://aio.example")) notFound() else found(url) },
            ownIdsOnly("aios", "https://aio.example/config"),
            bingecat
        )

        val results = (1..12).map { n -> repository.getMetaFromAllAddons("series", "tt40000%02d".format(n)).last() } +
            repository.getMetaFromAllAddons("movie", "tt22084616").last()

        assertTrue(results.all { it is NetworkResult.Success })
        assertEquals(13, requested.count { it.startsWith("https://cat.example") })
        assertEquals(0, requested.count { it.startsWith("https://aio.example") })
    }

    @Test
    fun `a resting AIOStreams leaves the other addons to answer`() = runTest {
        val seriesOnly = addon("cat", "https://cat.example/config").copy(
            catalogs = listOf(CatalogDescriptor(type = ContentType.SERIES, id = "ai-series", name = "AI series")),
            resources = listOf(AddonResource(name = "catalog", types = listOf("series"), idPrefixes = null)),
            rawTypes = listOf("series"),
            idPrefixes = emptyList()
        )
        val repository = newRepository(
            apiAnswering { url -> if (url.startsWith("https://aio.example")) notFound() else found(url) },
            ownIdsOnly("aios", "https://aio.example/config"),
            seriesOnly
        )

        repeat(10) { n -> repository.getMetaFromAllAddons("movie", "tt50000%02d".format(n)).last() }
        assertEquals(8, requested.count { it.startsWith("https://aio.example") })

        val show = repository.getMetaFromAllAddons("series", "tt1839337").last()

        assertTrue(show is NetworkResult.Success)
        assertEquals(8, requested.count { it.startsWith("https://aio.example") })
    }

    private fun ownIdsOnly(key: String, baseUrl: String) = addon(key, baseUrl).copy(
        resources = listOf(
            AddonResource(name = "stream", types = listOf("movie", "series"), idPrefixes = listOf("tt")),
            AddonResource(name = "meta", types = listOf("other"), idPrefixes = listOf("realdebrid", "torbox"))
        ),
        idPrefixes = emptyList()
    )

    private fun errorItem(): Response<MetaResponseDto> = Response.success(
        MetaResponseDto(
            meta = MetaDto(
                id = "aiostreamserror.%7B%22errorDescription%22%3A%22Rate%20Limit%20Exceeded%22%7D",
                type = "series",
                name = "[x] AIOStreams - Error"
            )
        )
    )

    private fun notFound(): Response<MetaResponseDto> =
        Response.error(404, "{}".toResponseBody("application/json".toMediaType()))

    private fun found(url: String): Response<MetaResponseDto> {
        val id = url.substringAfterLast('/').substringBefore(".json")
        return Response.success(MetaResponseDto(meta = MetaDto(id = id, type = "movie", name = "Found $id")))
    }

    private fun tooMany(retryAfter: String?): Response<MetaResponseDto> {
        val raw = okhttp3.Response.Builder()
            .code(429)
            .message("Too Many Requests")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("https://busy.example/").build())
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .build()
        return Response.error("".toResponseBody("application/json".toMediaType()), raw)
    }

    private fun apiAnswering(answer: (String) -> Response<MetaResponseDto>): AddonApi =
        mockk<AddonApi>().also { api ->
            coEvery { api.getMeta(any()) } coAnswers {
                val url = firstArg<String>()
                requested += url
                answer(url)
            }
        }

    private fun addon(key: String, baseUrl: String) = Addon(
        id = "test.addon.$key",
        name = "Test Addon $key",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = emptyList(),
        types = listOf(ContentType.MOVIE, ContentType.SERIES),
        rawTypes = listOf("movie", "series"),
        resources = listOf(AddonResource(name = "meta", types = listOf("movie", "series"), idPrefixes = null)),
        idPrefixes = listOf("tt")
    )

    private fun newRepository(api: AddonApi, vararg addons: Addon): MetaRepositoryImpl {
        val context = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "Episode"
            every { getString(any(), *anyVararg()) } returns "No supported addon"
        }
        val addonRepository = mockk<AddonRepository>(relaxed = true) {
            every { getInstalledAddons() } returns flowOf(addons.toList())
        }
        return MetaRepositoryImpl(
            context = context,
            api = api,
            addonRepository = addonRepository,
            healthStore = mockk(relaxed = true),
            serverCatalog = mockk(relaxed = true)
        )
    }
}
