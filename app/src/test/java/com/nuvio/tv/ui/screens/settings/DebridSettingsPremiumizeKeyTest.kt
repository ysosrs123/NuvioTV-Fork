package com.nuvio.tv.ui.screens.settings

import android.content.Context
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.PremiumizeApi
import com.nuvio.tv.data.remote.dto.PremiumizeAccountInfoDto
import com.nuvio.tv.domain.model.DebridSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DebridSettingsPremiumizeKeyTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val invalidMessage = "Could not validate this API key."
    private val settings = MutableStateFlow(DebridSettings())
    private val dataStore = mockk<DebridSettingsDataStore> {
        every { settings } returns this@DebridSettingsPremiumizeKeyTest.settings
        coEvery { setProviderApiKey(any(), any()) } coAnswers {
            val key = secondArg<String>()
            this@DebridSettingsPremiumizeKeyTest.settings.value =
                this@DebridSettingsPremiumizeKeyTest.settings.value.copy(premiumizeApiKey = key)
        }
    }
    private val context = mockk<Context> {
        every { getString(R.string.debrid_key_invalid) } returns invalidMessage
    }

    private fun viewModel(api: PremiumizeApi) =
        DebridSettingsViewModel(context, dataStore, mockk(relaxed = true), api, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

    @Test
    fun `accepted premiumize key is sent as bearer and saved`() = runTest {
        val api = mockk<PremiumizeApi> {
            coEvery { accountInfo(any()) } returns Response.success(
                PremiumizeAccountInfoDto(status = "success", customerId = "123")
            )
        }
        val vm = viewModel(api)
        var saved = false

        vm.validateAndSaveProviderApiKey(DebridProviders.PREMIUMIZE_ID, "  pmKey123  ") { saved = true }
        advanceUntilIdle()

        assertTrue(saved)
        coVerify(exactly = 1) { api.accountInfo("Bearer pmKey123") }
        coVerify(exactly = 1) { dataStore.setProviderApiKey(DebridProviders.PREMIUMIZE_ID, "pmKey123") }
        assertEquals("pmKey123", settings.value.premiumizeApiKey)
        assertFalse(vm.validating.value)
    }

    @Test
    fun `premiumize error status shows the invalid key message and saves nothing`() = runTest {
        val api = mockk<PremiumizeApi> {
            coEvery { accountInfo(any()) } returns Response.success(
                PremiumizeAccountInfoDto(status = "error", message = "not logged in")
            )
        }
        val vm = viewModel(api)
        val error = async { vm.validationError.first() }
        var saved = false

        vm.validateAndSaveProviderApiKey(DebridProviders.PREMIUMIZE_ID, "wrong") { saved = true }
        advanceUntilIdle()

        assertFalse(saved)
        assertEquals(invalidMessage, error.await())
        coVerify(exactly = 0) { dataStore.setProviderApiKey(any(), any()) }
    }

    @Test
    fun `premiumize http failure or network error counts as an invalid key`() = runTest {
        val api = mockk<PremiumizeApi> {
            coEvery { accountInfo("Bearer unauthorised") } returns
                Response.error(401, "{}".toResponseBody(null))
            coEvery { accountInfo("Bearer offline") } throws IOException("offline")
        }
        val vm = viewModel(api)
        val errors = mutableListOf<String>()
        val collector = async { vm.validationError.collect { errors += it } }

        vm.validateAndSaveProviderApiKey(DebridProviders.PREMIUMIZE_ID, "unauthorised") {}
        advanceUntilIdle()
        vm.validateAndSaveProviderApiKey(DebridProviders.PREMIUMIZE_ID, "offline") {}
        advanceUntilIdle()
        collector.cancel()

        assertEquals(listOf(invalidMessage, invalidMessage), errors)
        coVerify(exactly = 0) { dataStore.setProviderApiKey(any(), any()) }
    }

    @Test
    fun `clearing the premiumize key disconnects without calling premiumize`() = runTest {
        settings.value = DebridSettings(premiumizeApiKey = "pmKey123")
        val api = mockk<PremiumizeApi>()
        val vm = viewModel(api)
        var done = false

        vm.validateAndSaveProviderApiKey(DebridProviders.PREMIUMIZE_ID, "") { done = true }
        advanceUntilIdle()

        assertTrue(done)
        assertEquals("", settings.value.premiumizeApiKey)
        coVerify(exactly = 0) { api.accountInfo(any()) }
    }

    @Test
    fun `account response mapping`() {
        assertTrue(premiumizeAccountAccepted(Response.success(PremiumizeAccountInfoDto(status = "success"))))
        assertFalse(premiumizeAccountAccepted(Response.success(PremiumizeAccountInfoDto(status = "ERROR"))))
        assertFalse(premiumizeAccountAccepted(Response.success<PremiumizeAccountInfoDto>(null)))
        assertFalse(premiumizeAccountAccepted(Response.error(403, "".toResponseBody(null))))
    }
}
