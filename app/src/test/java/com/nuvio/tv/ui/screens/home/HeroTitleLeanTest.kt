package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.structuralEqualityPolicy
import com.nuvio.tv.R
import com.nuvio.tv.ui.util.asStable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HeroTitleLeanTest {
    private val words = mapOf(
        R.string.series_status_ended to "Ended",
        R.string.movie_status_released to "Released",
        R.string.series_status_continuing to "Returning",
        R.string.movie_status_post_production to "Post production"
    )
    private fun preview(title: String) =
        HeroPreview(title, logo = null, description = null, contentTypeText = null, yearText = null, imdbText = null,
            genres = emptyList<String>().asStable(), poster = null, backdrop = null, imageUrl = null)
    private fun label(status: String?, series: Boolean) = heroStatusLabel(status, series) { words.getValue(it) }

    @Test fun `known statuses use the app's words for series and films`() {
        assertEquals("ENDED", label("Ended", series = true))
        assertEquals("RELEASED", label(" released ", series = false))
        assertEquals("RETURNING", label("Returning Series", series = true))
        assertEquals("POST PRODUCTION", label("post production", series = false))
    }

    @Test fun `other statuses show as given and blank shows nothing`() {
        assertEquals("PILOT", label(" Pilot ", series = true))
        assertNull(label("  ", series = false))
        assertNull(label(null, series = true))
    }

    @Test fun `an equal hero preview keeps the instance the title already shows`() {
        val source = mutableStateOf(preview("Dune"))
        val shown = derivedStateOf(structuralEqualityPolicy()) { source.value to false }
        val first = Snapshot.observe(readObserver = {}) { shown.value }
        Snapshot.withMutableSnapshot { source.value = preview("Dune") }
        assertSame(first, shown.value)
        Snapshot.withMutableSnapshot { source.value = preview("Arrival") }
        assertEquals("Arrival", shown.value.first.title)
    }
}
