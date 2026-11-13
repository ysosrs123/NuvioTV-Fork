package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.scout.sync.ImportChange
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.domain.model.ScoutRatingEntry
import com.nuvio.tv.domain.model.ScoutTitle
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutStoreSyncTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    private val memory = MemoryStore()
    private val store = ScoutStore(
        mockk<ProfileDataStoreFactory>().also { every { it.get(1, "scout") } returns memory },
        mockk<ProfileManager>().also { every { it.activeProfileId } returns MutableStateFlow(1) }
    )
    private val arrival = ScoutTitle("tt1", "movie", "Arrival")

    @Test
    fun `presses are only queued with sending on`() = runBlocking {
        store.setRating(arrival, ScoutRating.LOVE, now = 1L)
        assertTrue(store.outbox(1).isEmpty())
        store.update { it.copy(sendToTrackers = true) }
        store.setRating(arrival, ScoutRating.LIKE, now = 2L)
        val item = store.outbox(1).getValue("tt1")
        assertEquals(ScoutRating.LIKE, item.action.rating)
        assertEquals(2L, item.action.stamp)
    }

    @Test
    fun `only the latest press per title is kept`() = runBlocking {
        store.update { it.copy(sendToTrackers = true) }
        store.setRating(arrival, ScoutRating.LOVE, now = 1L)
        store.setRating(arrival, null, now = 2L)
        val queued = store.outbox(1)
        assertEquals(1, queued.size)
        assertNull(queued.getValue("tt1").action.rating)
    }

    @Test
    fun `an item leaves the queue when every tracker is done, not before`() = runBlocking {
        store.update { it.copy(sendToTrackers = true) }
        store.setRating(arrival, ScoutRating.LOVE, now = 3L)
        store.finishOut(1, "tt1", 3L, "trakt", 10.0, setOf("trakt", "simkl"))
        assertEquals(setOf("trakt"), store.outbox(1).getValue("tt1").done)
        assertEquals(mapOf("tt1" to 10.0), store.sent(1, "trakt"))
        store.finishOut(1, "tt1", 3L, "simkl", null, setOf("trakt", "simkl"))
        assertTrue(store.outbox(1).isEmpty())
    }

    @Test
    fun `a newer press is not dropped by an older send finishing`() = runBlocking {
        store.update { it.copy(sendToTrackers = true) }
        store.setRating(arrival, ScoutRating.LOVE, now = 3L)
        store.setRating(arrival, ScoutRating.LIKE, now = 4L)
        store.finishOut(1, "tt1", 3L, "trakt", 10.0, setOf("trakt"))
        assertEquals(4L, store.outbox(1).getValue("tt1").action.stamp)
        assertTrue(store.sent(1, "trakt").isEmpty())
    }

    @Test
    fun `clearing a thumb keeps it from being imported again`() = runBlocking {
        store.setRating(arrival, ScoutRating.LOVE, now = 1L)
        store.setRating(arrival, null, now = 2L)
        assertEquals(setOf("tt1"), store.current().dismissed)
    }

    @Test
    fun `an import is one write and keeps its source`() = runBlocking {
        val writes = mutableListOf<Int>()
        val changed = store.applyImport(1) { profile, _, _ ->
            writes += profile.ratings.size
            listOf(
                ImportChange("tt1", ScoutRatingEntry(arrival, ScoutRating.LOVE, 9L, "simkl")),
                ImportChange("tt2", ScoutRatingEntry(ScoutTitle("tt2", "movie", "Heat"), ScoutRating.LIKE, 9L, "simkl"))
            )
        }
        assertEquals(2, changed)
        assertEquals(listOf(0), writes)
        val ratings = store.profile.first().ratings
        assertEquals("simkl", ratings.getValue("tt1").source)
        assertEquals(2, ratings.size)
    }

    @Test
    fun `switches default to send off and use on`() = runBlocking {
        val settings = store.current().settings
        assertEquals(false, settings.sendToTrackers)
        assertEquals(true, settings.useTrackerRatings)
    }
}
