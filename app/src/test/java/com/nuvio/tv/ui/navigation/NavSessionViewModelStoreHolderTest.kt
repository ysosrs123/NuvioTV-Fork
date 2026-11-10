package com.nuvio.tv.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NavSessionViewModelStoreHolderTest {
    class Probe : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }

    private fun probeIn(holder: NavSessionViewModelStoreHolder, session: String): Probe =
        ViewModelProvider.create(holder.storeFor(session))[Probe::class]

    @Test
    fun `an old session ending late leaves the new session's store alone`() {
        val holder = NavSessionViewModelStoreHolder()
        val old = probeIn(holder, "old")
        val next = probeIn(holder, "next")

        holder.endSession("old")

        assertTrue(old.cleared)
        assertFalse(next.cleared)
        assertSame(next, probeIn(holder, "next"))
    }

    @Test
    fun `the same session keeps its store until it ends`() {
        val holder = NavSessionViewModelStoreHolder()
        val first = probeIn(holder, "a")
        assertSame(first, probeIn(holder, "a"))

        holder.endSession("a")

        assertTrue(first.cleared)
        assertNotSame(first, probeIn(holder, "a"))
    }
}
