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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger

class MetaRepositorySharedCacheTest {

    private val contentId = "tt1839337"
    private val calls = AtomicInteger()

    @Test
    fun `a title fetched for the primary addon is not asked for again by the all addons lookup`() = runTest {
        val repository = newRepository()

        assertTrue(repository.getMetaFromPrimaryAddon("series", contentId).last() is NetworkResult.Success)
        assertTrue(repository.getMetaFromAllAddons("series", contentId).last() is NetworkResult.Success)

        assertEquals(1, calls.get())
    }

    @Test
    fun `a title fetched by the all addons lookup is not asked for again for the primary addon`() = runTest {
        val repository = newRepository()

        assertTrue(repository.getMetaFromAllAddons("series", contentId).last() is NetworkResult.Success)
        assertTrue(repository.getMetaFromPrimaryAddon("series", contentId).last() is NetworkResult.Success)

        assertEquals(1, calls.get())
    }

    private fun newRepository(): MetaRepositoryImpl {
        val addon = Addon(
            id = "test.addon",
            name = "Test Addon",
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = "https://a.addon.example",
            catalogs = emptyList(),
            types = listOf(ContentType.MOVIE, ContentType.SERIES),
            rawTypes = listOf("movie", "series"),
            resources = listOf(AddonResource(name = "meta", types = listOf("movie", "series"), idPrefixes = null)),
            idPrefixes = listOf("tt")
        )
        val context = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "Episode"
            every { getString(any(), *anyVararg()) } returns "No supported addon"
        }
        val api = mockk<AddonApi>().also {
            coEvery { it.getMeta(any()) } coAnswers {
                calls.incrementAndGet()
                Response.success(MetaResponseDto(meta = MetaDto(id = contentId, type = "series", name = "The Voice")))
            }
        }
        val addonRepository = mockk<AddonRepository>(relaxed = true) {
            every { getInstalledAddons() } returns MutableStateFlow(listOf(addon))
        }
        return MetaRepositoryImpl(context = context, api = api, addonRepository = addonRepository, healthStore = mockk(relaxed = true), serverCatalog = mockk(relaxed = true))
    }
}
