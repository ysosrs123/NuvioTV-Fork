package com.nuvio.tv.core.sync

import android.content.Context
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.UserProfile
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.ktor.http.Headers
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** Actual ProfileSyncService -> cached PostgrestResult/serializer -> actual
 * ProfileDataStore normalization/serialization/edit. Auth and RPC are controlled
 * dependencies; the DataStore core is isolated memory with explicit admission.
 * This does not establish Auth observer or on-disk/global lifecycle ownership.
 */
class ProfilePullUserOwnershipSchedulingTest {
    // Identical-user signout/recreation requires an account generation and
    // is outside this scoped class.
    private class MemoryPrefs:DataStore<Preferences> {
        val values=MutableStateFlow(emptyPreferences())
        val mutex=Mutex()
        var entered:CompletableDeferred<Unit>?=null
        override val data:Flow<Preferences> get()=values
        override suspend fun updateData(transform:suspend (Preferences)->Preferences):Preferences {
            entered?.complete(Unit)
            return mutex.withLock { coroutineContext.ensureActive();transform(values.value).also { values.value=it } }
        }
    }
    private data class Reply(val result:CompletableDeferred<String> = CompletableDeferred())
    private class Fixture {
        val authState=MutableStateFlow<AuthState>(AuthState.FullAccount("old-account","old@example.invalid"))
        val auth=mockk<AuthManager>(relaxed=true)
        val rpc=mockk<Postgrest>()
        val requests=Channel<Reply>(Channel.UNLIMITED)
        val replies=mutableListOf<Reply>()
        val calls=AtomicInteger()
        val memory=MemoryPrefs()
        val context=mockk<Context>(relaxed=true)
        val store:ProfileDataStore
        val service:ProfileSyncService
        var immediate:String?=null
        var nonCooperativeReply=false
        init {
            every { auth.authState } returns authState
            every { context.applicationContext } returns context
            every { context.filesDir } returns File(System.getProperty("nuvio.account.evidence") ?: System.getProperty("java.io.tmpdir"),"unused-profile-delegate")
            every { context.getString(any(),*anyVararg()) } returns "Primary"
            store=ProfileDataStore(context,Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build())
            store.javaClass.getDeclaredField("dataStore").apply { isAccessible=true }.set(store,memory)
            every { rpc.serializer } returns KotlinXSerializer(Json { ignoreUnknownKeys=true })
            coEvery { rpc.rpc("sync_pull_profiles") } coAnswers {
                calls.incrementAndGet()
                val json=immediate ?: run {
                    val r=Reply();synchronized(replies) { replies.add(r) };requests.send(r);if(nonCooperativeReply) withContext(NonCancellable) { r.result.await() } else r.result.await()
                }
                PostgrestResult(json,Headers.Empty,rpc)
            }
            service=ProfileSyncService(auth,rpc,store,mockk<ProfileManager>(relaxed=true),mockk<SyncClientIdentity>(relaxed=true))
        }
        suspend fun seed() { store.replaceAllProfiles(listOf(UserProfile(id=2,name="Fresh current",avatarColorHex="#1E88E5"))) }
        @Suppress("UNCHECKED_CAST")
        fun currentProfiles():List<UserProfile> {
            // Constructor-created public flows capture its original DataStore.
            // Inspect the injected core through the same actual private decoder;
            // never open that unused filesystem delegate in this fixture.
            val decode=store.javaClass.getDeclaredMethod("parseProfiles",String::class.java).apply { isAccessible=true }
            return decode.invoke(store,memory.values.value[stringPreferencesKey("profiles_json")]) as List<UserProfile>
        }
        suspend fun assertFresh() { assertEquals("Fresh current",currentProfiles().single { it.id==2 }.name) }
        fun freshAccount(sameId:Boolean=false) { authState.value=AuthState.FullAccount(if(sameId) "old-account" else "new-account",if(sameId) "old@example.invalid" else "fresh@example.invalid") }
        fun close() { synchronized(replies) { replies.forEach { it.result.complete("[]") } };requests.close() }
    }
    @Before fun start() { mockkStatic(SystemClock::class);every { SystemClock.elapsedRealtime() } returns 10_000L }
    @After fun finish() { unmockkStatic(SystemClock::class) }
    private fun actual(block:suspend CoroutineScope.(Fixture)->Unit)=runBlocking {
        withTimeout(15_000) {
            val f=Fixture();f.seed()
            try { block(f) } finally { if(f.memory.mutex.holdsLock(f)) f.memory.mutex.unlock(f);f.close() }
        }
    }
    @Test fun `ordinary current profile pull decodes and commits remote profile metadata`() = actual { f ->
        val work=async { f.service.pullFromRemote(true) };f.requests.receive().result.complete(REMOTE)
        assertTrue(work.await().isSuccess)
        assertEquals("Old remote",f.currentProfiles().single { it.id==2 }.name)
    }
    @Test fun `empty remote profile list preserves current local profile metadata`() = actual { f ->
        val work=async { f.service.pullFromRemote(true) };f.requests.receive().result.complete("[]")
        assertTrue(work.await().isSuccess);f.assertFresh()
    }
    @Test fun `fresh current profile pull cache suppresses duplicate ordinary RPC`() = actual { f ->
        f.immediate=REMOTE
        assertTrue(f.service.pullFromRemote().isSuccess);assertTrue(f.service.pullFromRemote().isSuccess)
        assertEquals(1,f.calls.get())
    }
    @Test fun `forced current profile pull preserves explicit refresh RPC`() = actual { f ->
        f.immediate=REMOTE
        assertTrue(f.service.pullFromRemote().isSuccess);assertTrue(f.service.pullFromRemote(true).isSuccess)
        assertEquals(2,f.calls.get())
    }
    @Test fun `old remote profile reply cannot replace a different current account`() = actual { f ->
        val old=async { f.service.pullFromRemote(true) };val reply=f.requests.receive()
        f.freshAccount();reply.result.complete(REMOTE);old.await();f.assertFresh()
    }
    @Test fun `old remote profile reply cannot restore metadata after signout`() = actual { f ->
        val old=async { f.service.pullFromRemote(true) };val reply=f.requests.receive()
        f.authState.value=AuthState.SignedOut;reply.result.complete(REMOTE);old.await();f.assertFresh()
    }
    @Test fun `account change while actual profile edit waits cannot commit old metadata`() = actual { f ->
        f.memory.mutex.lock(f);f.memory.entered=CompletableDeferred();f.immediate=REMOTE
        val old=async { f.service.pullFromRemote(true) }
        f.memory.entered!!.await();f.freshAccount()
        f.memory.mutex.unlock(f);old.await();f.assertFresh()
    }
    @Test fun `signed out initial pull does not admit an account profile RPC`() = actual { f ->
        f.authState.value=AuthState.SignedOut;f.immediate=REMOTE
        f.service.pullFromRemote(true);assertEquals(0,f.calls.get());f.assertFresh()
    }
    @Test fun `retired remote pull does not return old profiles as a current successful result`() = actual { f ->
        val old=async { f.service.pullFromRemote(true) };val reply=f.requests.receive()
        f.freshAccount();reply.result.complete(REMOTE)
        assertFalse("retired result must not be accepted as current remote profiles",old.await().getOrNull().orEmpty().any { it.name=="Old remote" })
    }
    @Test fun `ordinary current JWT refresh retry preserves profile metadata synchronization`() = actual { f ->
        coEvery { f.auth.refreshSessionIfJwtExpired(any()) } returns true
        coEvery { f.rpc.rpc("sync_pull_profiles") } coAnswers {
            if(f.calls.incrementAndGet()==1) throw IOException("controlled auth-expired reply")
            PostgrestResult(REMOTE,Headers.Empty,f.rpc)
        }
        assertTrue(f.service.pullFromRemote(true).isSuccess)
        assertEquals(2,f.calls.get());assertEquals("Old remote",f.currentProfiles().single { it.id==2 }.name)
    }
    @Test fun `account change during JWT refresh cannot admit another old profile RPC`() = actual { f ->
        coEvery { f.auth.refreshSessionIfJwtExpired(any()) } coAnswers { f.freshAccount();true }
        coEvery { f.rpc.rpc("sync_pull_profiles") } coAnswers {
            if(f.calls.incrementAndGet()==1) throw IOException("controlled auth-expired reply")
            PostgrestResult(REMOTE,Headers.Empty,f.rpc)
        }
        f.service.pullFromRemote(true);assertEquals(1,f.calls.get());f.assertFresh()
    }
    @Test fun `cancelled caller late noncooperative reply cannot write profile metadata`() = actual { f ->
        f.nonCooperativeReply=true
        val old=async { f.service.pullFromRemote(true) };val reply=f.requests.receive()
        old.cancel();reply.result.complete(REMOTE);old.join();f.assertFresh()
    }
    companion object { private const val REMOTE="[{\"profile_index\":2,\"name\":\"Old remote\",\"avatar_color_hex\":\"#1E88E5\"}]" }
}
