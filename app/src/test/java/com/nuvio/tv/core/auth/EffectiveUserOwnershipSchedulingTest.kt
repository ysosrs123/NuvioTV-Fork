package com.nuvio.tv.core.auth

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.data.local.*
import com.nuvio.tv.data.repository.AuthDiagnosticReportRepository
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.ServerConfiguration
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.ktor.http.Headers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import io.github.jan.supabase.postgrest.Postgrest
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import io.github.jan.supabase.auth.user.UserInfo
import kotlinx.coroutines.sync.Mutex
import org.junit.Test
import org.junit.Assert.*
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.Collections
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Actual AuthManager session observer plus actual AuthSessionValidator publication.
 * Isolated files and controlled auth/dependency mocks; no account/provider RPC.
 * Actual session-status observer remains active for controlled authenticated replies.
 * Controlled SDK user replies feed the actual validator; no credentials/network.
 * Required remote profile/history sync ownership remains separate.
 */
class EffectiveUserOwnershipSchedulingTest {
    private class OwnedSessionFlow(val values:MutableStateFlow<SessionStatus> = MutableStateFlow(SessionStatus.Initializing)):StateFlow<SessionStatus> by values {
        val initialProcessed=CompletableDeferred<Unit>()
        @OptIn(InternalCoroutinesApi::class)
        override suspend fun collect(collector:FlowCollector<SessionStatus>):Nothing {
            values.collect { status -> collector.emit(status);initialProcessed.complete(Unit) }
            throw AssertionError("Owned StateFlow unexpectedly completed")
        }
    }
    private class Fixture(val dir:File,val parent:File,val auth:Auth,val rpc:Postgrest,val manager:AuthManager,
        val storage:ContinueWatchingSnapshotStorage,val factory:ProfileDataStoreFactory,
        val data:ProfileDataStore,val credential:ProfileScopedCredentialStore,
        val profileClears:AtomicInteger,val credentialClears:AtomicInteger,val gate:CompletableDeferred<Unit>?)
    private fun actual(activeObserver:Boolean=true,block:suspend CoroutineScope.(Fixture)->Unit)=runBlocking {
        withTimeout(15_000) {
            val parent=File((System.getProperty("nuvio.workload.evidence") ?: System.getProperty("java.io.tmpdir")),"owned-auth-fixtures").canonicalFile
            require(parent.mkdirs() || parent.isDirectory)
            val dir=File(parent,UUID.randomUUID().toString()).canonicalFile
            require(dir.parentFile==parent && dir.mkdir())
            val context=mockk<Context>(relaxed=true)
            every { context.filesDir } returns dir
            val prefs=mockk<SharedPreferences>(relaxed=true);val editor=mockk<SharedPreferences.Editor>(relaxed=true)
            every { context.getSharedPreferences(any(),any()) } returns prefs
            every { prefs.edit() } returns editor
            every { editor.remove(any()) } returns editor
            val storage=ContinueWatchingSnapshotStorage(context)
            val factory=mockk<ProfileDataStoreFactory>(relaxed=true)
            val data=mockk<ProfileDataStore>(relaxed=true)
            val credentials=mockk<ProfileScopedCredentialStore>(relaxed=true)
            val profileClears=AtomicInteger();val credentialClears=AtomicInteger()
            coEvery { data.clearAll() } coAnswers { profileClears.incrementAndGet();Unit }
            every { credentials.clearAllProfiles() } answers { credentialClears.incrementAndGet();Unit }
            val reset=AccountLocalDataResetService(context,factory,data,mockk<ProfileLockStateDataStore>(relaxed=true),setOf(credentials),storage)
            val auth=mockk<Auth>(relaxed=true)
            every { auth.sessionStatus } returns OwnedSessionFlow()
            val notices=mockk<AuthSessionNoticeDataStore>(relaxed=true)
            val config=mockk<ServerConfiguration>(relaxed=true)
            every { config.isCustom } returns false
            val noNetwork=OkHttpClient.Builder().addInterceptor { throw AssertionError("No network is permitted in this fixture") }.build()
            val rpc=mockk<Postgrest>(relaxed=true)
            every { rpc.serializer } returns KotlinXSerializer(Json { ignoreUnknownKeys=true })
            val manager=AuthManager(auth,rpc,noNetwork,noNetwork,notices,reset,diagnosticReports(),config)
            val ownedScope=manager.javaClass.getDeclaredField("scope").apply { isAccessible=true }.get(manager) as CoroutineScope
            if(activeObserver) {
                (auth.sessionStatus as OwnedSessionFlow).initialProcessed.await()
            } else ownedScope.coroutineContext[Job]!!.cancelAndJoin()
            File(dir,"plugin_code_p2").mkdir();File(dir,"plugin_code_p2/fixture").writeText("isolated old account data")
            val f=Fixture(dir,parent,auth,rpc,manager,storage,factory,data,credentials,profileClears,credentialClears,null)
            try { block(f) } finally {
                if(storage.lifecycleMutex.holdsLock(f)) storage.lifecycleMutex.unlock(f)
                ownedScope.coroutineContext[Job]!!.cancelAndJoin()
                noNetwork.connectionPool.evictAll();noNetwork.dispatcher.executorService.shutdown()
                require(dir.canonicalFile.parentFile==parent.canonicalFile)
                dir.deleteRecursively()
            }
        }
    }
    // A relaxed mock hands submit's Result back as a bare Object, which the diagnostics upload
    // cannot read.
    private fun diagnosticReports()=mockk<AuthDiagnosticReportRepository>(relaxed=true).also {
        coEvery { it.submit(any()) } returns Result.success("fixture-report")
    }
    private fun Fixture.fullAccount() {
        @Suppress("UNCHECKED_CAST")
        val state=manager.javaClass.getDeclaredField("_authState").apply { isAccessible=true }.get(manager) as MutableStateFlow<AuthState>
        state.value=AuthState.FullAccount("fixture-old","old@example.invalid")
    }
    private suspend fun waitSignedOut(f:Fixture) {
        withTimeout(3000) { while(f.manager.authState.value!=AuthState.SignedOut) yield() }
    }
    private fun assertClean(f:Fixture) {
        assertFalse("committed sign-out must finish its local account cleanup",File(f.dir,"plugin_code_p2").exists())
        assertEquals(1,f.profileClears.get());assertEquals(1,f.credentialClears.get())
        coVerify(exactly=1) { f.factory.clearProfileScopedData() }
    }
    private suspend fun Fixture.login(userId:String="fixture-new",email:String="fresh@example.invalid") {
        val user=mockk<UserInfo>(relaxed=true)
        every { user.id } returns userId
        every { user.email } returns email
        every { auth.currentUserOrNull() } returns user
        every { auth.currentAccessTokenOrNull() } returns "controlled-fresh-token-$userId-${UUID.randomUUID()}"
        coEvery { auth.retrieveUserForCurrentSession(false) } returns user
        (auth.sessionStatus as OwnedSessionFlow).values.value=mockk<SessionStatus.Authenticated>(relaxed=true)
        withTimeout(3000) { while(manager.authState.value!=AuthState.FullAccount(userId,email)) yield() }
        File(dir,"plugin_code_p2/fixture").writeText("fresh observer account data")
    }
    private fun assertFresh(f:Fixture) {
        assertTrue("retired queued account reset must preserve the new account files",File(f.dir,"plugin_code_p2/fixture").isFile)
        assertEquals("fresh observer account data",File(f.dir,"plugin_code_p2/fixture").readText())
        assertEquals(0,f.profileClears.get());assertEquals(0,f.credentialClears.get())
    }
    private fun Fixture.client(user:UserInfo,token:String) {
        every { auth.currentUserOrNull() } returns user
        every { auth.currentAccessTokenOrNull() } returns token
        every { auth.currentSessionOrNull() } returns mockk(relaxed=true)
        (auth.sessionStatus as OwnedSessionFlow).values.value=mockk<SessionStatus.Authenticated>(relaxed=true)
    }
    private fun user(id:String,email:String):UserInfo {
        val value=mockk<UserInfo>(relaxed=true)
        every { value.id } returns id
        every { value.email } returns email
        return value
    }
    private suspend fun Fixture.waitUser(id:String,email:String) {
        withTimeout(3000) { while(manager.authState.value!=AuthState.FullAccount(id,email)) yield() }
    }
    private fun scope(f:Fixture)=f.manager.javaClass.getDeclaredField("scope").apply { isAccessible=true }.get(f.manager) as CoroutineScope
    private fun reply(f:Fixture,owner:String)=PostgrestResult(JsonPrimitive(owner).toString(),Headers.Empty,f.rpc)
    private fun cached(f:Fixture)=f.manager.javaClass.getDeclaredField("cachedEffectiveUserId").apply { isAccessible=true }.get(f.manager)
    private fun obsolete(signedOut:Boolean=false,failed:Boolean=false)=actual { f ->
        f.login("old-sync-user","old-sync@example.invalid")
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        coEvery { f.rpc.rpc("get_sync_owner") } coAnswers {
            entered.complete(Unit);withContext(NonCancellable) { release.await() }
            if(failed) throw IOException("controlled retired sync-owner reply")
            reply(f,"old-linked-owner")
        }
        val pending=async { f.manager.getEffectiveUserId() }
        try {
            entered.await()
            if(signedOut) {
                @Suppress("UNCHECKED_CAST")
                val state=f.manager.javaClass.getDeclaredField("_authState").apply { isAccessible=true }.get(f.manager) as MutableStateFlow<AuthState>
                state.value=AuthState.SignedOut
            } else f.login("fresh-sync-user","fresh-sync@example.invalid")
            release.complete(Unit)
            assertNull("retired account lookup must return no effective user",pending.await())
            assertNull("retired account lookup must not populate the effective-user cache",cached(f))
            if(!signedOut) {
                coEvery { f.rpc.rpc("get_sync_owner") } returns reply(f,"fresh-linked-owner")
                assertEquals("fresh-linked-owner",f.manager.getEffectiveUserId())
            }
        } finally { release.complete(Unit);pending.cancelAndJoin() }
    }
    @Test fun `late effective owner reply cannot escape a different user`() = obsolete()
    @Test fun `late effective owner reply cannot escape sign out`() = obsolete(signedOut=true)
    @Test fun `retired effective owner failure cannot fall back to old own user`() = obsolete(failed=true)
    @Test fun `ordinary effective owner resolution caches the linked owner`() = actual { f ->
        f.login("ordinary-sync-user","ordinary-sync@example.invalid")
        coEvery { f.rpc.rpc("get_sync_owner") } returns reply(f,"ordinary-linked-owner")
        assertEquals("ordinary-linked-owner",f.manager.getEffectiveUserId())
        assertEquals("ordinary-linked-owner",f.manager.getEffectiveUserId())
        coVerify(exactly=1) { f.rpc.rpc("get_sync_owner") }
    }
    @Test fun `ordinary failed effective owner resolution retains own user fallback`() = actual { f ->
        f.login("ordinary-sync-user","ordinary-sync@example.invalid")
        coEvery { f.rpc.rpc("get_sync_owner") } throws IOException("controlled current lookup failure")
        assertEquals("ordinary-sync-user",f.manager.getEffectiveUserId())
    }
    @Test fun `ordinary failed effective owner resolution preserves disabled fallback`() = actual { f ->
        f.login("ordinary-sync-user","ordinary-sync@example.invalid")
        coEvery { f.rpc.rpc("get_sync_owner") } throws IOException("controlled current lookup failure")
        assertNull(f.manager.getEffectiveUserId(fallbackToOwnIdOnFailure=false))
    }
    @Test fun `cancelled effective owner caller cannot return after noncooperative reply`() = actual { f ->
        f.login("ordinary-sync-user","ordinary-sync@example.invalid")
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var returned=false
        coEvery { f.rpc.rpc("get_sync_owner") } coAnswers {
            entered.complete(Unit);withContext(NonCancellable) { release.await() };reply(f,"cancelled-linked-owner")
        }
        val pending=launch { f.manager.getEffectiveUserId();returned=true }
        try {
            entered.await();pending.cancel();release.complete(Unit);pending.join()
            assertFalse("cancelled caller must not receive a successful or fallback result",returned)
            assertNull(cached(f))
        } finally { release.complete(Unit);pending.cancelAndJoin() }
    }
}
