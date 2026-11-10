package com.nuvio.tv.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSectionsTest {
    private val data = BackupSectionData(settings = "general", secrets = "playlists", device = "buffers")
    private val merged = BackupSections.merged(emptyMap(), mapOf("live" to data))

    @Test
    fun `each piece goes to its own part`() {
        assertEquals("general", merged[BackupPart.SETTINGS]!!.single().text)
        assertEquals("playlists", merged[BackupPart.SECRETS]!!.single().text)
        assertEquals("buffers", merged[BackupPart.DEVICE]!!.single().text)
    }

    @Test
    fun `existing entries are kept`() {
        val existing = BackupEntry(BackupStoreKind.PREFS, "network_dns", values = mapOf("a" to BackupValue.Flag(true)))
        val result = BackupSections.merged(mapOf(BackupPart.SETTINGS to listOf(existing)), mapOf("live" to data))
        assertEquals(listOf(existing.store, "live"), result[BackupPart.SETTINGS]!!.map { it.store })
    }

    @Test
    fun `section passwords never reach the open part of the file`() {
        val document = BackupDocument(
            BackupHeader(1L, "x", BackupDevice("a", "b", 34), emptyList(), setOf(BackupPart.SETTINGS)),
            merged.filterKeys { it != BackupPart.SECRETS }
        )
        assertTrue("playlists" !in BackupFormat.encode(document))
    }

    @Test
    fun `sound and picture text comes back only when ticked`() {
        assertNull(BackupSections.read("live", merged, withDevice = false).device)
        assertEquals("buffers", BackupSections.read("live", merged, withDevice = true).device)
    }

    @Test
    fun `without the PIN only the open pieces come back`() {
        val open = merged.filterKeys { it != BackupPart.SECRETS }
        val read = BackupSections.read("live", open, withDevice = false)
        assertEquals("general", read.settings)
        assertNull(read.secrets)
    }

    @Test
    fun `summary leaves out a section with nothing in the file`() {
        val sections = mapOf("live" to data, "only" to BackupSectionData(secrets = "s"), "none" to BackupSectionData())
        assertEquals(mapOf("live" to true, "only" to true), BackupSections.summary(sections, sealed = true))
        assertEquals(mapOf("live" to false), BackupSections.summary(sections, sealed = false))
    }

    @Test
    fun `sections survive the file and an unknown store kind is skipped`() {
        val header = BackupHeader(
            1L, "x", BackupDevice("a", "b", 34), emptyList(), setOf(BackupPart.SETTINGS), mapOf("live" to true, "other" to false)
        )
        val text = BackupFormat.encode(BackupDocument(header, merged.filterKeys { it != BackupPart.SECRETS }))
        val back = BackupFormat.decode(text)
        assertEquals(header.sections, back.header.sections)
        assertEquals("general", BackupSections.read("live", back.entries, withDevice = false).settings)
        val unknown = text.replace("\"kind\":\"section\"", "\"kind\":\"later\"")
        assertTrue(BackupFormat.decode(unknown).entries[BackupPart.SETTINGS].orEmpty().isEmpty())
    }

    @Test
    fun `sections are not in the settings catalogue, so the normal restore skips them`() {
        assertNull(BackupCatalog.spec(BackupStoreKind.SECTION, "live"))
    }
}
