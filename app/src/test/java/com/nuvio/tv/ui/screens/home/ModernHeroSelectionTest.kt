package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.ui.util.asStable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModernHeroSelectionTest {
    private fun candidate(key: String?, row: String = "catalogue") =
        ModernHeroFocus(key?.let { ModernHeroSelection(row, it) }, 450)

    @Test fun `rapid focus changes publish only the destination hero and enrichment selection`() = runTest {
        val focus = MutableStateFlow(candidate("a"))
        val selected = mutableListOf<ModernHeroSelection>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            focus.settledModernHeroSelections(MutableStateFlow(false)).collect { selected += it }
        }
        for (key in listOf("b", "c", "d")) {
            advanceTimeBy(100)
            focus.value = candidate(key)
            runCurrent()
        }
        advanceTimeBy(449)
        runCurrent()
        assertTrue(selected.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(ModernHeroSelection("catalogue", "d")), selected)
    }

    @Test fun `vertical scrolling delays publication and newer row focus cancels the old wait`() = runTest {
        val focus = MutableStateFlow(candidate("same", "old-row"))
        val scrolling = MutableStateFlow(true)
        val selected = mutableListOf<ModernHeroSelection>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            focus.settledModernHeroSelections(scrolling).collect { selected += it }
        }
        advanceTimeBy(600)
        runCurrent()
        assertTrue(selected.isEmpty())
        focus.value = candidate("same", "new-row")
        runCurrent()
        scrolling.value = false
        advanceTimeBy(449)
        runCurrent()
        assertTrue(selected.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(ModernHeroSelection("new-row", "same")), selected)
    }

    @Test fun `completed dwell publishes when vertical scrolling finishes without another focus event`() = runTest {
        val scrolling = MutableStateFlow(true)
        val selected = mutableListOf<ModernHeroSelection>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            MutableStateFlow(candidate("a")).settledModernHeroSelections(scrolling).collect { selected += it }
        }
        advanceTimeBy(500)
        runCurrent()
        assertTrue(selected.isEmpty())
        scrolling.value = false
        runCurrent()
        assertEquals(listOf(ModernHeroSelection("catalogue", "a")), selected)
    }

    @Test fun `losing the focused item cancels pending hero work`() = runTest {
        val focus = MutableStateFlow(candidate("a"))
        val selected = mutableListOf<ModernHeroSelection>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            focus.settledModernHeroSelections(MutableStateFlow(false)).collect { selected += it }
        }
        advanceTimeBy(100)
        focus.value = candidate(null)
        advanceTimeBy(1000)
        runCurrent()
        assertTrue(selected.isEmpty())
    }

    @Test fun `hero identity survives reordering and updates only its own metadata with no other-title fallback`() {
        fun item(key: String, title: String = key) = ModernCarouselItem(
            key, title, null, null,
            HeroPreview(title, "$key-logo", null, null, yearText = null, imdbText = null,
                genres = emptyList<String>().asStable(), poster = null, backdrop = "$key-backdrop", imageUrl = null),
            ModernPayload.Catalog(key, key, "movie", "https://example.test", key, null, "movie")
        )
        val row = HeroCarouselRow("row", "row", 0, listOf(item("a"), item("b")).asStable())
        val selection = row.heroSelectionAt(1)
        assertEquals("b", resolveModernHeroItem(mapOf("row" to row), selection)?.title)
        val refreshed = row.copy(items = listOf(item("b", "Enriched B"), item("a")).asStable())
        assertEquals("Enriched B", resolveModernHeroItem(mapOf("row" to refreshed), selection)?.title)
        assertNull(resolveModernHeroItem(mapOf("row" to row.copy(items = listOf(item("a")).asStable())), selection))
        assertNull(resolveModernHeroItem(mapOf("other-row" to row), selection))
        assertNull(resolveModernHeroItem(mapOf("row" to row), null))
    }
}
