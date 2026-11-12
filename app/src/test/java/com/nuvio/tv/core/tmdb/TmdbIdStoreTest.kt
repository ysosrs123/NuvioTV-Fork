package com.nuvio.tv.core.tmdb

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
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
            flush()
        }

        val reopened = store(file).load()

        assertEquals("tt0133093", reopened["603:movie"])
        assertEquals("tt0944947", reopened["1399:tv"])
    }

    @Test
    fun `a title with no IMDb id is remembered for a week`() {
        val file = File(folder.root, "ids.tsv")
        store(file).apply { putNoImdbId("42:movie"); flush() }

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
            flush()
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
            flush()
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

    @Test
    fun `new ids are seen at once and written together after a short wait`() {
        val file = File(folder.root, "ids.tsv")
        val scheduler = TestCoroutineScheduler()
        val store = TmdbIdStore(file, StandardTestDispatcher(scheduler), 2_000L) { nowMs }
        store.put("603:movie", "tt0133093")
        store.putNoImdbId("42:movie")

        assertEquals("tt0133093", store.load()["603:movie"])
        assertTrue(store.hasNoImdbId("42:movie"))
        assertFalse(file.exists())

        scheduler.advanceTimeBy(1_999L)
        scheduler.runCurrent()
        assertFalse(file.exists())

        scheduler.advanceTimeBy(2L)
        scheduler.runCurrent()
        assertEquals(listOf("603:movie\ttt0133093", "42:movie\t-\t$nowMs"), file.readLines())
    }

    @Test
    fun `a large batch is written without waiting`() {
        val file = File(folder.root, "ids.tsv")
        val scheduler = TestCoroutineScheduler()
        val store = TmdbIdStore(file, StandardTestDispatcher(scheduler), 60_000L) { nowMs }
        repeat(FLUSH_AT_LINES) { store.put("$it:movie", "tt$it") }

        scheduler.runCurrent()

        assertEquals(FLUSH_AT_LINES, file.readLines().size)
        assertEquals("tt7", store(file).load()["7:movie"])
    }

    @Test
    fun `batches append to a file written the old way`() {
        val file = File(folder.root, "ids.tsv")
        file.writeText("603:movie\ttt0133093\n")
        store(file).apply {
            put("1399:tv", "tt0944947")
            flush()
            flush()
        }

        assertEquals(listOf("603:movie\ttt0133093", "1399:tv\ttt0944947"), file.readLines())
        assertEquals(2, store(file).load().size)
    }
}
