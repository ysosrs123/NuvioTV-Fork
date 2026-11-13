package com.nuvio.tv.core.sync

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.OfflinePinStore
import com.nuvio.tv.core.profile.OfflinePinVault
import com.nuvio.tv.domain.model.AuthState
import io.github.jan.supabase.postgrest.Postgrest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePinNoSessionTest {
    private val authState = MutableStateFlow<AuthState>(AuthState.SignedOut)
    private val auth = mockk<AuthManager>(relaxed = true).also { every { it.authState } returns authState }
    private val pins = mockk<OfflinePinStore>(relaxed = true)
    private val service = ProfileSyncService(
        authManager = auth,
        postgrest = mockk<Postgrest>(relaxed = true),
        profileDataStore = mockk(relaxed = true),
        profileManager = mockk(relaxed = true),
        syncClientIdentity = mockk(relaxed = true),
        offlinePinStore = pins
    )

    @Test
    fun `signed out the saved PIN opens the profile and is kept`() = runBlocking {
        every { pins.check(2, "1234") } returns OfflinePinVault.Answer.Unlocked

        val result = service.verifyProfilePin(2, "1234").getOrThrow()

        assertTrue(result.unlocked)
        verify(exactly = 0) { pins.forgetIfMatches(any(), any()) }
        verify(exactly = 0) { pins.forget(any()) }
    }

    @Test
    fun `signed out a wrong PIN counts against the box lockout`() = runBlocking {
        every { pins.check(2, "0000") } returns OfflinePinVault.Answer.Refused(300)

        val result = service.verifyProfilePin(2, "0000").getOrThrow()

        assertEquals(false, result.unlocked)
        assertEquals(300, result.retryAfterSeconds)
        verify(exactly = 0) { pins.forgetIfMatches(any(), any()) }
    }

    @Test
    fun `while sign-in is still loading nothing is forgotten`() = runBlocking {
        authState.value = AuthState.Loading
        every { pins.check(2, "1234") } returns OfflinePinVault.Answer.NoCopy

        val result = service.verifyProfilePin(2, "1234")

        assertTrue(result.isFailure)
        verify(exactly = 0) { pins.forgetIfMatches(any(), any()) }
    }
}
