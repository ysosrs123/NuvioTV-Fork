package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.ContentType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AddonManifestFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun addon(id: String, catalogs: Int) = Addon(
        id = id, name = "Addon $id", version = "1.0.0", description = null, logo = null, baseUrl = "https://example.test/$id",
        catalogs = (1..catalogs).map {
            CatalogDescriptor(
                type = ContentType.MOVIE, id = "c$it", name = "Catalogue $it",
                extra = listOf(CatalogExtra("genre", options = listOf("Drama", "Comédie", "紀錄片")))
            )
        },
        types = listOf(ContentType.MOVIE), resources = emptyList()
    )

    @Test
    fun `what is written comes back the same`() {
        val file = AddonManifestFile(File(folder.root, "addon-manifests.json"))
        assertFalse(file.exists())
        assertNull(file.read())
        val manifests = mapOf("https://example.test/a" to addon("a", 3), "https://example.test/b" to addon("b", 40))
        file.write(manifests)
        assertTrue(file.exists())
        assertTrue(file.sizeBytes() > 0)
        assertEquals(manifests, file.read())
    }

    @Test
    fun `a later write replaces the earlier one and leaves no temporary file`() {
        val target = File(folder.root, "addon-manifests.json")
        val file = AddonManifestFile(target)
        file.write(mapOf("https://example.test/a" to addon("a", 2)))
        file.write(mapOf("https://example.test/b" to addon("b", 1)))
        assertEquals(setOf("https://example.test/b"), file.read()?.keys)
        assertEquals(listOf("addon-manifests.json"), folder.root.list()?.toList())
    }

    @Test
    fun `set-up size names counts and the largest files only`() {
        val counts = ArcSetupSize.AddonCounts(installed = 23, enabled = 21, catalogs = 412, largestCatalogs = 180, extraOptions = 9000)
        assertEquals("23 (21 on), catalogues 412 (largest add-on 180), filter options 9000", ArcSetupSize.addonText(counts))
        assertEquals("library 12, watched 3400, watch progress 250 (this profile)", ArcSetupSize.listText(ArcSetupSize.ListCounts(12, 3400, 250)))

        assertNull(ArcSetupSize.savedData(null))
        assertNull(ArcSetupSize.savedData(folder.root))
        val prefs = folder.newFolder("shared_prefs")
        File(prefs, "small.xml").writeBytes(ByteArray(1000))
        File(prefs, "big.xml").writeBytes(ByteArray(3 * 1024 * 1024))
        assertEquals("3.0 MB in 2 files; largest big.xml 3.0 MB", ArcSetupSize.savedData(folder.root))
    }
}
