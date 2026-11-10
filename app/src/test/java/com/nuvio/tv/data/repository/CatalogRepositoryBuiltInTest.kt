package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.catalog.BuiltInCatalogProvider
import com.nuvio.tv.core.catalog.BuiltInCatalogs
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRepositoryBuiltInTest {
    private val row = CatalogRow(
        addonId = "built", addonName = "Built", addonBaseUrl = "built://x", catalogId = "c", catalogName = "C",
        type = ContentType.MOVIE, items = emptyList()
    )

    private fun repository(failure: Exception? = null) = CatalogRepositoryImpl(
        context = mockk<Context>(relaxed = true),
        api = mockk(),
        layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore> {
            every { customPosterUrlPattern } returns flowOf("")
            every { customPosterEnabledScreens } returns flowOf(emptySet())
        },
        healthStore = mockk(relaxed = true),
        serverCatalog = mockk(relaxed = true),
        builtInCatalogs = BuiltInCatalogs(setOf(object : BuiltInCatalogProvider {
            override val addons: Flow<List<Addon>> = flowOf(emptyList())
            override fun handles(baseUrl: String?) = baseUrl?.startsWith("built://") == true
            override fun ownsAddon(addonId: String?) = addonId == "built"
            override suspend fun catalog(
                addonBaseUrl: String, addonId: String, addonName: String, catalogId: String, catalogName: String,
                type: String, skip: Int, extraArgs: Map<String, String>, posterPattern: String
            ): CatalogRow {
                failure?.let { throw it }
                return row
            }
        }))
    )

    private suspend fun CatalogRepositoryImpl.rows() =
        getCatalog("built://x", "built", "Built", "c", "C", "movie").toList()

    @Test
    fun `built-in row comes from its provider without the network`() = runBlocking {
        val results = repository().rows()
        assertEquals(row, (results.last() as NetworkResult.Success).data)
    }

    @Test
    fun `a failing provider reports an error`() = runBlocking {
        val results = repository(IllegalStateException("broken")).rows()
        assertEquals("broken", (results.last() as NetworkResult.Error).message)
        assertTrue(results.none { it is NetworkResult.Success })
    }
}
