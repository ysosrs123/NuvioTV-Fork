package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.domain.model.LibraryEntry
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryGenreFilterTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private fun LibraryViewModelTestFixture.entry(name: String, listedAt: Long, vararg genres: String) = LibraryEntry(
        id = "tmdb:$name", type = "movie", name = name, poster = null, background = null, logo = null,
        description = null, releaseInfo = null, imdbRating = null, genres = genres.toList(), addonBaseUrl = null,
        listKeys = setOf(tab.key), listedAt = listedAt, trackingProviderId = "mdblist"
    )

    @Test
    fun `a selected genre that disappears shows the whole list instead of an empty grid`() = runTest {
        val f = LibraryViewModelTestFixture()
        f.items.value = listOf(f.entry("Alpha", 3, "Drama"), f.entry("Bravo", 2, "Comedy"))
        runCurrent()
        f.viewModel.onSelectGenre("Drama")
        runCurrent()
        assertEquals(listOf("Alpha"), f.viewModel.uiState.value.visibleItems.map { it.name })

        f.items.value = listOf(f.entry("Alpha", 3, "Thriller"), f.entry("Bravo", 2, "Comedy"))
        runCurrent()
        assertNull(f.viewModel.uiState.value.selectedGenre)
        assertEquals(listOf("Alpha", "Bravo"), f.viewModel.uiState.value.visibleItems.map { it.name })
    }

    @Test
    fun `genres arriving later do not reorder the list`() = runTest {
        val f = LibraryViewModelTestFixture()
        val plain = listOf(f.entry("Zulu", 5), f.entry("Alpha", 5), f.entry("Mike", 9), f.entry("Bravo", 1))
        f.items.value = plain
        runCurrent()
        val before = f.viewModel.uiState.value.visibleItems.map { it.name }
        for (option in LibrarySortOption.TrackingOptions) {
            f.viewModel.onSelectSortOption(option)
            runCurrent()
            val order = f.viewModel.uiState.value.visibleItems.map { it.name }
            f.items.value = plain.mapIndexed { index, entry -> entry.copy(genres = listOf(if (index % 2 == 0) "Drama" else "Action")) }
            runCurrent()
            assertEquals(order, f.viewModel.uiState.value.visibleItems.map { it.name })
            f.items.value = plain
            runCurrent()
        }
        f.viewModel.onSelectSortOption(LibrarySortOption.DEFAULT)
        runCurrent()
        assertEquals(before, f.viewModel.uiState.value.visibleItems.map { it.name })
    }
}
