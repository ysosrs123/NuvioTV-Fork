package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.google.gson.Gson
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class WatchProgressOwnershipTest {
    private fun progress(id: String) = WatchProgress(contentId=id, contentType="movie", name="fixture", poster=null, backdrop=null, logo=null, videoId=id, season=null, episode=null, episodeTitle=null, position=1000, duration=10000, lastWatched=1)
    private fun actual(block: suspend (ProfileDataStoreFactory, WatchProgressPreferences) -> Unit) = runBlocking {
        withTimeout(30_000) {
            val dir=Files.createTempDirectory("progress-ownership-").toFile()
            val context=mockk<Context>()
            every { context.filesDir } returns dir
            every { context.applicationContext } returns context
            val factory=ProfileDataStoreFactory(context)
            val manager=mockk<ProfileManager>()
            every { manager.activeProfileId } returns MutableStateFlow(1)
            val prefs=WatchProgressPreferences(factory,manager)
            try { block(factory,prefs) }
            finally { factory.clearProfileScopedData();dir.deleteRecursively() }
        }
    }

    @Test fun `held progress flow retires and reused profile migrates its own legacy generation`() = actual { factory,prefs ->
        prefs.saveProgress(progress("old"),2)
        val old=prefs.observeAllProgress(2)
        assertEquals(listOf(progress("old")),old.first())
        factory.clearProfile(2)
        assertEquals(emptyList<WatchProgress>(),old.first())
        factory.markProfileCreated(2)
        val legacy=progress("new")
        factory.get(2,WATCH_PROGRESS_METADATA_FEATURE).edit { it[watchProgressEntriesKey]=Gson().toJson(mapOf("new" to legacy)) }
        assertEquals(mapOf("new" to legacy),prefs.getAllRawEntries(2))
        assertEquals(emptyList<WatchProgress>(),old.first())
        assertEquals(listOf(legacy),prefs.observeAllProgress(2).first())
    }

    @Test fun `global reset retires old progress snapshots and preserves unrelated store handles`() = actual { factory,prefs ->
        val unrelated=factory.get(1,"theme_preferences")
        prefs.saveProgress(progress("old"),1)
        val old=prefs.observeAllRawProgress(1)
        assertEquals(listOf(progress("old")),old.first())
        factory.clearProfileScopedData()
        assertSame(unrelated,factory.get(1,"theme_preferences"))
        assertEquals(emptyList<WatchProgress>(),old.first())
        prefs.saveProgress(progress("new"),1)
        assertEquals(emptyList<WatchProgress>(),old.first())
        assertEquals(listOf(progress("new")),prefs.observeAllRawProgress(1).first())
    }

    @Test fun `concurrent profile aggregate and raw cache reads preserve profile identity`() = actual { _,prefs ->
        for(id in 1..6)prefs.saveProgress(progress("p$id"),id)
        coroutineScope {
            (1..6).map { id -> async(Dispatchers.Default) {
                repeat(100) {
                    assertEquals(listOf(progress("p$id")),prefs.observeAllProgress(id).first())
                    assertEquals(listOf(progress("p$id")),prefs.observeAllRawProgress(id).first())
                }
            } }.awaitAll()
        }
    }

    @Test fun `active progress collector rebinds after global reset with unchanged profile number`() = actual { factory,prefs ->
        prefs.saveProgress(progress("old"),1)
        val output=Channel<List<WatchProgress>>(Channel.UNLIMITED)
        val collector=CoroutineScope(kotlin.coroutines.coroutineContext).launch { prefs.allProgress.collect { output.send(it) } }
        try {
            assertEquals(listOf(progress("old")),output.receive())
            factory.clearProfileScopedData()
            assertEquals(emptyList<WatchProgress>(),output.receive())
            prefs.saveProgress(progress("new"),1)
            var latest=output.receive()
            while(latest.isEmpty()) latest=output.receive()
            assertEquals(listOf(progress("new")),latest)
        } finally { collector.cancelAndJoin() }
    }
}
