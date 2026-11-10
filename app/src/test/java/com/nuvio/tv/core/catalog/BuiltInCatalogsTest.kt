package com.nuvio.tv.core.catalog

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInCatalogsTest {
    private class Fake(
        private val prefix: String,
        list: List<Addon> = emptyList(),
        private val hidden: List<String> = emptyList(),
        private val suffix: Boolean = false
    ) : BuiltInCatalogProvider {
        override val addons: Flow<List<Addon>> = MutableStateFlow(list)
        override fun handles(baseUrl: String?) = baseUrl?.startsWith(prefix) == true
        override fun ownsAddon(addonId: String?) = addonId?.startsWith(prefix) == true
        override fun hiddenByDefaultKeys(addons: List<Addon>) = hidden
        override fun showsTypeSuffix(addonId: String) = suffix
        override suspend fun catalog(
            addonBaseUrl: String, addonId: String, addonName: String, catalogId: String, catalogName: String,
            type: String, skip: Int, extraArgs: Map<String, String>, posterPattern: String
        ): CatalogRow = mockk()
    }

    @Test
    fun `no providers change nothing`() = runBlocking {
        val none = BuiltInCatalogs.None
        assertTrue(none.addons.first().isEmpty())
        assertNull(none.provider("http://addon"))
        assertTrue(none.hiddenByDefaultKeys(emptyList()).isEmpty())
        assertFalse(none.skipsTypeSuffix("anything"))
    }

    @Test
    fun `default positions never override a saved order`() {
        val provider = object : BuiltInCatalogProvider by Fake("a") {
            override val defaultFirstKeys = listOf("new", "moved")
        }
        assertEquals(listOf("new", "x", "moved"), BuiltInCatalogs(setOf(provider)).withDefaultFirst(listOf("x", "moved")))
        assertEquals(listOf("x"), BuiltInCatalogs.None.withDefaultFirst(listOf("x")))
        val mixes = object : BuiltInCatalogProvider by provider {
            override val defaultFirstKeys = listOf("mixes")
            override val defaultFirstRank = 0
        }
        assertEquals(listOf("mixes", "new", "moved"), BuiltInCatalogs(linkedSetOf(provider, mixes)).withDefaultFirst(emptyList()))
    }

    @Test
    fun `addons of every provider are listed`() = runBlocking {
        val a = mockk<Addon>()
        val b = mockk<Addon>()
        assertEquals(listOf(a, b), BuiltInCatalogs(setOf(Fake("a", listOf(a)), Fake("b", listOf(b)))).addons.first())
    }

    @Test
    fun `a request goes to the provider that owns the address`() {
        val a = Fake("a://")
        val b = Fake("b://")
        val catalogs = BuiltInCatalogs(setOf(a, b))
        assertSame(b, catalogs.provider("b://x"))
        assertNull(catalogs.provider("http://addon"))
        assertNull(catalogs.provider(null))
    }

    @Test
    fun `hidden keys are collected and suffix follows the owner`() {
        val catalogs = BuiltInCatalogs(setOf(Fake("a", hidden = listOf("k1")), Fake("b", hidden = listOf("k2"), suffix = true)))
        assertEquals(listOf("k1", "k2"), catalogs.hiddenByDefaultKeys(emptyList()))
        assertTrue(catalogs.skipsTypeSuffix("a1"))
        assertFalse(catalogs.skipsTypeSuffix("b1"))
        assertFalse(catalogs.skipsTypeSuffix("other"))
    }
}
