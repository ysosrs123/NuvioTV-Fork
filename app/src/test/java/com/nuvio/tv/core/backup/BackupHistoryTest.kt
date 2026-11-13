package com.nuvio.tv.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupHistoryTest {

    private val saved = """{"id":"tt1","type":"movie","name":"One","poster":"https://image.tmdb.org/t/p/w500/a.jpg",""" +
        """"background":"https://api.ratingposterdb.com/t0-secret/imdb/backdrop-default/tt1.jpg",""" +
        """"addonBaseUrl":"https://addon.example/secret-key","genres":["Drama"],"addedAt":5}"""

    private fun entry(store: String, profileId: Int, values: Map<String, BackupValue>) =
        BackupEntry(BackupStoreKind.PROFILE_STORE, store, profileId, values)

    @Test
    fun `add-on addresses and keyed artwork links are left out of saved titles`() {
        val scrubbed = BackupHistory.scrub(mapOf("library_items" to BackupValue.Texts(setOf(saved))))
        val item = Json.parseToJsonElement((scrubbed.getValue("library_items") as BackupValue.Texts).value.single()).jsonObject

        assertFalse("addonBaseUrl" in item)
        assertFalse("background" in item)
        assertEquals("https://image.tmdb.org/t/p/w500/a.jpg", item.getValue("poster").jsonPrimitive.content)
        assertEquals("One", item.getValue("name").jsonPrimitive.content)
        assertEquals("5", item.getValue("addedAt").jsonPrimitive.content)
    }

    @Test
    fun `watch progress keeps positions and tracker links`() {
        val map = """{"tt2:1:3":{"contentId":"tt2","contentType":"series","name":"Two","season":1,"episode":3,""" +
            """"position":61000,"duration":2400000,"lastWatched":1790000000000,"addon_base_url":"http://192.168.1.5:7000/k",""" +
            """"logo":"http://192.168.1.5:8096/Items/9/Images/Logo?api_key=abc","trackingSourceUrl":"https://trakt.tv/shows/two"}}"""
        val scrubbed = BackupHistory.scrub(mapOf("watch_progress_map" to BackupValue.Text(map)))
        val text = (scrubbed.getValue("watch_progress_map") as BackupValue.Text).value
        val progress = Json.parseToJsonElement(text).jsonObject.getValue("tt2:1:3").jsonObject

        assertFalse("192.168.1.5" in text)
        assertEquals("61000", progress.getValue("position").jsonPrimitive.content)
        assertEquals("https://trakt.tv/shows/two", progress.getValue("trackingSourceUrl").jsonPrimitive.content)
    }

    @Test
    fun `an item kept as text inside the map is cleaned too`() {
        val inner = """{"contentId":"tt3","addonBaseUrl":"https://addon.example/key"}"""
        val map = JsonObject(mapOf("tt3" to JsonPrimitive(inner))).toString()
        val text = (BackupHistory.scrub(mapOf("watch_progress_map" to BackupValue.Text(map)))
            .getValue("watch_progress_map") as BackupValue.Text).value

        assertFalse("addon.example" in text)
        assertTrue("tt3" in text)
    }

    @Test
    fun `what waits for the next sign-in is cleaned the same way`() {
        val waiting = """{"key":"tt4","progress":{"contentId":"tt4","position":9,"addonBaseUrl":"https://addon.example/key"}}"""
        val text = (BackupHistory.scrub(mapOf("progress_upserts" to BackupValue.Texts(setOf(waiting))))
            .getValue("progress_upserts") as BackupValue.Texts).value.single()

        assertFalse("addon.example" in text)
        assertTrue("\"position\":9" in text)
    }

    @Test
    fun `plain settings pass through unchanged`() {
        val values = mapOf(
            "library_sort_option" to BackupValue.Text("TITLE_ASC"),
            "watch_progress_storage_version" to BackupValue.Whole(1)
        )
        assertEquals(values, BackupHistory.scrub(values))
    }

    @Test
    fun `counts add up over profiles and both progress stores`() {
        val entries = listOf(
            entry(BackupHistory.LIBRARY, 1, mapOf("library_items" to BackupValue.Texts(setOf("a", "b")))),
            entry(BackupHistory.LIBRARY, 2, mapOf("library_items" to BackupValue.Texts(setOf("c")))),
            entry(BackupHistory.WATCHED, 1, mapOf("watched_items" to BackupValue.Texts(setOf("w1", "w2", "w3", "w4")))),
            entry(BackupHistory.PROGRESS_RECENT, 1, mapOf("watch_progress_map" to BackupValue.Text("""{"a":{},"b":{}}"""))),
            entry(BackupHistory.PROGRESS_ARCHIVE, 1, mapOf("watch_progress_map" to BackupValue.Text("""{"c":{}}""")))
        )
        assertEquals(BackupHistoryCounts(saved = 3, watched = 4, inProgress = 3), BackupHistory.counts(entries))
    }

    @Test
    fun `stores the backup has nothing for are emptied for restored profiles only`() {
        val entries = listOf(
            entry(BackupHistory.LIBRARY, 1, emptyMap()),
            entry(BackupHistory.PROGRESS_STATE, 1, emptyMap()),
            entry(BackupHistory.PROGRESS_RECENT, 1, emptyMap()),
            entry(BackupHistory.WATCHED, 1, emptyMap())
        )
        assertEquals(
            listOf(BackupHistory.PROGRESS_ARCHIVE to 1, BackupHistory.PENDING to 1),
            BackupHistory.storesToEmpty(entries)
        )
        assertTrue(BackupHistory.storesToEmpty(emptyList()).isEmpty())
    }

    @Test
    fun `the history part survives the file format`() {
        val document = BackupDocument(
            BackupHeader(1L, "1.1.0", BackupDevice("m", "box", 34), listOf("A"), setOf(BackupPart.HISTORY)),
            mapOf(BackupPart.HISTORY to listOf(entry(BackupHistory.LIBRARY, 1, mapOf("library_items" to BackupValue.Texts(setOf("x")))))),
            null
        )
        val decoded = BackupFormat.decode(BackupFormat.encode(document))
        assertEquals(document.entries, decoded.entries)
        assertEquals(setOf(BackupPart.HISTORY), decoded.header.parts)
    }
}
