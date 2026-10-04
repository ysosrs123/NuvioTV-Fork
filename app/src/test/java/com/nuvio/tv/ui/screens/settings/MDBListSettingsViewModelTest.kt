package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.mdblist.MdbListTestHarness
import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.data.remote.dto.mdblist.MDBListUserDto
import com.nuvio.tv.domain.model.MDBListSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class MDBListSettingsViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun `connected account can save and clear a separate ratings key`() = runTest {
        val harness = MdbListTestHarness()
        val preferences = MutableStateFlow(MDBListSettings(enabled = true))
        val store = mockk<MDBListSettingsDataStore> {
            every { settings } returns preferences
            coEvery { setApiKey(any()) } coAnswers {
                preferences.value = preferences.value.copy(apiKey = firstArg())
            }
        }
        val api = mockk<MDBListApi> {
            coEvery { getUser("separate-key") } returns Response.success(MDBListUserDto())
        }
        val traktSettings = mockk<TraktSettingsDataStore>(relaxed = true) {
            every { watchProgressSource } returns MutableStateFlow(WatchProgressSource.NUVIO_SYNC)
        }
        val viewModel = MDBListSettingsViewModel(
            store, api, harness.store,
            mdbListAccountApi = harness.api,
            traktSettingsDataStore = traktSettings,
            trackingSourceController = mockk(relaxed = true)
        )
        runCurrent()
        assertFalse(viewModel.uiState.value.isConnected)

        harness.connected()
        runCurrent()
        assertTrue(viewModel.uiState.value.isConnected)
        assertEquals("", viewModel.uiState.value.apiKey)

        var saves = 0
        viewModel.validateAndSaveApiKey(" separate-key ") { saves++ }
        runCurrent()
        assertEquals(1, saves)
        assertEquals("separate-key", viewModel.uiState.value.apiKey)
        assertTrue(viewModel.uiState.value.isConnected)
        coVerify(exactly = 1) { api.getUser("separate-key") }

        viewModel.validateAndSaveApiKey("") { saves++ }
        runCurrent()
        assertEquals(2, saves)
        assertEquals("", viewModel.uiState.value.apiKey)
        assertTrue(viewModel.uiState.value.isConnected)
        coVerify(exactly = 1) { api.getUser(any()) }

        harness.store.clearAuth()
        runCurrent()
        assertFalse(viewModel.uiState.value.isConnected)
    }

    @Test
    fun `signed in account fills the account plan and requests rows`() = runTest {
        val harness = MdbListTestHarness()
        harness.connected()
        harness.reply(body = """{"user_id":42,"username":"viewer","is_supporter":true,"rate_limit":1000,"rate_limit_remaining":950}""")
        val store = mockk<MDBListSettingsDataStore> {
            every { settings } returns MutableStateFlow(MDBListSettings(apiKey = "ratings-key"))
        }
        val api = mockk<MDBListApi>()
        val traktSettings = mockk<TraktSettingsDataStore>(relaxed = true) {
            every { watchProgressSource } returns MutableStateFlow(WatchProgressSource.NUVIO_SYNC)
        }
        val viewModel = MDBListSettingsViewModel(
            store, api, harness.store,
            mdbListAccountApi = harness.api,
            traktSettingsDataStore = traktSettings,
            trackingSourceController = mockk(relaxed = true)
        )
        runCurrent()

        viewModel.refreshAccount()
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals("viewer", state.username)
        assertEquals(true, state.isSupporter)
        assertEquals(50, state.requestsUsed)
        assertEquals(1000, state.requestsLimit)
        assertEquals(listOf("/user"), harness.engine.requests.map { it.path })
        coVerify(exactly = 0) { api.getUser(any()) }
    }

    @Test
    fun `account reply without an id falls back to the ratings key for plan and requests`() = runTest {
        val harness = MdbListTestHarness()
        harness.connected()
        harness.reply(body = """{"username":"viewer","plan":"supporter","rate_limit":10000,"api_requests_count":12}""")
        val store = mockk<MDBListSettingsDataStore> {
            every { settings } returns MutableStateFlow(MDBListSettings(enabled = true, apiKey = "ratings-key"))
        }
        val api = mockk<MDBListApi> {
            coEvery { getUser("ratings-key") } returns Response.success(
                MDBListUserDto(username = "viewer", plan = "supporter", rateLimit = 10000, apiRequestsCount = 12)
            )
        }
        val traktSettings = mockk<TraktSettingsDataStore>(relaxed = true) {
            every { watchProgressSource } returns MutableStateFlow(WatchProgressSource.NUVIO_SYNC)
        }
        val viewModel = MDBListSettingsViewModel(
            store, api, harness.store,
            mdbListAccountApi = harness.api,
            traktSettingsDataStore = traktSettings,
            trackingSourceController = mockk(relaxed = true)
        )
        runCurrent()

        viewModel.refreshAccount()
        runCurrent()

        val state = viewModel.uiState.value
        assertNull(state.username)
        assertEquals("supporter", state.plan)
        assertEquals(12, state.requestsUsed)
        assertEquals(10000, state.requestsLimit)
    }

    @Test
    fun `ratings key fills only the fields the account reply left out`() = runTest {
        val harness = MdbListTestHarness()
        harness.connected()
        harness.reply(body = """{"user_id":42,"username":"viewer","rate_limit":1000}""")
        val store = mockk<MDBListSettingsDataStore> {
            every { settings } returns MutableStateFlow(MDBListSettings(enabled = true, apiKey = "ratings-key"))
        }
        val api = mockk<MDBListApi> {
            coEvery { getUser("ratings-key") } returns Response.success(
                MDBListUserDto(username = "someone-else", plan = "free", apiRequestsCount = 7)
            )
        }
        val traktSettings = mockk<TraktSettingsDataStore>(relaxed = true) {
            every { watchProgressSource } returns MutableStateFlow(WatchProgressSource.NUVIO_SYNC)
        }
        val viewModel = MDBListSettingsViewModel(
            store, api, harness.store,
            mdbListAccountApi = harness.api,
            traktSettingsDataStore = traktSettings,
            trackingSourceController = mockk(relaxed = true)
        )
        runCurrent()

        viewModel.refreshAccount()
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals("viewer", state.username)
        assertEquals("free", state.plan)
        assertEquals(7, state.requestsUsed)
        assertEquals(1000, state.requestsLimit)
    }
}
