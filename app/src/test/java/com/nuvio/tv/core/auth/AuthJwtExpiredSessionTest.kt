package com.nuvio.tv.core.auth

import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.repository.AuthDiagnosticReportRepository
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.ServerConfiguration
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AuthJwtExpiredSessionTest {
    private class Fixture(
        val auth: Auth,
        val reset: AccountLocalDataResetService,
        val manager: AuthManager
    )

    private fun fixture(): Fixture {
        val auth = mockk<Auth>(relaxed = true)
        every { auth.sessionStatus } returns MutableStateFlow<SessionStatus>(SessionStatus.Initializing)
        val notices = mockk<AuthSessionNoticeDataStore>(relaxed = true)
        coEvery { notices.markUnexpectedNuvioLogoutIfNeeded() } returns true
        val reset = mockk<AccountLocalDataResetService>(relaxed = true)
        val reports = mockk<AuthDiagnosticReportRepository>(relaxed = true)
        coEvery { reports.submit(any()) } returns Result.success("fixture-report")
        val config = mockk<ServerConfiguration>(relaxed = true)
        every { config.isCustom } returns false
        val httpClient = OkHttpClient()
        val manager = AuthManager(
            auth,
            mockk<Postgrest>(relaxed = true),
            httpClient,
            httpClient,
            notices,
            reset,
            reports,
            config,
            dagger.Lazy { SignOutSafetyCopy {} }
        )
        return Fixture(auth, reset, manager)
    }

    private suspend fun Fixture.close() {
        val scope = manager.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(manager) as CoroutineScope
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    private fun Fixture.assertSignInKept() {
        coVerify(exactly = 0) { auth.clearSession() }
        coVerify(exactly = 0) { reset.clearAfterSignOut() }
        assertNotEquals(AuthState.SignedOut, manager.authState.value)
    }

    @Test
    fun `jwt expired with no session held keeps the sign-in and local data`() = runBlocking {
        val fixture = fixture()
        try {
            every { fixture.auth.currentSessionOrNull() } returns null

            assertFalse(fixture.manager.refreshSessionIfJwtExpired(IllegalStateException("JWT expired")))

            fixture.assertSignInKept()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `jwt expired keeps the sign-in when the session is gone by the time the refresh starts`() = runBlocking {
        val fixture = fixture()
        try {
            val session = UserSession(
                accessToken = "access",
                refreshToken = "refresh",
                expiresIn = 3600L,
                tokenType = "bearer",
                user = null
            )
            every { fixture.auth.currentSessionOrNull() } returnsMany listOf(session, null)

            assertFalse(fixture.manager.refreshSessionIfJwtExpired(IllegalStateException("JWT expired")))

            fixture.assertSignInKept()
        } finally {
            fixture.close()
        }
    }
}
