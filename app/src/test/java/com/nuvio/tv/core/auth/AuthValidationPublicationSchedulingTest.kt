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
class AuthValidationPublicationSchedulingTest {
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
    private fun changed(oldError:Boolean=false,sameToken:Boolean=false)=actual { f ->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val old=user("old-validator","old@example.invalid")
        val fresh=user(if(sameToken) "old-validator" else "fresh-validator","fresh@example.invalid")
        coEvery { f.auth.retrieveUserForCurrentSession(false) } coAnswers {
            entered.complete(Unit);withContext(NonCancellable) { release.await() }
            if(oldError) throw IOException("controlled old validation reply") else old
        }
        val observed=Collections.synchronizedList(mutableListOf<AuthState>())
        val observer=launch(Dispatchers.Unconfined,start=CoroutineStart.UNDISPATCHED) { f.manager.authState.collect { observed.add(it) } }
        try {
            f.client(old,"old-token");entered.await()
            coEvery { f.auth.retrieveUserForCurrentSession(false) } returns fresh
            f.client(fresh,if(sameToken) "old-token" else "fresh-token")
            observed.clear();release.complete(Unit)
            f.waitUser(fresh.id,fresh.email!!)
            assertFalse("retired validation must not publish old account metadata",synchronized(observed) { observed.any { it==AuthState.FullAccount(old.id,old.email!!) } })
        } finally { release.complete(Unit);observer.cancelAndJoin() }
    }
    @Test fun `late successful old validation cannot publish a superseded client user`() = changed()
    @Test fun `late failed old validation cannot publish a superseded client user`() = changed(oldError=true)
    @Test fun `same token current user metadata replacement cannot publish old email`() = changed(sameToken=true)
    @Test fun `changed token preserving the same user still publishes the current account`() = actual { f ->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val current=user("refreshed-validator","refresh@example.invalid")
        coEvery { f.auth.retrieveUserForCurrentSession(false) } coAnswers {
            entered.complete(Unit);withContext(NonCancellable) { release.await() };current
        }
        try {
            f.client(current,"old-refresh-token");entered.await()
            coEvery { f.auth.retrieveUserForCurrentSession(false) } returns current
            f.client(current,"new-refresh-token");release.complete(Unit)
            f.waitUser(current.id,current.email!!)
            coVerify(exactly=0) { f.auth.clearSession() }
        } finally { release.complete(Unit) }
    }
    @Test fun `ordinary current validation publishes its current account`() = actual { f ->
        val current=user("ordinary-validator","ordinary@example.invalid")
        coEvery { f.auth.retrieveUserForCurrentSession(false) } returns current
        f.client(current,"ordinary-token");f.waitUser(current.id,current.email!!)
        coVerify(exactly=0) { f.auth.clearSession() }
    }
    @Test fun `ordinary current invalid validation still clears its remote session`() = actual { f ->
        val current=user("ordinary-validator","ordinary@example.invalid")
        coEvery { f.auth.retrieveUserForCurrentSession(false) } returns user("different-remote","other@example.invalid")
        f.client(current,"ordinary-token")
        withTimeout(3000) { while(f.manager.authState.value!=AuthState.SignedOut) yield() }
        coVerify(exactly=1) { f.auth.clearSession() }
    }
    @Test fun `cancelled actual Auth observer cannot publish after a noncooperative validation reply`() = actual { f ->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val current=user("stopped-validator","stop@example.invalid")
        coEvery { f.auth.retrieveUserForCurrentSession(false) } coAnswers {
            entered.complete(Unit);withContext(NonCancellable) { release.await() };current
        }
        try {
            f.client(current,"stopped-token");entered.await()
            val root=scope(f).coroutineContext[Job]!!;root.cancel();release.complete(Unit);root.join()
            assertEquals(AuthState.Loading,f.manager.authState.value)
        } finally { release.complete(Unit) }
    }
}
