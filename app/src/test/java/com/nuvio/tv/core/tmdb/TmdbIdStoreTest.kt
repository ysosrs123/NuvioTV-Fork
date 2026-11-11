package com.nuvio.tv.core.tmdb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TmdbIdStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var nowMs = 1_000_000L
    private fun store(file: File) = TmdbIdStore(file) { nowMs }

    @Test
    fun `ids come back after a restart`() {
        val file = File(folder.root, "ids.tsv")
        store(file).apply {
            put("603:movie", "tt0133093")
            put("1399:tv", "tt0944947")
        }

        val reopened = store(file).load()

        assertEquals("tt0133093", reopened["603:movie"])
        assertEquals("tt0944947", reopened["1399:tv"])
    }

    @Test
    fun `a title with no IMDb id is remembered for a week`() {
        val file = File(folder.root, "ids.tsv")
        store(file).putNoImdbId("42:movie")

        assertTrue(store(file).hasNoImdbId("42:movie"))
        assertNull(store(file).load()["42:movie"])

        nowMs += 7L * 24 * 60 * 60 * 1000
        assertFalse(store(file).hasNoImdbId("42:movie"))
    }

    @Test
    fun `an id learned later replaces the empty answer`() {
        val file = File(folder.root, "ids.tsv")
        store(file).apply {
            putNoImdbId("42:movie")
            put("42:movie", "tt0000042")
        }

        val reopened = store(file)

        assertFalse(reopened.hasNoImdbId("42:movie"))
        assertEquals("tt0000042", reopened.load()["42:movie"])
    }

    @Test
    fun `the same id is written once`() {
        val file = File(folder.root, "ids.tsv")
        store(file).apply {
            put("603:movie", "tt0133093")
            put("603:movie", "tt0133093")
        }

        assertEquals(1, file.readLines().size)
    }

    @Test
    fun `a damaged or missing file is an empty store`() {
        val file = File(folder.root, "ids.tsv")
        assertTrue(store(file).load().isEmpty())

        file.writeText("garbage" + System.lineSeparator() + "603:movie" + System.lineSeparator())
        assertTrue(store(file).load().isEmpty())
        assertTrue(TmdbIdStore(null) { nowMs }.load().isEmpty())
    }
}
