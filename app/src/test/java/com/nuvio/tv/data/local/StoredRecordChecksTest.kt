package com.nuvio.tv.data.local

import com.google.gson.Gson
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoredRecordChecksTest {
    private val gson = Gson()

    @Test
    fun `watched entries without an id are dropped and a missing title becomes empty`() {
        assertNull(parseWatchedItem(gson, """{"contentType":"movie","title":"X","watchedAt":1}"""))
        assertNull(parseWatchedItem(gson, """{"contentId":" ","contentType":"movie","title":"X","watchedAt":1}"""))
        val noTitle = parseWatchedItem(gson, """{"contentId":"tt1","contentType":"movie","watchedAt":1}""")
        assertEquals("", noTitle?.title)
        assertEquals("tt1", parseWatchedItem(gson, """{"contentId":"tt1","contentType":"movie","title":"X","watchedAt":1}""")?.contentId)
    }

    @Test
    fun `library entries get safe values for missing fields`() {
        val parsed = gson.fromJson("""{"id":"tt1","type":"movie","posterShape":"UNKNOWN"}""", SavedLibraryItem::class.java)
        val fixed = parsed.completeOrNull()!!
        assertEquals("", fixed.name)
        assertEquals(PosterShape.POSTER, fixed.posterShape)
        assertEquals(emptyList<String>(), fixed.genres)
        assertNull(gson.fromJson("""{"type":"movie","name":"X"}""", SavedLibraryItem::class.java).completeOrNull())
    }

    @Test
    fun `complete library entries are kept as they are`() {
        val item = SavedLibraryItem("tt1", "movie", "X", null, PosterShape.LANDSCAPE, null, null, null, null, listOf("Drama"), null)
        assertEquals(item, gson.fromJson(gson.toJson(item), SavedLibraryItem::class.java).completeOrNull())
    }
}
