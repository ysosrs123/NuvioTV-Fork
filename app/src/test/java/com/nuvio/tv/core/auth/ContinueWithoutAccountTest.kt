package com.nuvio.tv.core.auth

import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.repository.AuthDiagnosticReportRepository
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.ServerConfiguration
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWithoutAccountTest {
    private val auth = mockk<Auth>(relaxed = true).also {
        every { it.sessionStatus } returns MutableStateFlow<SessionStatus>(SessionStatus.Initializing)
    }
    private val reset = mockk<AccountLocalDataResetService>(relaxed = true)

    private fun manager(): AuthManager {
        val config = mockk<ServerConfiguration>(relaxed = true)
        every { config.isCustom } returns false
        val httpClient = OkHttpClient()
        return AuthManager(
            auth,
            mockk<Postgrest>(relaxed = true),
            httpClient,
            httpClient,
            mockk<AuthSessionNoticeDataStore>(relaxed = true),
            reset,
            mockk<AuthDiagnosticReportRepository>(relaxed = true),
            config,
            dagger.Lazy { SignOutSafetyCopy {} }
        )
    }

    private suspend fun AuthManager.close() {
        val scope = javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(this) as CoroutineScope
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test
    fun `with no sign-in on the box nothing is cleared`() = runBlocking {
        val manager = manager()
        try {
            every { auth.currentSessionOrNull() } returns null

            manager.continueWithoutAccount()

            coVerify(exactly = 0) { reset.clearAfterSignOut() }
            coVerify(exactly = 0) { auth.signOut(any()) }
            assertEquals(AuthState.SignedOut, manager.authState.value)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `a sign-in still held is ended and its data cleared`() = runBlocking {
        val manager = manager()
        try {
            every { auth.currentSessionOrNull() } returns UserSession(
                accessToken = "access",
                refreshToken = "refresh",
                expiresIn = 3600L,
                tokenType = "bearer",
                user = null
            )

            manager.continueWithoutAccount()

            coVerify(exactly = 1) { reset.clearAfterSignOut() }
            assertEquals(AuthState.SignedOut, manager.authState.value)
        } finally {
            manager.close()
        }
    }
}
