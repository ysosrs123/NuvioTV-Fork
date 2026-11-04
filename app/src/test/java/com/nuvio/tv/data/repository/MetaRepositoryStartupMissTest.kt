package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.MetaDto
import com.nuvio.tv.data.remote.dto.MetaResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger

class MetaRepositoryStartupMissTest {

    private val contentId = "tt1839337"

    @Test
    fun `a lookup before any addon has loaded is not remembered as missing`() = runTest {
        val addons = MutableStateFlow<List<Addon>>(emptyList())
        val calls = AtomicInteger()
        val repository = newRepository(addons) {
            calls.incrementAndGet()
            Response.success(MetaResponseDto(meta = MetaDto(id = contentId, type = "series", name = "The Voice")))
        }

        val early = repository.getMetaFromAllAddons("series", contentId).last() as NetworkResult.Error
        assertNull(early.code)

        addons.value = listOf(addon("a"))
        assertTrue(repository.getMetaFromAllAddons("series", contentId).last() is NetworkResult.Success)
        assertEquals(1, calls.get())
    }

    @Test
    fun `with no addons a repeat lookup answers without waiting for them`() = runTest {
        val addons = MutableStateFlow<List<Addon>>(emptyList())
        val repository = newRepository(addons) { error("no addon to ask") }

        repository.getMetaFromAllAddons("series", contentId).last()
        val before = testScheduler.currentTime
        val repeat = repository.getMetaFromAllAddons("series", contentId).last() as NetworkResult.Error

        assertEquals(before, testScheduler.currentTime)
        assertNull(repeat.code)
    }

    @Test
    fun `a remembered miss is asked again once the addon set changes`() = runTest {
        val addons = MutableStateFlow(listOf(addon("a")))
        val calls = AtomicInteger()
        val repository = newRepository(addons) {
            calls.incrementAndGet()
            Response.error(404, "".toResponseBody("application/json".toMediaType()))
        }

        repeat(2) { repository.getMetaFromAllAddons("series", contentId).last() }
        assertEquals(1, calls.get())

        addons.value = listOf(addon("a"), addon("b"))
        repository.getMetaFromAllAddons("series", contentId).last()
        assertEquals(3, calls.get())
    }

    private fun addon(key: String) = Addon(
        id = "test.addon.$key",
        name = "Test Addon $key",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://$key.addon.example",
        catalogs = emptyList(),
        types = listOf(ContentType.MOVIE, ContentType.SERIES),
        rawTypes = listOf("movie", "series"),
        resources = listOf(AddonResource(name = "meta", types = listOf("movie", "series"), idPrefixes = null)),
        idPrefixes = listOf("tt")
    )

    private fun newRepository(
        addons: MutableStateFlow<List<Addon>>,
        answer: suspend () -> Response<MetaResponseDto>
    ): MetaRepositoryImpl {
        val context = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "Episode"
            every { getString(any(), *anyVararg()) } returns "No supported addon"
        }
        val api = mockk<AddonApi>().also { coEvery { it.getMeta(any()) } coAnswers { answer() } }
        val addonRepository = mockk<AddonRepository>(relaxed = true) {
            every { getInstalledAddons() } returns addons
        }
        return MetaRepositoryImpl(context = context, api = api, addonRepository = addonRepository, healthStore = mockk(relaxed = true), serverCatalog = mockk(relaxed = true))
    }
}
