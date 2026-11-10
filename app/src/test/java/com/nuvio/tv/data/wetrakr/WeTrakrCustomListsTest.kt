package com.nuvio.tv.data.wetrakr

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeTrakrCustomListsTest {
    private val now = 1_793_750_400_000L
    private val dune = WeTrakrListEntry(WeTrakrTitle(WeTrakrType.MOVIE, WeTrakrIds(wetrakr = 126, imdb = "tt15239678"), "Dune"))
    private val silo = WeTrakrListEntry(WeTrakrTitle(WeTrakrType.SHOW, WeTrakrIds(wetrakr = 900, imdb = "tt14688458"), "Silo"))
    private val stamps = mapOf("all" to "2026-11-03T00:00:00.000Z", "lists.all" to "2026-11-03T00:00:00.000Z")

    private class Remote(
        var stamps: Map<String, String>,
        var lists: List<WeTrakrCustomList> = emptyList(),
        var items: Map<Long, List<WeTrakrListEntry>?> = emptyMap()
    ) : WeTrakrSyncRemote {
        val calls = mutableListOf<String>()
        var failLists = false

        override suspend fun activities() = WeTrakrActivities(stamps)
        override suspend fun watchedMovies() = emptyList<WeTrakrWatchedMovie>()
        override suspend fun watchedEpisodes() = emptyList<WeTrakrWatchedEpisode>()
        override suspend fun list(list: WeTrakrList, target: String, since: String?) = emptyList<WeTrakrListEntry>()
        override suspend fun playback() = emptyList<WeTrakrPlayback>()
        override suspend fun journal(since: String) = WeTrakrJournalPage(emptyList(), null)
        override suspend fun showIds(showId: Long): WeTrakrIds? = null
        override suspend fun customLists(): List<WeTrakrCustomList> {
            calls += "lists"
            if (failLists) throw WeTrakrDecodingException()
            return lists
        }
        override suspend fun customListItems(listId: Long): List<WeTrakrListEntry>? {
            calls += "items:$listId"
            return items[listId]
        }
    }

    private fun synced(checked: Long?, lists: List<WeTrakrCustomList> = emptyList()) = WeTrakrSnapshot(
        accountId = 42,
        activities = stamps,
        journalMark = "2026-11-03T00:00:00.000Z",
        isInitialized = true,
        customLists = lists,
        customListsCheckedAtEpochMs = checked
    )

    @Test
    fun `the first sync after an update reads the lists even though nothing else moved`() = runTest {
        val remote = Remote(stamps, listOf(WeTrakrCustomList(7, "Mine", updatedAt = "a")), mapOf(7L to listOf(dune)))

        val next = WeTrakrSyncEngine(remote) { now }.synchronize(synced(checked = null))

        assertEquals(listOf(dune), next.customLists.single().items)
        assertEquals(now, next.customListsCheckedAtEpochMs)
    }

    @Test
    fun `when nothing moved no list is read`() = runTest {
        val remote = Remote(stamps)

        WeTrakrSyncEngine(remote) { now }.synchronize(synced(checked = now - 1_000))

        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `only the list that changed downloads its items again`() = runTest {
        val held = listOf(
            WeTrakrCustomList(7, "Same", updatedAt = "a", items = listOf(dune)),
            WeTrakrCustomList(8, "Changed", updatedAt = "a", items = listOf(dune))
        )
        val remote = Remote(
            stamps + ("lists.all" to "2026-11-04T00:00:00.000Z"),
            listOf(WeTrakrCustomList(7, "Same", updatedAt = "a"), WeTrakrCustomList(8, "Changed", updatedAt = "b")),
            mapOf(8L to listOf(silo))
        )

        val next = WeTrakrSyncEngine(remote) { now }.synchronize(synced(now - 1_000, held))

        assertEquals(listOf("lists", "items:8"), remote.calls)
        assertEquals(listOf(dune), next.customLists[0].items)
        assertEquals(listOf(silo), next.customLists[1].items)
    }

    @Test
    fun `a dynamic list that refilled itself is noticed within a day`() = runTest {
        val remote = Remote(stamps, listOf(WeTrakrCustomList(7, "Dynamic", updatedAt = "b")), mapOf(7L to listOf(silo)))
        val held = listOf(WeTrakrCustomList(7, "Dynamic", updatedAt = "a", items = listOf(dune)))

        val next = WeTrakrSyncEngine(remote) { now }.synchronize(synced(now - 25L * 60 * 60 * 1_000, held))

        assertEquals(listOf(silo), next.customLists.single().items)
    }

    @Test
    fun `a list that was deleted goes away`() = runTest {
        val remote = Remote(stamps + ("lists.all" to "2026-11-04T00:00:00.000Z"))
        val held = listOf(WeTrakrCustomList(7, "Gone", updatedAt = "a", items = listOf(dune)))

        val next = WeTrakrSyncEngine(remote) { now }.synchronize(synced(now - 1_000, held))

        assertTrue(next.customLists.isEmpty())
    }

    @Test
    fun `a list that can't be read comes back empty without stopping the others`() = runTest {
        val remote = Remote(
            stamps + ("lists.all" to "b"),
            listOf(WeTrakrCustomList(7, "Locked", updatedAt = "a"), WeTrakrCustomList(8, "Fine", updatedAt = "a")),
            mapOf(7L to null, 8L to listOf(dune))
        )

        val next = WeTrakrSyncEngine(remote) { now }.synchronize(synced(now - 1_000))

        assertTrue(next.customLists[0].items.isEmpty())
        assertEquals(listOf(dune), next.customLists[1].items)
    }

    @Test
    fun `a list that fails to load keeps its items and loads again on the next sync`() = runTest {
        val held = listOf(WeTrakrCustomList(7, "Mine", updatedAt = "a", items = listOf(dune)))
        val remote = Remote(
            stamps + ("lists.all" to "b"),
            listOf(WeTrakrCustomList(7, "Mine", updatedAt = "b")),
            mapOf(7L to null)
        )

        val failed = WeTrakrSyncEngine(remote) { now }.synchronize(synced(now - 1_000, held))
        assertEquals(listOf(dune), failed.customLists.single().items)

        remote.items = mapOf(7L to listOf(dune, silo))
        val later = now + 25L * 60 * 60 * 1_000
        val next = WeTrakrSyncEngine(remote) { later }.synchronize(failed)

        assertEquals(listOf(dune, silo), next.customLists.single().items)
        assertEquals("b", next.customLists.single().updatedAt)
    }

    @Test
    fun `a failure keeps the lists already held and the rest of the sync goes on`() = runTest {
        val remote = Remote(stamps + ("lists.all" to "b")).apply { failLists = true }
        val held = listOf(WeTrakrCustomList(7, "Mine", updatedAt = "a", items = listOf(dune)))

        val next = WeTrakrSyncEngine(remote) { now }.synchronize(synced(now - 1_000, held))

        assertEquals(held, next.customLists)
        assertEquals(now - 1_000, next.customListsCheckedAtEpochMs)
    }

    @Test
    fun `locked lists are left out`() {
        val lists = decodeWeTrakrCustomLists(
            """[{"id":7,"name":"Mine","privacy":"private","list_updated_at":"a","kind":"dynamic"},
                {"id":8,"name":"Locked","locked":true},{"name":"No id"},{"id":9,"name":" "}]"""
        )

        assertEquals(listOf(7L), lists.map { it.id })
        assertTrue(lists.single().isPrivate)
        assertEquals("a", lists.single().updatedAt)
    }

    @Test
    fun `only films and shows are kept from a list`() {
        val rows = Json.parseToJsonElement(
            """[{"type":"movie","id":1,"ids":{"imdb":"tt1000001"},"title":"Film","poster_path":"/f.jpg","list_item_created_at":"2026"},
                {"type":"show","id":2,"ids":{"imdb":"tt1000002"},"name":"Show"},
                {"type":"season","id":3,"ids":{"imdb":"tt3"}},
                {"type":"episode","id":4,"ids":{"imdb":"tt4"}},
                {"type":"person","id":5,"ids":{"imdb":"nm5"}}]"""
        ).jsonArray.map { it.jsonObject }

        val entries = decodeWeTrakrCustomListItems(rows)

        assertEquals(listOf("tt1000001", "tt1000002"), entries.map { it.title.ids.imdb })
        assertEquals(WeTrakrType.SHOW, entries[1].title.type)
        assertEquals("2026", entries[0].listedAt)
    }

    @Test
    fun `list entries show under their own tab in the list's order`() {
        val snapshot = synced(now, listOf(WeTrakrCustomList(7, "Mine", items = listOf(silo, dune))))
            .copy(lists = mapOf(WeTrakrList.PLANNING to listOf(dune)))

        val entries = snapshot.libraryEntries()

        val film = entries.single { it.imdbId == "tt15239678" }
        assertEquals(setOf("wetrakr:status:planning", "wetrakr:list:7"), film.listKeys)
        assertEquals(1, film.listRanks["wetrakr:list:7"])
        assertEquals(0, entries.single { it.imdbId == "tt14688458" }.listRanks["wetrakr:list:7"])
        assertFalse("wetrakr:status:planning" in entries.single { it.imdbId == "tt14688458" }.listKeys)
    }

    @Test
    fun `a title only on a custom list does not count as already there for a transfer`() {
        val snapshot = synced(now, listOf(WeTrakrCustomList(7, "Mine", items = listOf(silo, dune))))
            .copy(lists = mapOf(WeTrakrList.PLANNING to listOf(dune)))

        val kept = snapshot.libraryEntries().withoutWeTrakrCustomListOnly()

        assertEquals(listOf("tt15239678"), kept.map { it.imdbId })
    }

    @Test
    fun `old saved snapshots without lists still load`() {
        val snapshot = Json { ignoreUnknownKeys = true }.decodeFromString(WeTrakrSnapshot.serializer(), """{"accountId":42}""")

        assertTrue(snapshot.customLists.isEmpty())
        assertEquals(null, snapshot.customListsCheckedAtEpochMs)
    }
}
