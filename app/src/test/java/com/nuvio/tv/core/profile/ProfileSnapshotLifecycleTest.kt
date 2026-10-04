package com.nuvio.tv.core.profile

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.data.local.*
import com.nuvio.tv.core.auth.AccountLocalDataResetService
import kotlinx.coroutines.sync.Mutex
import com.nuvio.tv.domain.model.UserProfile
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Real manager/cache scheduling; all metadata dependencies and files are isolated fixtures. */
class ProfileSnapshotLifecycleTest {
    private fun ip(id: String) = CachedInProgressItem(id,"movie",id,null,null,null,id,null,null,null,10,100,1,10f)
    private fun nu(id: String) = CachedNextUpItem(id,"series",id,null,null,null,"$id:1:2",1,2,null,thumbnail=null,lastWatched=1,sortTimestamp=1)
    private fun profile(id: Int) = UserProfile(id,"profile$id","#1E88E5")
    private class Fixture(val dir: File, val data: ProfileDataStore, val factory: ProfileDataStoreFactory,
        val credentials: ProfileScopedCredentialStore, val entries: MutableStateFlow<List<UserProfile>>,
        val manager: ProfileManager, val cache: ContinueWatchingEnrichmentCache, val reset: AccountLocalDataResetService)
    private fun actual(block: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        withTimeout(15_000) {
            val dir=Files.createTempDirectory("profile-snapshot-lifecycle-").toFile()
            val context=mockk<Context>(relaxed=true)
            every { context.filesDir } returns dir
            val prefs=mockk<SharedPreferences>(relaxed=true); val editor=mockk<SharedPreferences.Editor>(relaxed=true)
            every { context.getSharedPreferences(any(),any()) } returns prefs
            every { prefs.edit() } returns editor
            every { editor.remove(any()) } returns editor
            val data=mockk<ProfileDataStore>(relaxed=true);val factory=mockk<ProfileDataStoreFactory>(relaxed=true)
            val credentials=mockk<ProfileScopedCredentialStore>(relaxed=true)
            val entries=MutableStateFlow(listOf(profile(1),profile(2),profile(3)))
            every { data.profilesList } returns entries
            every { data.activeProfileId } returns MutableStateFlow(2)
            every { data.hasEverSelectedProfile } returns MutableStateFlow(true)
            every { data.rememberLastProfileEnabled } returns MutableStateFlow(false)
            every { data.confirmExitEnabled } returns MutableStateFlow(false)
            every { data.startupSplashEnabled } returns MutableStateFlow(true)
            every { factory.historyGenerationChanges } returns MutableStateFlow(0L)
            coEvery { data.deleteProfile(any()) } coAnswers { val id=firstArg<Int>();entries.value=entries.value.filter { it.id!=id } }
            coEvery { data.upsertProfile(any()) } coAnswers { val p=firstArg<UserProfile>();entries.value=entries.value.filter { it.id!=p.id }+p }
            val storage=ContinueWatchingSnapshotStorage(context)
            val manager=ProfileManager(data,factory,setOf(credentials),context,storage)
            val cache=ContinueWatchingEnrichmentCache(context,manager,storage)
            coEvery { data.clearAll() } coAnswers { currentCoroutineContext().ensureActive();entries.value=listOf(profile(1)) }
            val reset=AccountLocalDataResetService(context,factory,data,mockk<ProfileLockStateDataStore>(relaxed=true),setOf(credentials),storage)
            val f=Fixture(dir,data,factory,credentials,entries,manager,cache,reset)
            try {
                manager.profiles.first { it.size==3 }
                block(f)
            } finally {
                val scope=ProfileManager::class.java.getDeclaredField("scope").apply { isAccessible=true }.get(manager) as CoroutineScope
                scope.coroutineContext[Job]!!.cancelAndJoin()
                dir.deleteRecursively()
            }
        }
    }
    private suspend fun removed(f: Fixture) { assertTrue(f.manager.deleteProfile(2)); f.manager.profiles.first { it.none { p -> p.id==2 } } }
    private suspend fun seed(f: Fixture) {
        f.cache.saveInProgressSnapshot(listOf(ip("old")),force=true,profileId=2)
        f.cache.saveNextUpSnapshot(listOf(nu("old")),force=true,profileId=2)
    }

    @Test fun `real profile deletion invalidates both cached snapshot kinds`() = actual { f ->
        seed(f);val epoch=f.cache.cacheCleared.value;removed(f)
        assertTrue(f.cache.getInProgressSnapshot(2).isEmpty());assertTrue(f.cache.getNextUpSnapshot(2).isEmpty())
        assertTrue("profile cleanup must publish an invalidation",f.cache.cacheCleared.value>epoch)
    }
    @Test fun `old pipeline save cannot restore files after real profile deletion`() = actual { f ->
        seed(f);val epoch=f.cache.cacheCleared.value;removed(f)
        f.cache.saveInProgressSnapshot(listOf(ip("late")),force=true,profileId=2,expectedClearVersion=epoch)
        f.cache.saveNextUpSnapshot(listOf(nu("late")),force=true,profileId=2,expectedClearVersion=epoch)
        assertTrue(f.cache.getInProgressSnapshot(2).isEmpty());assertTrue(f.cache.getNextUpSnapshot(2).isEmpty())
    }
    @Test fun `same numeric profile recreation rejects old expected version`() = actual { f ->
        seed(f);val epoch=f.cache.cacheCleared.value;removed(f)
        assertEquals(2,f.manager.createProfile("replacement","#ffffff")!!.id)
        f.cache.saveInProgressSnapshot(listOf(ip("late")),force=true,profileId=2,expectedClearVersion=epoch)
        f.cache.saveNextUpSnapshot(listOf(nu("late")),force=true,profileId=2,expectedClearVersion=epoch)
        assertTrue(f.cache.getInProgressSnapshot(2).isEmpty());assertTrue(f.cache.getNextUpSnapshot(2).isEmpty())
    }
    @Test fun `fresh identical snapshots immediately work after recreation`() = actual { f ->
        seed(f);removed(f);assertEquals(2,f.manager.createProfile("replacement","#ffffff")!!.id)
        f.cache.saveInProgressSnapshot(listOf(ip("old")),profileId=2)
        f.cache.saveNextUpSnapshot(listOf(nu("old")),profileId=2)
        assertEquals(listOf(ip("old")),f.cache.getInProgressSnapshot(2));assertEquals(listOf(nu("old")),f.cache.getNextUpSnapshot(2))
    }
    @Test fun `retired numeric profile rejects even a newly admitted unversioned save`() = actual { f ->
        removed(f)
        f.cache.saveInProgressSnapshot(listOf(ip("late")),force=true,profileId=2)
        assertTrue(f.cache.getInProgressSnapshot(2).isEmpty())
    }
    @Test fun `deletion preserves other profiles and required credential metadata cleanup`() = actual { f ->
        f.cache.saveInProgressSnapshot(listOf(ip("other")),force=true,profileId=3)
        f.cache.saveNextUpSnapshot(listOf(nu("other")),force=true,profileId=3)
        File(f.dir,"datastore").mkdirs(); File(f.dir,"datastore/player_p2.preferences_pb").writeText("two")
        File(f.dir,"datastore/player_p3.preferences_pb").writeText("three")
        File(f.dir,"plugin_code_p2").mkdirs();File(f.dir,"plugin_code_p2/code").writeText("two")
        removed(f)
        assertEquals(listOf(ip("other")),f.cache.getInProgressSnapshot(3));assertEquals(listOf(nu("other")),f.cache.getNextUpSnapshot(3))
        assertFalse(File(f.dir,"datastore/player_p2.preferences_pb").exists());assertTrue(File(f.dir,"datastore/player_p3.preferences_pb").exists())
        assertFalse(File(f.dir,"plugin_code_p2").exists())
        verify(exactly=1) { f.credentials.removeProfile(2) };coVerify(exactly=1) { f.factory.clearProfile(2) };coVerify(exactly=1) { f.data.deleteProfile(2) }
    }
    @Test fun `two admitted creates reserve different numeric IDs`() = actual { f ->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        coEvery { f.data.upsertProfile(any()) } coAnswers {
            val p=firstArg<UserProfile>();entered.complete(Unit);release.await();f.entries.value=f.entries.value.filter { it.id!=p.id }+p
        }
        val first=async(start=CoroutineStart.UNDISPATCHED) { f.manager.createProfile("first","#111111") }
        entered.await()
        val second=async(start=CoroutineStart.UNDISPATCHED) { f.manager.createProfile("second","#222222") }
        release.complete(Unit)
        assertNotEquals(first.await()!!.id,second.await()!!.id)
    }
    @Test fun `duplicate admitted deletes perform cleanup only once`() = actual { f ->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        coEvery { f.factory.clearProfile(2) } coAnswers { entered.complete(Unit);release.await() }
        val first=async { f.manager.deleteProfile(2) };entered.await()
        val second=async(start=CoroutineStart.UNDISPATCHED) { f.manager.deleteProfile(2) }
        release.complete(Unit)
        assertTrue(first.await());assertFalse(second.await())
        coVerify(exactly=1) { f.factory.clearProfile(2) };verify(exactly=1) { f.credentials.removeProfile(2) }
    }
    @Test fun `cancellation after retirement admission finishes metadata and files cleanup`() = actual { f ->
        seed(f);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        coEvery { f.factory.clearProfile(2) } coAnswers {
            withContext(NonCancellable) { entered.complete(Unit);release.await() }
        }
        val deletion=launch { f.manager.deleteProfile(2) };entered.await();deletion.cancel();release.complete(Unit);deletion.join()
        assertTrue("committed retirement cannot leave a listed profile",f.entries.value.none { it.id==2 })
        assertTrue(f.cache.getInProgressSnapshot(2).isEmpty());coVerify(exactly=1) { f.data.deleteProfile(2) }
    }
    @Test fun `primary profile deletion never starts retirement`() = actual { f ->
        assertFalse(f.manager.deleteProfile(1));coVerify(exactly=0) { f.factory.clearProfile(any()) };verify(exactly=0) { f.credentials.removeProfile(any()) }
    }
    @Test fun `deletion shares the cache file lock with already admitted saves`() = actual { f ->
        seed(f)
        val mutex=ContinueWatchingEnrichmentCache::class.java.getDeclaredField("mutex").apply { isAccessible=true }.get(f.cache) as Mutex
        mutex.lock()
        val oldSave=async(start=CoroutineStart.UNDISPATCHED) { f.cache.saveInProgressSnapshot(listOf(ip("queued")),force=true,profileId=2) }
        val deletion=async { f.manager.deleteProfile(2) }
        try {
            assertNull("deletion must await the snapshot file owner",withTimeoutOrNull(300) { deletion.await() })
        } finally { mutex.unlock() }
        oldSave.await();assertTrue(deletion.await());assertTrue(f.cache.getInProgressSnapshot(2).isEmpty())
    }
    @Test fun `create admitted during deletion reuses ID only after cleanup commits`() = actual { f ->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        coEvery { f.factory.clearProfile(2) } coAnswers { entered.complete(Unit);release.await() }
        val deletion=async { f.manager.deleteProfile(2) };entered.await()
        val creation=async(start=CoroutineStart.UNDISPATCHED) { f.manager.createProfile("replacement","#ffffff") }
        release.complete(Unit);assertTrue(deletion.await());assertEquals(2,creation.await()!!.id)
    }
    @Test fun `real account reset invalidates old snapshots for every profile`() = actual { f ->
        seed(f);f.cache.saveInProgressSnapshot(listOf(ip("three")),force=true,profileId=3)
        val epoch=f.cache.cacheCleared.value;f.reset.clearAfterSignOut()
        f.cache.saveInProgressSnapshot(listOf(ip("late")),force=true,profileId=2,expectedClearVersion=epoch)
        f.cache.saveNextUpSnapshot(listOf(nu("late")),force=true,profileId=3,expectedClearVersion=epoch)
        assertTrue(f.cache.getInProgressSnapshot(2).isEmpty());assertTrue(f.cache.getNextUpSnapshot(3).isEmpty())
        assertTrue(f.cache.cacheCleared.value>epoch)
        verify(exactly=1) { f.credentials.clearAllProfiles() };coVerify(exactly=1) { f.data.clearAll() }
    }
    @Test fun `cancellation after reset admission completes account cleanup`() = actual { f ->
        seed(f);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        coEvery { f.factory.clearProfileScopedData() } coAnswers { withContext(NonCancellable) { entered.complete(Unit);release.await() } }
        val reset=launch { f.reset.clearAfterSignOut() };entered.await();reset.cancel();release.complete(Unit);reset.join()
        assertEquals(listOf(profile(1)),f.entries.value);assertTrue(f.cache.getInProgressSnapshot(2).isEmpty())
        verify(exactly=1) { f.credentials.clearAllProfiles() }
    }
    @Test fun `profile creation waits for an admitted account reset`() = actual { f ->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        coEvery { f.factory.clearProfileScopedData() } coAnswers { entered.complete(Unit);release.await() }
        val reset=async { f.reset.clearAfterSignOut() };entered.await()
        val creation=async(start=CoroutineStart.UNDISPATCHED) { f.manager.createProfile("after reset","#ffffff") }
        try { assertNull("creation must not race metadata reset",withTimeoutOrNull(300) { creation.await() }) }
        finally { release.complete(Unit) }
        reset.await();val created=creation.await()!!
        assertEquals(2,created.id);assertTrue(f.entries.value.any { it==created })
    }

    @Test fun `cancelled creation waiting behind lifecycle cleanup never mutates metadata`() = actual { f ->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        coEvery { f.factory.clearProfile(2) } coAnswers { entered.complete(Unit);release.await() }
        val deletion=async { f.manager.deleteProfile(2) };entered.await()
        val creation=launch(start=CoroutineStart.UNDISPATCHED) { f.manager.createProfile("cancelled","#ffffff") }
        creation.cancelAndJoin();release.complete(Unit);assertTrue(deletion.await())
        coVerify(exactly=0) { f.data.upsertProfile(any()) };verify(exactly=0) { f.factory.markProfileCreated(any()) }
    }
    @Test fun `cancelled second deletion cannot retire a different profile`() = actual { f ->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        coEvery { f.factory.clearProfile(2) } coAnswers { entered.complete(Unit);release.await() }
        val first=async { f.manager.deleteProfile(2) };entered.await()
        val second=launch(start=CoroutineStart.UNDISPATCHED) { f.manager.deleteProfile(3) }
        second.cancelAndJoin();release.complete(Unit);assertTrue(first.await())
        assertTrue(f.entries.value.any { it.id==3 });coVerify(exactly=0) { f.factory.clearProfile(3) };verify(exactly=0) { f.credentials.removeProfile(3) }
    }
    @Test fun `deleting another profile preserves existing write throttle`() = actual { f ->
        f.cache.saveInProgressSnapshot(listOf(ip("existing")),profileId=3)
        val version=f.cache.snapshotVersion.value;removed(f)
        f.cache.saveInProgressSnapshot(listOf(ip("throttled")),profileId=3)
        assertEquals(version,f.cache.snapshotVersion.value);assertEquals(listOf(ip("existing")),f.cache.getInProgressSnapshot(3))
    }
    @Test fun `cancellation after creation admission completes metadata and snapshot activation`() = actual { f ->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        coEvery { f.data.upsertProfile(any()) } coAnswers {
            val p=firstArg<UserProfile>()
            withContext(NonCancellable) { entered.complete(Unit);release.await() }
            currentCoroutineContext().ensureActive();f.entries.value=f.entries.value+p
        }
        val creation=launch { f.manager.createProfile("committed","#ffffff") };entered.await();creation.cancel();release.complete(Unit);creation.join()
        assertTrue(f.entries.value.any { it.id==4 })
        f.cache.saveInProgressSnapshot(listOf(ip("new")),force=true,profileId=4)
        assertEquals(listOf(ip("new")),f.cache.getInProgressSnapshot(4))
    }

    @Test fun `selecting synced profiles restored after reset reopens fresh snapshots`() = actual { f ->
        seed(f);val oldEpoch=f.cache.cacheCleared.value;f.reset.clearAfterSignOut()
        // ProfileSyncService restores metadata through replaceAllProfiles, independently of createProfile.
        f.entries.value=listOf(profile(1),profile(2));f.manager.profiles.first { it.any { p -> p.id==2 } }
        f.manager.setActiveProfile(2)
        f.cache.saveInProgressSnapshot(listOf(ip("late")),force=true,profileId=2,expectedClearVersion=oldEpoch)
        assertTrue(f.cache.getInProgressSnapshot(2).isEmpty())
        f.cache.saveInProgressSnapshot(listOf(ip("fresh")),profileId=2)
        f.cache.saveNextUpSnapshot(listOf(nu("fresh")),profileId=2)
        assertEquals(listOf(ip("fresh")),f.cache.getInProgressSnapshot(2));assertEquals(listOf(nu("fresh")),f.cache.getNextUpSnapshot(2))
        verify(exactly=1) { f.factory.markProfileCreated(2) }
    }
    @Test fun `selecting synced metadata for a retired ID reopens it without accepting old saves`() = actual { f ->
        seed(f);val oldEpoch=f.cache.cacheCleared.value;removed(f)
        f.entries.value=f.entries.value+profile(2);f.manager.profiles.first { it.any { p -> p.id==2 } }
        f.manager.setActiveProfile(2)
        f.cache.saveNextUpSnapshot(listOf(nu("late")),force=true,profileId=2,expectedClearVersion=oldEpoch)
        assertTrue(f.cache.getNextUpSnapshot(2).isEmpty())
        f.cache.saveNextUpSnapshot(listOf(nu("fresh")),profileId=2)
        assertEquals(listOf(nu("fresh")),f.cache.getNextUpSnapshot(2));verify(exactly=1) { f.factory.markProfileCreated(2) }
    }

    @Test fun `invalid selection cannot reactivate a deleted profile`() = actual { f ->
        removed(f);val epoch=f.cache.cacheCleared.value;f.manager.setActiveProfile(2)
        f.cache.saveNextUpSnapshot(listOf(nu("invalid")),force=true,profileId=2)
        assertTrue(f.cache.getNextUpSnapshot(2).isEmpty());assertEquals(epoch,f.cache.cacheCleared.value)
        verify(exactly=0) { f.factory.markProfileCreated(2) };coVerify(exactly=0) { f.data.setActiveProfile(2) }
    }
    @Test fun `repeated valid selection keeps the active profile snapshot epoch`() = actual { f ->
        f.reset.clearAfterSignOut();f.entries.value=listOf(profile(1),profile(2));f.manager.profiles.first { it.size==2 }
        f.manager.setActiveProfile(2);val epoch=f.cache.cacheCleared.value
        f.cache.saveNextUpSnapshot(listOf(nu("fresh")),profileId=2)
        f.manager.setActiveProfile(2)
        assertEquals(epoch,f.cache.cacheCleared.value);assertEquals(listOf(nu("fresh")),f.cache.getNextUpSnapshot(2))
        verify(exactly=1) { f.factory.markProfileCreated(2) }
    }

}
