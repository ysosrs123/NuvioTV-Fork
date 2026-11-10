package com.nuvio.tv.core.scout

import com.nuvio.tv.data.local.ScoutStore
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.ScoutProfile
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.domain.model.ScoutRatingEntry
import com.nuvio.tv.domain.model.ScoutTitle
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoutFolderSupportTest {
    private fun item(id: String, name: String, year: String? = "2020") = MetaPreview(
        id = id, type = ContentType.MOVIE, name = name, poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = year, imdbRating = null, genres = emptyList()
    )

    private fun support(
        watched: List<WatchedItem> = emptyList(),
        profile: ScoutProfile = ScoutProfile(),
        tabs: List<LibraryListTab> = emptyList(),
        entries: List<LibraryEntry> = emptyList(),
        active: MutableStateFlow<Int> = MutableStateFlow(1),
        onRead: () -> Unit = {},
        onHistory: () -> Unit = {}
    ): ScoutFolderSupport {
        val store = mockk<ScoutStore> { coEvery { current() } returns profile }
        val progress = mockk<WatchProgressRepository> {
            every { tasteHistory } answers {
                onHistory()
                flowOf(watched)
            }
            every { allProgress } returns flowOf(emptyList())
        }
        val library = mockk<LibraryRepository> {
            every { listTabs } answers {
                onRead()
                flowOf(tabs)
            }
            every { libraryItems } returns flowOf(entries)
        }
        val profiles = mockk<com.nuvio.tv.core.profile.ProfileManager> { every { activeProfileId } returns active }
        return ScoutFolderSupport(store, profiles, dagger.Lazy { progress }, dagger.Lazy { library })
    }

    private fun visible(s: ScoutFolderSupport, vararg items: MetaPreview) =
        runBlocking { s.withoutSeen(items.toList()) }.map { it.id }

    @Test
    fun `watched title leaves but a remake of the name stays`() {
        val s = support(watched = listOf(WatchedItem("tt1", "movie", "Dune", watchedAt = 1, releaseInfo = "1984")))
        assertEquals(listOf("tmdb:2"), visible(s, item("tmdb:1", "Dune", "1984"), item("tmdb:2", "Dune", "2021")))
    }

    @Test
    fun `liked and loved titles stay until watched`() {
        listOf(ScoutRating.LIKE, ScoutRating.LOVE).forEach { rating ->
            val rated = ScoutRatingEntry(ScoutTitle("tt1", "movie", "Moon"), rating, 1)
            val s = support(profile = ScoutProfile(ratings = mapOf("tt1" to rated)))
            assertEquals(listOf("tmdb:1"), visible(s, item("tmdb:1", "Moon")))
        }
    }

    @Test
    fun `the remembered lists belong to one profile`() {
        var reads = 0
        val active = MutableStateFlow(1)
        val s = support(active = active, onRead = { reads++ })
        runBlocking { s.listed() }
        runBlocking { s.listed() }
        assertEquals(1, reads)
        active.value = 2
        runBlocking { s.listed() }
        assertEquals(2, reads)
    }

    @Test
    fun `callers at the same moment share one lookup`() {
        val lists = java.util.concurrent.atomic.AtomicInteger()
        val history = java.util.concurrent.atomic.AtomicInteger()
        val s = support(onRead = { lists.incrementAndGet() }, onHistory = { history.incrementAndGet() })
        runBlocking {
            (1..8).map { async(kotlinx.coroutines.Dispatchers.Default) { s.withoutSeen(listOf(item("tmdb:1", "Moon"))) } }.awaitAll()
        }
        assertEquals(1, lists.get())
        assertEquals(1, history.get())
    }

    @Test
    fun `rated title leaves`() {
        val rated = ScoutRatingEntry(ScoutTitle("tt1", "movie", "Moon"), ScoutRating.NOT_FOR_ME, 1)
        val s = support(profile = ScoutProfile(ratings = mapOf("tt1" to rated)))
        assertEquals(emptyList<String>(), visible(s, item("tmdb:1", "Moon")))
    }

    @Test
    fun `slice id only for scout folders that exist`() {
        val s = support()
        assertEquals("mood_laugh", s.sliceId(ScoutCatalogProvider.TILES_COLLECTION_ID, "mood_laugh"))
        assertNull(s.sliceId("other", "mood_laugh"))
        assertNull(s.sliceId(ScoutCatalogProvider.TILES_COLLECTION_ID, ScoutCatalogProvider.FOLDER_MORE))
    }
}
