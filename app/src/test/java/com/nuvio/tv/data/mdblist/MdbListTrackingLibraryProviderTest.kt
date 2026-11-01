package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.defaultToggleAdds
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MdbListTrackingLibraryProviderTest {
    private val provider = MdbListTrackingLibraryProvider(
        service = mockk(relaxed = true),
        tracking = mockk(relaxed = true)
    )

    @Test
    fun `only the watchlist and own lists count as in the library`() {
        assertTrue(provider.countsAsInLibrary(MDBLIST_WATCHLIST_KEY))
        assertTrue(provider.countsAsInLibrary("${MDBLIST_LIST_KEY_PREFIX}7"))
        assertFalse(provider.countsAsInLibrary("${MDBLIST_DYNAMIC_LIST_KEY_PREFIX}7"))
        assertFalse(provider.countsAsInLibrary("${MDBLIST_LIKED_LIST_KEY_PREFIX}7"))
        assertFalse(provider.countsAsInLibrary("${MDBLIST_EXTERNAL_LIST_KEY_PREFIX}7"))
    }

    @Test
    fun `the button adds to the watchlist when the title is only in a read-only list`() {
        val current = mapOf(MDBLIST_WATCHLIST_KEY to false, "${MDBLIST_DYNAMIC_LIST_KEY_PREFIX}7" to true)
        val desired = provider.toggledDefaultMembership(current)

        assertTrue(desired.getValue(MDBLIST_WATCHLIST_KEY))
        assertTrue(defaultToggleAdds(current, desired))
    }

    @Test
    fun `the button removes from the watchlist when the title is there`() {
        val current = mapOf(MDBLIST_WATCHLIST_KEY to true, "${MDBLIST_LIST_KEY_PREFIX}7" to true)
        val desired = provider.toggledDefaultMembership(current)

        assertFalse(desired.getValue(MDBLIST_WATCHLIST_KEY))
        assertFalse(defaultToggleAdds(current, desired))
    }
}
