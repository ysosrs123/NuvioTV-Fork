package com.nuvio.tv.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.*
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.data.local.*
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

/** Actual repository scheduling with isolated fixture flows; never touches a user history store. */
class WatchProgressHydrationSchedulingTest {
    private class FixtureStore : DataStore<Preferences> {
        private val state=MutableStateFlow(emptyPreferences())
        private val mutex=kotlinx.coroutines.sync.Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences)->Preferences): Preferences = mutex.withLock {
            transform(state.value).also { state.value=it }
        }
    }
    private fun progress(id: String="tt-fixture") = WatchProgress(id,"movie",id,null,null,null,id,null,null,null,2000,10000,100)
    private fun metadata() = mockk<Meta>(relaxed=true).also { meta ->
        every { meta.name } returns "resolved fixture"; every { meta.poster } returns "fixture-poster"
        every { meta.backdropUrl } returns "fixture-backdrop"; every { meta.logo } returns null
        every { meta.videos } returns emptyList();every { meta.runtime } returns "90"
    }
    private class Request(val type: String,val id: String,val reply: CompletableDeferred<NetworkResult<Meta>> = CompletableDeferred(),
        val retired: CompletableDeferred<Unit> = CompletableDeferred())
    private class Fixture(val repository: WatchProgressRepositoryImpl,val prefs: WatchProgressPreferences,
        val active: MutableStateFlow<Int>,val selections: MutableStateFlow<Long>,val generations: MutableStateFlow<Long>,
        val source: MutableStateFlow<WatchProgressSource>,val requests: Channel<Request>,val allRequests: MutableList<Request>,
        val rows: Channel<List<WatchProgress>>,val sync: WatchProgressSyncService,val writes: Channel<WatchProgress>,
        val connected: MutableStateFlow<Boolean>,val tmdb: TmdbService,val mutations: WatchStateMutationStore,val sourceReady: CompletableDeferred<Unit>,val providerRows: MutableStateFlow<List<WatchProgress>>)
    private fun actual(provider: Boolean=false,nonCooperative: Boolean=false,itemId: String="tt-fixture",itemCount: Int=1,deferSource: Boolean=false, block: suspend CoroutineScope.(Fixture) -> Unit)=runBlocking {
        withTimeout(20_000) {
            val active=MutableStateFlow(1);val selections=MutableStateFlow(0L);val generations=MutableStateFlow(0L)
            val manager=mockk<ProfileManager>();every { manager.activeProfileId } returns active
            every { manager.profileSelectionRevision } returns selections;every { manager.profileHistoryGenerationChanges } returns generations
            val items=(0 until itemCount).map { progress(if(itemCount==1) itemId else "$itemId-$it") }
            val local=MutableStateFlow(if(provider) emptyList() else items)
            val stores=mutableMapOf<Pair<Int,String>,DataStore<Preferences>>()
            val factory=mockk<ProfileDataStoreFactory>()
            every { factory.get(any(),any()) } answers { stores.getOrPut(firstArg<Int>() to secondArg<String>()) { FixtureStore() } }
            val real=WatchProgressPreferences(factory,manager)
            if(!provider) { real.saveProgressBatch(items,1);local.value=real.getAllRawEntries(1).values.sortedByDescending(WatchProgress::lastWatched) }
            val prefs=spyk(real)
            every { prefs.observeAllProgress(any()) } returns local
            val writes=Channel<WatchProgress>(Channel.UNLIMITED)
            coEvery { prefs.saveProgress(any(),any()) } coAnswers {
                callOriginal()
                val p=firstArg<WatchProgress>();val current=prefs.getAllRawEntries(secondArg<Int>())[p.contentId]!!
                writes.send(current);local.value=prefs.getAllRawEntries(secondArg<Int>()).values.toList()
            }
            coEvery { prefs.removeProgress(any(),any(),any(),any()) } coAnswers {
                callOriginal();local.value=prefs.getAllRawEntries(arg<Int>(3)).values.toList()
            }
            coEvery { prefs.updateArtworkIfPresent(any(),any(),any()) } coAnswers {
                val storage=secondArg<WatchProgressPreferences.ArtworkStorage>()
                val before=prefs.getAllRawEntries(storage.profileId)
                callOriginal()
                val after=prefs.getAllRawEntries(storage.profileId)
                after.filter { (key,value)->value!=before[key] }.values.forEach { writes.send(it) }
                local.value=after.values.toList()
            }
            val source=MutableStateFlow(if(provider) WatchProgressSource.TRAKT else WatchProgressSource.NUVIO_SYNC)
            val sourceReady=CompletableDeferred<Unit>()
            if(!deferSource) sourceReady.complete(Unit)
            val observedSource=object: StateFlow<WatchProgressSource> by source {
                @OptIn(InternalCoroutinesApi::class)
                override suspend fun collect(collector: FlowCollector<WatchProgressSource>): Nothing {
                    sourceReady.await();source.collect(collector)
                }
            }
            val settings=mockk<TraktSettingsDataStore>(relaxed=true);every { settings.watchProgressSource } returns observedSource
            val auth=mockk<AuthManager>(relaxed=true);every { auth.isAuthenticated } returns true
            val meta=mockk<MetaRepository>();val requests=Channel<Request>(Channel.UNLIMITED)
            val allRequests=Collections.synchronizedList(mutableListOf<Request>())
            every { meta.getMetaFromPrimaryAddon(any(),any()) } answers {
                val type=firstArg<String>();val id=secondArg<String>()
                flow {
                    val request=Request(type,id);allRequests.add(request);requests.send(request)
                    try {
                        val reply=if(nonCooperative) withContext(NonCancellable) { request.reply.await() } else request.reply.await()
                        emit(reply)
                    } finally { request.retired.complete(Unit) }
                }
            }
            val tracking=mockk<TrackingProgressProvider>(relaxed=true)
            every { tracking.providerId } returns TrackingProviderId.TRAKT
            val connected=MutableStateFlow(true)
            every { tracking.isAuthenticated } returns connected
            val providerRows=MutableStateFlow(items)
            every { tracking.allProgress } returns providerRows
            every { tracking.retainsLocalProgress(any()) } returns false
            val sync=mockk<WatchProgressSyncService>(relaxed=true)
            val tmdb=mockk<TmdbService>(relaxed=true)
            val mutations=mockk<WatchStateMutationStore>(relaxed=true)
            val repository=WatchProgressRepositoryImpl(prefs,settings,mockk<LayoutPreferenceDataStore>(relaxed=true),sync,
                mockk<WatchedItemsPreferences>(relaxed=true),mockk<WatchedItemsSyncService>(relaxed=true),auth,meta,
                tmdb,manager,TrackingProgressProviderRegistry(if(provider) setOf(tracking) else emptySet()),
                TrackingHistoryWriterRegistry(emptySet()),mutations,mockk(relaxed=true))
            val rows=Channel<List<WatchProgress>>(Channel.UNLIMITED)
            val f=Fixture(repository,prefs,active,selections,generations,source,requests,allRequests,rows,sync,writes,connected,tmdb,mutations,sourceReady,providerRows)
            try { block(f) } finally {
                sourceReady.complete(Unit)
                synchronized(allRequests) { allRequests.forEach { it.reply.complete(NetworkResult.Success(metadata())) } }
                val owned=WatchProgressRepositoryImpl::class.java.getDeclaredField("syncScope").apply { isAccessible=true }.get(repository) as CoroutineScope
                owned.coroutineContext[Job]!!.cancelAndJoin()
            }
        }
    }
    private fun CoroutineScope.subscribe(f: Fixture) = launch { f.repository.allProgress.collect { f.rows.send(it) } }
    private suspend fun request(f: Fixture)=withTimeout(2000) { f.requests.receive() }
    private suspend fun retires(request: Request) { assertNotNull("optional request must retire with its subscriber owner",withTimeoutOrNull(500) { request.retired.await() }) }

    @Test fun `local rows emit before suspended artwork finishes`() = actual { f ->
        val subscriber=subscribe(f);try { request(f);assertEquals("tt-fixture",f.rows.receive().single().contentId) } finally { subscriber.cancelAndJoin() }
    }
    @Test fun `local subscriber cancellation retires optional artwork`() = actual { f ->
        val subscriber=subscribe(f);val pending=request(f);subscriber.cancelAndJoin();retires(pending)
        coVerify(exactly=0) { f.prefs.saveProgress(any(),any()) }
    }
    @Test fun `provider subscriber cancellation retires optional metadata`() = actual(true) { f ->
        val subscriber=subscribe(f);val pending=request(f);subscriber.cancelAndJoin();retires(pending)
    }
    @Test fun `same numeric history generation retires old local artwork`() = actual { f ->
        val subscriber=subscribe(f);try { val pending=request(f);f.generations.value++;retires(pending) } finally { subscriber.cancelAndJoin() }
    }
    @Test fun `same numeric selection revision retires old provider metadata`() = actual(true) { f ->
        val subscriber=subscribe(f);try { val pending=request(f);f.selections.value+=2;retires(pending) } finally { subscriber.cancelAndJoin() }
    }
    @Test fun `provider to local source change retires old optional metadata`() = actual(true) { f ->
        val subscriber=subscribe(f);try { val pending=request(f);f.source.value=WatchProgressSource.NUVIO_SYNC;retires(pending) } finally { subscriber.cancelAndJoin() }
    }
    @Test fun `cancelled local artwork can be retried on reentry`() = actual { f ->
        val first=subscribe(f);val old=request(f);first.cancelAndJoin();retires(old)
        val second=subscribe(f);try { val fresh=request(f);assertNotSame(old,fresh);fresh.reply.complete(NetworkResult.Success(metadata()));assertNotNull(withTimeoutOrNull(2000) { f.writes.receive() }) } finally { second.cancelAndJoin() }
    }
    @Test fun `optional subscriber cancellation preserves required progress sync`() = actual { f ->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val pushed=CompletableDeferred<Unit>()
        coEvery { f.sync.pushSingleToRemote("required",any(),1) } coAnswers {
            entered.complete(Unit);release.await();pushed.complete(Unit);Result.success(Unit)
        }
        f.repository.hasCompletedInitialPull=true
        val subscriber=subscribe(f);val pending=request(f)
        try {
            f.repository.saveProgress(progress("required"),profileId=1,syncRemote=true)
            withTimeout(2000) { entered.await() }
            subscriber.cancelAndJoin();retires(pending);assertFalse(pushed.isCompleted)
            release.complete(Unit);assertNotNull(withTimeoutOrNull(2000) { pushed.await() })
        } finally { release.complete(Unit);subscriber.cancelAndJoin() }
    }
    @Test fun `late local artwork cannot persist across rapid same-ID reselection`() = actual { f ->
        val subscriber=subscribe(f)
        try {
            val old=request(f);f.active.value=2;f.active.value=1;f.selections.value+=2
            old.reply.complete(NetworkResult.Success(metadata()))
            assertNull("old selection must not save artwork",withTimeoutOrNull(500) { f.writes.receive() })
        } finally { subscriber.cancelAndJoin() }
    }
    @Test fun `late provider metadata cannot publish across same-ID reselection`() = actual(true) { f ->
        val subscriber=subscribe(f)
        try {
            val old=request(f);f.selections.value+=2;old.reply.complete(NetworkResult.Success(metadata()))
            assertNull("old selection must not enrich fresh rows",withTimeoutOrNull(500) {
                while(true) { val rows=f.rows.receive();if(rows.any { it.poster=="fixture-poster" }) return@withTimeoutOrNull rows }
            })
        } finally { subscriber.cancelAndJoin() }
    }
    @Test fun `second provider subscriber survives cancellation of first`() = actual(true) { f ->
        val first=subscribe(f);val pending=request(f);val second=subscribe(f)
        val responder=launch { for (next in f.requests) next.reply.complete(NetworkResult.Success(metadata())) }
        try {
            first.cancelAndJoin();pending.reply.complete(NetworkResult.Success(metadata()))
            assertNotNull(withTimeoutOrNull(2000) {
                while(true) { val rows=f.rows.receive();if(rows.any { it.poster=="fixture-poster" }) return@withTimeoutOrNull rows }
            })
        } finally { first.cancelAndJoin();second.cancelAndJoin();responder.cancelAndJoin() }
    }

    @Test fun `optional artwork preserves a newer playback position`() = actual { f ->
        val subscriber=subscribe(f)
        try {
            val old=request(f)
            f.repository.saveProgress(progress().copy(position=8000),profileId=1,syncRemote=false)
            assertEquals(8000L,f.writes.receive().position)
            old.reply.complete(NetworkResult.Success(metadata()))
            assertEquals("quiet metadata must not restore the request's older position",8000L,withTimeout(2000) { f.writes.receive() }.position)
        } finally { subscriber.cancelAndJoin() }
    }
    @Test fun `optional artwork cannot restore a removed progress entry`() = actual { f ->
        val subscriber=subscribe(f)
        try {
            val old=request(f);f.repository.removeProgress("tt-fixture")
            old.reply.complete(NetworkResult.Success(metadata()))
            assertNull("late artwork must not recreate removed history",withTimeoutOrNull(500) { f.writes.receive() })
        } finally { subscriber.cancelAndJoin() }
    }

    @Test fun `two local subscribers retain independent artwork cancellation`() = actual { f ->
        val first=subscribe(f);val old=request(f);val second=subscribe(f);val fresh=request(f)
        try {
            first.cancelAndJoin();retires(old)
            fresh.reply.complete(NetworkResult.Success(metadata()))
            assertEquals("fixture-poster",withTimeout(2000) { f.writes.receive() }.poster)
        } finally { first.cancelAndJoin();second.cancelAndJoin() }
    }

    @Test fun `same numeric selection revision retires local artwork`() = actual { f ->
        val subscriber=subscribe(f)
        try { val old=request(f);f.selections.value+=2;retires(old);val fresh=request(f)
            fresh.reply.complete(NetworkResult.Success(metadata()));assertEquals("fixture-poster",withTimeout(2000) { f.writes.receive() }.poster)
        } finally { subscriber.cancelAndJoin() }
    }

    @Test fun `same numeric history generation retires provider metadata`() = actual(true) { f ->
        val subscriber=subscribe(f)
        try { val old=request(f);f.generations.value++;retires(old);assertNotSame(old,request(f)) }
        finally { subscriber.cancelAndJoin() }
    }

    @Test fun `provider disconnect retires old optional metadata`() = actual(true) { f ->
        val subscriber=subscribe(f)
        try { val old=request(f);f.connected.value=false;retires(old) } finally { subscriber.cancelAndJoin() }
    }

    @Test fun `cancelled blocked provider reply cannot delay fresh selection rows`() = actual(provider=true,nonCooperative=true) { f ->
        val subscriber=subscribe(f)
        try {
            val old=request(f);f.selections.value+=2
            val fresh=request(f) // A cancelled old RPC still awaits its controlled reply.
            assertFalse(old.retired.isCompleted)
            fresh.reply.complete(NetworkResult.Success(metadata()))
            assertNotNull(withTimeoutOrNull(2000) {
                while(true) { val rows=f.rows.receive();if(rows.any { it.poster=="fixture-poster" }) return@withTimeoutOrNull rows }
            })
            old.reply.complete(NetworkResult.Success(metadata().also { every { it.poster } returns "stale-poster" }))
            retires(old)
            assertNull(withTimeoutOrNull(500) {
                while(true) { val rows=f.rows.receive();if(rows.any { it.poster=="stale-poster" }) return@withTimeoutOrNull rows }
            })
        } finally { subscriber.cancelAndJoin() }
    }

    @Test fun `cancelled blocked local reply cannot edit a fresh selection`() = actual(nonCooperative=true) { f ->
        val subscriber=subscribe(f)
        try {
            val old=request(f);f.selections.value+=2;val fresh=request(f)
            assertFalse(old.retired.isCompleted)
            fresh.reply.complete(NetworkResult.Success(metadata()))
            assertEquals("fixture-poster",withTimeout(2000) { f.writes.receive() }.poster)
            old.reply.complete(NetworkResult.Success(metadata().also { every { it.poster } returns "stale-poster" }))
            retires(old);assertNull(withTimeoutOrNull(500) { f.writes.receive() })
        } finally { subscriber.cancelAndJoin() }
    }

    @Test fun `metadata ID fallback remains available to the current owner`() = actual(itemId="tmdb:123") { f ->
        val subscriber=subscribe(f)
        try {
            val first=request(f);assertEquals("tmdb:123",first.id)
            first.reply.complete(NetworkResult.Error("fixture miss"))
            val fallback=request(f);assertEquals("123",fallback.id)
            fallback.reply.complete(NetworkResult.Success(metadata()))
            assertEquals("fixture-poster",withTimeout(2000) { f.writes.receive() }.poster)
        } finally { subscriber.cancelAndJoin() }
    }

    @Test fun `retired metadata owner cannot admit an ID fallback`() = actual(itemId="tmdb:123",nonCooperative=true) { f ->
        val subscriber=subscribe(f);val old=request(f);subscriber.cancelAndJoin()
        old.reply.complete(NetworkResult.Error("fixture miss"));retires(old)
        assertNull(withTimeoutOrNull(500) { f.requests.receive() })
    }

    @Test fun `provider optional hydration admits at most thirty visible contents`() = actual(provider=true,itemCount=40) { f ->
        val subscriber=subscribe(f)
        try {
            val admitted=(1..30).map { request(f) }
            assertEquals(30,admitted.map { it.id }.toSet().size)
            assertNull(withTimeoutOrNull(500) { f.requests.receive() })
        } finally { subscriber.cancelAndJoin() }
    }

    @Test fun `local artwork admits ten rows with one active lookup`() = actual(itemCount=40) { f ->
        val subscriber=subscribe(f)
        try {
            repeat(10) {
                val pending=request(f)
                assertNull(withTimeoutOrNull(100) { f.requests.receive() })
                pending.reply.complete(NetworkResult.Success(metadata()))
            }
            assertNull(withTimeoutOrNull(500) { f.requests.receive() })
        } finally { subscriber.cancelAndJoin() }
    }

    @Test fun `current local owner retains TMDB artwork fallback`() = actual { f ->
        coEvery { f.tmdb.fetchImdbImages(any(),any()) } returns com.nuvio.tv.core.tmdb.TmdbImages("tmdb-backdrop","tmdb-poster")
        val subscriber=subscribe(f)
        try {
            val pending=request(f);pending.reply.complete(NetworkResult.Success(metadata().also {
                every { it.poster } returns null;every { it.backdropUrl } returns null
            }))
            assertEquals("tmdb-poster",withTimeout(2000) { f.writes.receive() }.poster)
        } finally { subscriber.cancelAndJoin() }
    }

    @Test fun `source change retires suspended TMDB fallback`() = actual { f ->
        val entered=CompletableDeferred<Unit>();val retired=CompletableDeferred<Unit>()
        coEvery { f.tmdb.fetchImdbImages(any(),any()) } coAnswers {
            entered.complete(Unit);try { awaitCancellation() } finally { retired.complete(Unit) }
        }
        val subscriber=subscribe(f)
        try {
            request(f).reply.complete(NetworkResult.Success(metadata().also {
                every { it.poster } returns null;every { it.backdropUrl } returns null
            }))
            withTimeout(2000) { entered.await() };f.source.value=WatchProgressSource.TRAKT
            assertNotNull(withTimeoutOrNull(500) { retired.await() })
        } finally { subscriber.cancelAndJoin() }
    }

    @Test fun `late noncooperative TMDB fallback cannot change fresh progress`() = actual { f ->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val retired=CompletableDeferred<Unit>()
        coEvery { f.tmdb.fetchImdbImages(any(),any()) } coAnswers {
            entered.complete(Unit)
            try { withContext(NonCancellable) { release.await() };com.nuvio.tv.core.tmdb.TmdbImages("stale-backdrop","stale-poster") }
            finally { retired.complete(Unit) }
        }
        val subscriber=subscribe(f)
        try {
            request(f).reply.complete(NetworkResult.Success(metadata().also {
                every { it.poster } returns null;every { it.backdropUrl } returns null
            }))
            withTimeout(2000) { entered.await() };f.selections.value+=2
            val fresh=request(f);fresh.reply.complete(NetworkResult.Success(metadata()))
            assertEquals("fixture-poster",withTimeout(2000) { f.writes.receive() }.poster)
            release.complete(Unit);withTimeout(2000) { retired.await() }
            assertNull(withTimeoutOrNull(500) { f.writes.receive() })
        } finally { release.complete(Unit);subscriber.cancelAndJoin() }
    }

    @Test fun `provider reconnect cannot keep an older optional request owner`() = actual(true) { f ->
        val subscriber=subscribe(f)
        try {
            val old=request(f)
            @Suppress("UNCHECKED_CAST")
            val connections=WatchProgressRepositoryImpl::class.java.getDeclaredField("progressProviderConnectionState")
                .apply { isAccessible=true }.get(f.repository) as StateFlow<Any>
            fun connected(value: Any): Boolean {
                val entries=if(value is Map<*,*>) value else value.javaClass.getDeclaredField("connections")
                    .apply { isAccessible=true }.get(value) as Map<*,*>
                return entries[TrackingProviderId.TRAKT]==true
            }
            f.connected.value=false;withTimeout(2000) { connections.first { !connected(it) } }
            f.connected.value=true;withTimeout(2000) { connections.first { connected(it) } }
            retires(old)
            assertNotSame(old,request(f))
        } finally { subscriber.cancelAndJoin() }
    }

    @Test fun `source away and back retires each observed artwork owner`() = actual(nonCooperative=true) { f ->
        val subscriber=subscribe(f)
        try {
            val old=request(f);f.source.value=WatchProgressSource.TRAKT;val away=request(f)
            f.source.value=WatchProgressSource.NUVIO_SYNC;val fresh=request(f)
            old.reply.complete(NetworkResult.Success(metadata().also { every { it.poster } returns "stale" }))
            away.reply.complete(NetworkResult.Success(metadata().also { every { it.poster } returns "stale" }))
            retires(old);retires(away);assertNull(withTimeoutOrNull(500) { f.writes.receive() })
            fresh.reply.complete(NetworkResult.Success(metadata()))
            assertEquals("fixture-poster",withTimeout(2000) { f.writes.receive() }.poster)
        } finally { subscriber.cancelAndJoin() }
    }

    @Test fun `subscriber cancellation preserves an admitted bulk progress sync`() = actual { f ->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val pushed=CompletableDeferred<Unit>()
        coEvery { f.sync.pushToRemote(1) } coAnswers {
            entered.complete(Unit);release.await();pushed.complete(Unit);Result.success(Unit)
        }
        f.repository.hasCompletedInitialPull=true
        val subscriber=subscribe(f);val pending=request(f)
        try {
            f.repository.saveProgressBatch(listOf(progress("required")),profileId=1,syncRemote=true)
            withTimeout(6000) { entered.await() }
            subscriber.cancelAndJoin();retires(pending);assertFalse(pushed.isCompleted)
            release.complete(Unit);assertNotNull(withTimeoutOrNull(2000) { pushed.await() })
        } finally { release.complete(Unit);subscriber.cancelAndJoin() }
    }

    @Test fun `quiet artwork edits cannot enqueue or push progress mutations`() = actual { f ->
        val subscriber=subscribe(f)
        try {
            request(f).reply.complete(NetworkResult.Success(metadata()))
            assertEquals("fixture-poster",withTimeout(2000) { f.writes.receive() }.poster)
            coVerify(exactly=0) { f.mutations.queueProgressUpserts(any(),any()) }
            coVerify(exactly=0) { f.mutations.queueWatchedUpserts(any(),any()) }
            coVerify(exactly=0) { f.sync.pushSingleToRemote(any(),any(),any()) }
            coVerify(exactly=0) { f.sync.pushToRemote(any()) }
        } finally { subscriber.cancelAndJoin() }
    }

    @Test fun `available local rows precede a suspended initial source preference`() = actual(deferSource=true) { f ->
        val subscriber=subscribe(f)
        try {
            assertEquals("tt-fixture",withTimeout(2000) { f.rows.receive() }.single().contentId)
            assertNull("optional metadata must wait for a known source owner",withTimeoutOrNull(250) { f.requests.receive() })
            f.sourceReady.complete(Unit)
            request(f).reply.complete(NetworkResult.Success(metadata()))
            assertEquals("fixture-poster",withTimeout(2000) { f.writes.receive() }.poster)
        } finally { f.sourceReady.complete(Unit);subscriber.cancelAndJoin() }
    }

    @Test fun `provider reclassification retires pending artwork for the old item type`() = actual(true) { f ->
        val subscriber=subscribe(f)
        try {
            val old=request(f)
            f.providerRows.value=listOf(progress().copy(contentType="series"))
            retires(old)
            val fresh=request(f);assertEquals("series",fresh.type)
            fresh.reply.complete(NetworkResult.Success(metadata().also { every { it.poster } returns "series-art" }))
            assertNotNull(withTimeoutOrNull(2000) {
                while(true) { val rows=f.rows.receive();if(rows.any { it.contentType=="series" && it.poster=="series-art" }) return@withTimeoutOrNull rows }
            })
        } finally { subscriber.cancelAndJoin() }
    }

    @Test fun `cached artwork cannot cross provider movie to series reclassification`() = actual(true) { f ->
        val subscriber=subscribe(f)
        try {
            request(f).reply.complete(NetworkResult.Success(metadata().also { every { it.poster } returns "movie-art" }))
            withTimeout(2000) {
                while(true) { val rows=f.rows.receive();if(rows.any { it.poster=="movie-art" }) break }
            }
            f.providerRows.value=listOf(progress().copy(contentType="series"))
            val changed=withTimeout(2000) {
                var found: WatchProgress?=null
                while(found==null) found=f.rows.receive().firstOrNull { it.contentType=="series" }
                requireNotNull(found)
            }
            assertNull("cached movie artwork must not decorate the new series",changed.poster)
            val fresh=request(f);assertEquals("series",fresh.type)
            fresh.reply.complete(NetworkResult.Success(metadata().also { every { it.poster } returns "series-art" }))
            assertNotNull(withTimeoutOrNull(2000) {
                while(true) { val rows=f.rows.receive();if(rows.any { it.poster=="series-art" }) return@withTimeoutOrNull rows }
            })
        } finally { subscriber.cancelAndJoin() }
    }

}
