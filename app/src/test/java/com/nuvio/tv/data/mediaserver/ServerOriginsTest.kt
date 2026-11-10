package com.nuvio.tv.data.mediaserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerOriginsTest {
    @Test
    fun anItemIsFoundByAnyOfItsIdsForThatServerAndType() {
        val origins = ServerOrigins.inMemory()
        origins.remember("c1", "movie", listOf("m1" to listOf("tt1", "tmdb:2")))
        assertEquals("m1", origins.itemId("c1", "movie", listOf("tmdb:2")))
        assertNull(origins.itemId("c2", "movie", listOf("tt1")))
        assertNull(origins.itemId("c1", "series", listOf("tt1")))
    }

    @Test
    fun itOutlivesTheAppRun() {
        var stored: String? = null
        ServerOrigins({ stored }, { stored = it }).remember("c1", "movie", listOf("m1" to listOf("tt1")))
        assertEquals("m1", ServerOrigins({ stored }, { stored = it }).itemId("c1", "movie", listOf("tt1")))
    }

    @Test
    fun forgettingClearsEveryIdOfThatItem() {
        val origins = ServerOrigins.inMemory()
        origins.remember("c1", "movie", listOf("m1" to listOf("tt1", "tmdb:2")))
        assertTrue(origins.forget("c1", "movie", listOf("tt1")))
        assertNull(origins.itemId("c1", "movie", listOf("tmdb:2")))
        assertFalse(origins.forget("c1", "movie", listOf("tt1")))
    }

    @Test
    fun aBrokenSavedFileCountsAsEmpty() {
        assertNull(ServerOrigins({ "not json" }, { }).itemId("c1", "movie", listOf("tt1")))
    }
}
