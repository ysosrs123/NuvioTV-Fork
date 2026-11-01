package com.nuvio.tv.data.local

import android.content.Context
import com.google.gson.Gson
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ContinueWatchingSnapshotWriteTest {

    private val gson = Gson()

    private fun inProgress(id: String) =
        CachedInProgressItem(id, "movie", id, null, null, null, id, null, null, null, 10, 100, 1, 10f)

    private fun nextUp(id: String) = CachedNextUpItem(
        id, "series", id, null, null, null, "$id:1:2", 1, 2, null,
        thumbnail = null, lastWatched = 1, sortTimestamp = 1
    )

    // Built the way Gson builds an entry whose JSON has no contentId key.
    private val inProgressWithoutId: CachedInProgressItem = gson.fromJson(
        """{"contentType":"movie","name":"No id","videoId":"x","position":10,"duration":100,"lastWatched":1}""",
        CachedInProgressItem::class.java
    )

    private val nextUpWithoutId: CachedNextUpItem = gson.fromJson(
        """{"contentType":"series","name":"No id","videoId":"x:1:2","season":1,"episode":2,"lastWatched":1,"sortTimestamp":1}""",
        CachedNextUpItem::class.java
    )

    private fun <T> withCache(block: suspend (ContinueWatchingEnrichmentCache, File) -> T): T = runBlocking {
        val dir = Files.createTempDirectory("cw-write-").toFile()
        try {
            val context = mockk<Context>()
            every { context.filesDir } returns dir
            val manager = mockk<ProfileManager>()
            every { manager.activeProfileId } returns MutableStateFlow(1)
            block(ContinueWatchingEnrichmentCache(context, manager), dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an in-progress entry without an id is never written`() = withCache { cache, dir ->
        cache.saveInProgressSnapshot(listOf(inProgressWithoutId, inProgress("tt1")), force = true)

        val json = File(dir, "cw_enrichment/inprogress_1.json").readText()
        assertEquals(1, Regex("\"contentId\"").findAll(json).count())
        assertFalse(json.contains("No id"))
        assertEquals(listOf("tt1"), cache.getInProgressSnapshot(1).map { it.contentId })
    }

    @Test
    fun `a next-up entry without an id is never written`() = withCache { cache, dir ->
        cache.saveNextUpSnapshot(listOf(nextUpWithoutId, nextUp("tt2")), force = true)

        val json = File(dir, "cw_enrichment/nextup_1.json").readText()
        assertFalse(json.contains("No id"))
        assertEquals(listOf("tt2"), cache.getNextUpSnapshot(1).map { it.contentId })
    }

    @Test
    fun `a blank id is not saved`() = withCache { cache, _ ->
        cache.saveInProgressSnapshot(listOf(inProgress(" "), inProgress("tt3")), force = true)
        assertEquals(listOf("tt3"), cache.getInProgressSnapshot(1).map { it.contentId })
    }

    @Test
    fun `custom add-on and local file ids are kept`() = withCache { cache, _ ->
        val ids = listOf("am9fel:feltext", "local:file-1", "tmdb:123")
        cache.saveInProgressSnapshot(ids.map(::inProgress), force = true)
        assertEquals(ids, cache.getInProgressSnapshot(1).map { it.contentId })
    }

    @Test
    fun `savable checks see the missing fields Gson leaves null`() {
        assertFalse(inProgressWithoutId.isSavable())
        assertFalse(nextUpWithoutId.isSavable())
        assertTrue(inProgress("tt1").isSavable())
        assertTrue(nextUp("tt1").isSavable())
        val logs = mutableListOf<String>()
        val kept = listOf(inProgressWithoutId, inProgress("tt1"))
            .savableSnapshot("in-progress", CachedInProgressItem::isSavable) { logs += it }
        assertEquals(1, kept.size)
        assertEquals(1, logs.size)
    }

    @Test
    fun `snapshots saved by earlier release builds are read and saved back under the real names`() = withCache { cache, dir ->
        File(dir, "cw_enrichment").mkdirs()
        File(dir, "cw_enrichment/inprogress_1.json").writeText(
            """[{"a":"tt0073195","b":"movie","c":"Jaws","d":"https://img/p.jpg","g":"tt0073195","k":600000,"l":7440000,
            "m":1760000000000,"n":8.1,"q":8.1,"r":["Thriller"],"s":"1975","t":"en","u":false}]"""
        )
        File(dir, "cw_enrichment/nextup_1.json").writeText(
            """[{"a":"tt0903747","b":"series","c":"Breaking Bad","g":"tt0903747:2:3","h":2,"i":3,"j":"Bit by a Dead Bee",
            "n":true,"p":1760000000000,"q":8.6,"r":["Drama"],"t":1760000000001,"v":false,"w":false,"x":2,"y":2,"z":"en","A":false}]"""
        )

        val progress = cache.getInProgressSnapshot(1).single()
        assertEquals("tt0073195", progress.contentId)
        assertEquals("Jaws", progress.name)
        assertEquals(600000L, progress.position)
        assertEquals(7440000L, progress.duration)
        assertEquals(listOf("Thriller"), progress.genres)
        assertEquals(false, progress.ratingVisible)
        val next = cache.getNextUpSnapshot(1).single()
        assertEquals("tt0903747", next.contentId)
        assertEquals("tt0903747:2:3", next.videoId)
        assertEquals(2, next.season)
        assertEquals(3, next.episode)
        assertEquals(1760000000001L, next.sortTimestamp)
        assertEquals(2, next.seedEpisode)
        assertEquals(false, next.ratingVisible)

        cache.saveInProgressSnapshot(listOf(progress), force = true)
        cache.saveNextUpSnapshot(listOf(next), force = true)
        val savedIp = File(dir, "cw_enrichment/inprogress_1.json").readText()
        val savedNu = File(dir, "cw_enrichment/nextup_1.json").readText()
        assertTrue(savedIp.contains("\"contentId\":\"tt0073195\"") && savedIp.contains("\"ratingVisible\":false"))
        assertTrue(savedNu.contains("\"contentId\":\"tt0903747\"") && savedNu.contains("\"seedEpisode\":2"))
        assertEquals(progress, cache.getInProgressSnapshot(1).single())
        assertEquals(next, cache.getNextUpSnapshot(1).single())
    }

    @Test
    fun `snapshots under the real names are read`() = withCache { cache, dir ->
        File(dir, "cw_enrichment").mkdirs()
        File(dir, "cw_enrichment/inprogress_1.json").writeText(
            """[{"contentId":"am9fel:feltext","contentType":"movie","name":"FEL test","videoId":"am9fel:feltext",
            "position":1000,"duration":2000,"lastWatched":1}]"""
        )
        val progress = cache.getInProgressSnapshot(1).single()
        assertEquals("am9fel:feltext", progress.contentId)
        assertEquals(1000L, progress.position)
    }
}
