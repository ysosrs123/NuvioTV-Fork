package com.nuvio.tv.ui.screens.home

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingMetaStoreTest {
    private val dir: File = Files.createTempDirectory("cwmeta").toFile()
    private val store = ContinueWatchingMetaStore(dir)

    private fun meta(id: String) = CwMetaSummary(
        id = id, name = "Show $id", poster = null, backdropUrl = null, logo = null, description = "d",
        genres = listOf("Drama"), releaseInfo = "2024", imdbRating = 7.5f, language = null, country = null,
        videos = listOf(CwVideoSummary("$id:1:1", "Pilot", "2024-01-01", null, 1, 1, null)),
        status = "Continuing"
    )

    @Test fun `saved details come back with their episode list`() {
        store.save(1, "ctx", listOf(CwMetaStoreEntry("series:a", 2_000L, meta("a"))))
        val loaded = store.load(1, "ctx", nowMs = 1_000L)
        assertEquals(1, loaded.size)
        assertEquals(meta("a"), loaded.single().meta)
    }

    @Test fun `details past their time are not read back`() {
        store.save(1, "ctx", listOf(
            CwMetaStoreEntry("series:a", 2_000L, meta("a")),
            CwMetaStoreEntry("movie:b", 500L, meta("b"))
        ))
        assertEquals(listOf("series:a"), store.load(1, "ctx", nowMs = 1_000L).map { it.key })
    }

    @Test fun `another profile or other settings read nothing`() {
        store.save(1, "ctx", listOf(CwMetaStoreEntry("series:a", 2_000L, meta("a"))))
        assertTrue(store.load(2, "ctx", nowMs = 1_000L).isEmpty())
        assertTrue(store.load(1, "other", nowMs = 1_000L).isEmpty())
    }

    @Test fun `a damaged file reads as empty`() {
        File(dir, "cw_meta_1.json").writeText("{not json")
        assertTrue(store.load(1, "ctx", nowMs = 1_000L).isEmpty())
    }
}
