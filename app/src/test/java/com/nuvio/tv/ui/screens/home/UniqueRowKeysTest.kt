package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.ui.util.asStable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UniqueRowKeysTest {
    private fun row(key: String, index: Int) =
        HeroCarouselRow(key = key, title = key, globalRowIndex = index, items = emptyList<ModernCarouselItem>().asStable())

    @Test
    fun `rows that share a key are told apart, the first keeps its key`() {
        val rows = listOf(row("a", 0), row("b", 1), row("a", 2), row("a", 3))
        assertEquals(listOf("a", "b", "a#1", "a#2"), withUniqueRowKeys(rows).map { it.key })
    }

    @Test
    fun `a list without repeats comes back untouched`() {
        val rows = listOf(row("a", 0), row("b", 1))
        assertSame(rows, withUniqueRowKeys(rows))
    }
}
