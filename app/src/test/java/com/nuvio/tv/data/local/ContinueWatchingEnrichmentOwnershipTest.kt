package com.nuvio.tv.data.local

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ContinueWatchingEnrichmentOwnershipTest {
    private fun ip(id: String) = CachedInProgressItem(id,"movie",id,null,null,null,id,null,null,null,10,100,1,10f)
    private fun nu(id: String) = CachedNextUpItem(id,"series",id,null,null,null,"$id:1:2",1,2,null,thumbnail=null,lastWatched=1,sortTimestamp=1)
    private fun actual(block: suspend (ContinueWatchingEnrichmentCache, MutableStateFlow<Int>, Mutex) -> Unit) = runBlocking {
        withTimeout(30_000) {
            val dir=Files.createTempDirectory("cw-cache-owner-").toFile()
            val context=mockk<Context>()
            every { context.filesDir } returns dir
            val active=MutableStateFlow(1)
            val manager=mockk<ProfileManager>()
            every { manager.activeProfileId } returns active
            val cache=ContinueWatchingEnrichmentCache(context,manager)
            val field=ContinueWatchingEnrichmentCache::class.java.getDeclaredField("mutex").apply { isAccessible=true }
            val mutex=field.get(cache) as Mutex
            try { block(cache,active,mutex) } finally { dir.deleteRecursively() }
        }
    }

    @Test fun `queued in-progress save stays with the profile that started it`() = actual { cache,active,mutex ->
        coroutineScope {
            mutex.lock()
            val queued=async(start=CoroutineStart.UNDISPATCHED) { cache.saveInProgressSnapshot(listOf(ip("one")),force=true) }
            active.value=2
            mutex.unlock()
            queued.await()
            assertTrue(cache.getInProgressSnapshot().isEmpty())
            active.value=1
            assertEquals(listOf(ip("one")),cache.getInProgressSnapshot())
        }
    }
    @Test fun `queued next-up save stays with the profile that started it`() = actual { cache,active,mutex ->
        coroutineScope {
            mutex.lock()
            val queued=async(start=CoroutineStart.UNDISPATCHED) { cache.saveNextUpSnapshot(listOf(nu("one")),force=true) }
            active.value=2
            mutex.unlock()
            queued.await()
            assertTrue(cache.getNextUpSnapshot().isEmpty())
            active.value=1
            assertEquals(listOf(nu("one")),cache.getNextUpSnapshot())
        }
    }
    @Test fun `queued reads return the originating profile snapshots`() = actual { cache,active,mutex ->
        cache.saveInProgressSnapshot(listOf(ip("one")),force=true)
        cache.saveNextUpSnapshot(listOf(nu("one")),force=true)
        active.value=2
        cache.saveInProgressSnapshot(listOf(ip("two")),force=true)
        cache.saveNextUpSnapshot(listOf(nu("two")),force=true)
        active.value=1
        coroutineScope {
            mutex.lock()
            val progress=async(start=CoroutineStart.UNDISPATCHED) { cache.getInProgressSnapshot() }
            val next=async(start=CoroutineStart.UNDISPATCHED) { cache.getNextUpSnapshot() }
            active.value=2
            mutex.unlock()
            assertEquals(listOf(ip("one")),progress.await())
            assertEquals(listOf(nu("one")),next.await())
        }
    }
    @Test fun `queued clear removes only the profile that requested it`() = actual { cache,active,mutex ->
        cache.saveInProgressSnapshot(listOf(ip("one")),force=true)
        cache.saveNextUpSnapshot(listOf(nu("one")),force=true)
        active.value=2
        cache.saveInProgressSnapshot(listOf(ip("two")),force=true)
        cache.saveNextUpSnapshot(listOf(nu("two")),force=true)
        active.value=1
        coroutineScope {
            mutex.lock()
            val clear=async(start=CoroutineStart.UNDISPATCHED) { cache.clearAll() }
            active.value=2
            mutex.unlock()
            clear.await()
        }
        assertEquals(listOf(ip("two")),cache.getInProgressSnapshot())
        assertEquals(listOf(nu("two")),cache.getNextUpSnapshot())
        active.value=1
        assertTrue(cache.getInProgressSnapshot().isEmpty())
        assertTrue(cache.getNextUpSnapshot().isEmpty())
        assertEquals(1,cache.cacheCleared.value)
    }
    @Test fun `identical content in a second profile still writes both snapshot kinds`() = actual { cache,active,_ ->
        val progress=listOf(ip("same"));val next=listOf(nu("same"))
        cache.saveInProgressSnapshot(progress)
        cache.saveNextUpSnapshot(next)
        active.value=2
        cache.saveInProgressSnapshot(progress)
        cache.saveNextUpSnapshot(next)
        assertEquals(progress,cache.getInProgressSnapshot())
        assertEquals(next,cache.getNextUpSnapshot())
        assertEquals(4,cache.snapshotVersion.value)
    }
    @Test fun `different content in a second profile has its own throttle`() = actual { cache,active,_ ->
        cache.saveInProgressSnapshot(listOf(ip("one")))
        cache.saveNextUpSnapshot(listOf(nu("one")))
        active.value=2
        cache.saveInProgressSnapshot(listOf(ip("two")))
        cache.saveNextUpSnapshot(listOf(nu("two")))
        assertEquals(listOf(ip("two")),cache.getInProgressSnapshot())
        assertEquals(listOf(nu("two")),cache.getNextUpSnapshot())
        assertEquals(4,cache.snapshotVersion.value)
    }

    @Test fun `force writes captured before clear cannot restore cleared snapshots`() = actual { cache,active,_ ->
        val version=cache.cacheCleared.value
        cache.saveInProgressSnapshot(listOf(ip("old")),force=true)
        cache.saveNextUpSnapshot(listOf(nu("old")),force=true)
        cache.clearAll()
        cache.saveInProgressSnapshot(listOf(ip("old")),force=true,profileId=1,expectedClearVersion=version)
        cache.saveNextUpSnapshot(listOf(nu("old")),force=true,profileId=1,expectedClearVersion=version)
        assertTrue(cache.getInProgressSnapshot().isEmpty())
        assertTrue(cache.getNextUpSnapshot().isEmpty())
        assertEquals(2,cache.snapshotVersion.value)
        // A fresh owner can still write, independently of whichever profile is currently active.
        active.value=2
        cache.saveInProgressSnapshot(listOf(ip("new")),force=true,profileId=1)
        assertTrue(cache.getInProgressSnapshot().isEmpty())
        assertEquals(listOf(ip("new")),cache.getInProgressSnapshot(1))
    }
    @Test fun `clearing resets only its profile write stamps and allows immediate fresh content`() = actual { cache,active,_ ->
        cache.saveInProgressSnapshot(listOf(ip("one")))
        cache.saveNextUpSnapshot(listOf(nu("one")))
        active.value=2
        cache.saveInProgressSnapshot(listOf(ip("two")))
        cache.saveNextUpSnapshot(listOf(nu("two")))
        cache.clearAll(1)
        cache.saveInProgressSnapshot(listOf(ip("one")),profileId=1)
        cache.saveNextUpSnapshot(listOf(nu("one")),profileId=1)
        assertEquals(listOf(ip("two")),cache.getInProgressSnapshot())
        assertEquals(listOf(nu("two")),cache.getNextUpSnapshot())
        assertEquals(listOf(ip("one")),cache.getInProgressSnapshot(1))
        assertEquals(listOf(nu("one")),cache.getNextUpSnapshot(1))
        assertEquals(6,cache.snapshotVersion.value)
    }
    @Test fun `cancelled mutex wait does not publish a snapshot or a version`() = actual { cache,_,mutex ->
        coroutineScope {
            mutex.lock()
            val queued=launch(start=CoroutineStart.UNDISPATCHED) { cache.saveNextUpSnapshot(listOf(nu("cancelled")),force=true) }
            queued.cancelAndJoin()
            mutex.unlock()
        }
        assertTrue(cache.getNextUpSnapshot().isEmpty())
        assertEquals(0,cache.snapshotVersion.value)
    }
    @Test fun `queued write captures its input list before dispatch`() = actual { cache,_,mutex ->
        coroutineScope {
            val items=mutableListOf(ip("admitted"))
            mutex.lock()
            val queued=async(start=CoroutineStart.UNDISPATCHED) { cache.saveInProgressSnapshot(items,force=true) }
            items.clear()
            mutex.unlock()
            queued.await()
            assertEquals(listOf(ip("admitted")),cache.getInProgressSnapshot())
        }
    }
    @Test fun `same-profile unchanged writes are suppressed and force preserves explicit updates`() = actual { cache,_,_ ->
        val items=listOf(ip("one"))
        cache.saveInProgressSnapshot(items)
        cache.saveInProgressSnapshot(items)
        assertEquals(1,cache.snapshotVersion.value)
        cache.saveInProgressSnapshot(listOf(ip("two")),force=true)
        assertEquals(listOf(ip("two")),cache.getInProgressSnapshot())
        assertEquals(2,cache.snapshotVersion.value)
    }

    @Test fun `externally removed profile files can be recreated with identical content`() = actual { cache,_,_ ->
        cache.saveInProgressSnapshot(listOf(ip("one")))
        cache.saveNextUpSnapshot(listOf(nu("one")))
        val field=ContinueWatchingEnrichmentCache::class.java.getDeclaredField("context").apply { isAccessible=true }
        val context=field.get(cache) as Context
        val dir=java.io.File(context.filesDir,"cw_enrichment")
        assertTrue(java.io.File(dir,"inprogress_1.json").delete())
        assertTrue(java.io.File(dir,"nextup_1.json").delete())
        cache.saveInProgressSnapshot(listOf(ip("one")))
        cache.saveNextUpSnapshot(listOf(nu("one")))
        assertEquals(listOf(ip("one")),cache.getInProgressSnapshot())
        assertEquals(listOf(nu("one")),cache.getNextUpSnapshot())
        assertEquals(4,cache.snapshotVersion.value)
    }
}
