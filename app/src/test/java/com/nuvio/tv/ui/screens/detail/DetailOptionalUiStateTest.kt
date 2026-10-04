package com.nuvio.tv.ui.screens.detail

import org.junit.Assert.*
import org.junit.Test

class DetailOptionalUiStateTest {
    @Test fun `comment completion does not invalidate entry or rating state`() {
        val initial = MetaDetailsUiState()
        val loading = initial.copy(isCommentsLoading = true, commentsCurrentPage = 1, commentsPageCount = 4)
        assertEquals(initial.entryState(), loading.entryState())
        assertEquals(initial.ratingState(), loading.ratingState())
        assertNotEquals(initial.commentState(), loading.commentState())
        assertTrue(loading.commentState().canLoadMore)
    }
    @Test fun `rating arrival updates only the rating projection`() {
        val initial = MetaDetailsUiState()
        val enriched = initial.copy(tmdbRating = 8.5f, isMdbListRatingsActive = true)
        assertEquals(initial.entryState(), enriched.entryState())
        assertEquals(initial.commentState(), enriched.commentState())
        assertNotEquals(initial.ratingState(), enriched.ratingState())
    }
    @Test fun `selection and presentation changes still reach the entry`() {
        val initial = MetaDetailsUiState()
        val selected = initial.copy(selectedSeason = 2, heroPresentationToken = 3)
        assertNotEquals(initial.entryState(), selected.entryState())
        assertEquals(2, selected.entryState().selectedSeason)
        assertEquals(3, selected.entryState().heroPresentationToken)
    }
}
