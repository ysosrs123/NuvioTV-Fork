package com.nuvio.tv.core.auth

import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.repository.AuthDiagnosticReportRepository
import com.nuvio.tv.domain.model.ServerConfiguration
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class UnexpectedSignOutSafetyCopyTest {
    private val reset = mockk<AccountLocalDataResetService>(relaxed = true)
    private val notices = mockk<AuthSessionNoticeDataStore>(relaxed = true).also {
        coEvery { it.markUnexpectedNuvioLogoutIfNeeded() } returns true
    }

    private fun manager(copy: SignOutSafetyCopy): AuthManager {
        val auth = mockk<Auth>(relaxed = true)
        every { auth.sessionStatus } returns MutableStateFlow<SessionStatus>(SessionStatus.NotAuthenticated(false))
        every { auth.currentSessionOrNull() } returns null
        val config = mockk<ServerConfiguration>(relaxed = true)
        every { config.isCustom } returns false
        every { config.backendUrl } returns "https://server.invalid"
        every { config.publishableKey } returns "key"
        val serverAnswers = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody())
                .build()
        }.build()
        return AuthManager(
            auth,
            mockk<Postgrest>(relaxed = true),
            serverAnswers,
            serverAnswers,
            notices,
            reset,
            mockk<AuthDiagnosticReportRepository>(relaxed = true),
            config,
            dagger.Lazy { copy }
        )
    }

    private suspend fun AuthManager.close() {
        val scope = javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(this) as CoroutineScope
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test
    fun `the box is cleared once the copy is saved`() = runBlocking {
        val manager = manager(SignOutSafetyCopy {})
        try {
            coVerify(timeout = 5000, exactly = 1) { reset.clearAfterSignOut() }
            coVerify(exactly = 0) { notices.markDataKeptForLastAccount() }
        } finally {
            manager.close()
        }
    }

    @Test
    fun `the box keeps its data when the copy cannot be saved`() = runBlocking {
        val manager = manager(SignOutSafetyCopy { throw IOException("no space") })
        try {
            coVerify(timeout = 5000, exactly = 1) { notices.markDataKeptForLastAccount() }
            coVerify(exactly = 0) { reset.clearAfterSignOut() }
        } finally {
            manager.close()
        }
    }
}
