package com.nuvio.tv.ui.screens.plugin

import android.content.Context
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.NewRepoProviders
import com.nuvio.tv.domain.model.PluginRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginTrustWarningTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repo = PluginRepository(id = "r1", name = "Phisher", url = "https://example.org/repo.json")
    private val toReview = MutableStateFlow(setOf("r1"))
    private val manager = mockk<PluginManager>(relaxed = true) {
        every { pluginsEnabled } returns MutableStateFlow(true)
        every { groupStreamsByRepository } returns MutableStateFlow(false)
        every { repositories } returns MutableStateFlow(listOf(repo))
        every { scrapers } returns MutableStateFlow(emptyList())
        every { syncedReposToReview } returns toReview
        every { lastOutcomes } returns MutableStateFlow(emptyMap())
        coEvery { addRepository(any(), any()) } returns Result.success(repo)
    }
    private val profiles = mockk<ProfileManager>(relaxed = true) {
        every { activeProfile } returns null
    }
    private val context = mockk<Context>(relaxed = true) {
        every { getString(any()) } returns ""
        every { getString(any(), *anyVararg()) } returns ""
    }

    @Test
    fun `adding a repo waits for the warning to be confirmed`() = runTest(mainRule.dispatcher) {
        val vm = PluginViewModel(manager, profiles, context)
        vm.onEvent(PluginUiEvent.AddRepository(" https://example.org/repo.json "))
        advanceUntilIdle()
        assertEquals("https://example.org/repo.json", vm.uiState.value.pendingRepoAdd)
        coVerify(exactly = 0) { manager.addRepository(any(), any()) }

        vm.onEvent(PluginUiEvent.ConfirmPendingRepoAdd)
        advanceUntilIdle()
        assertNull(vm.uiState.value.pendingRepoAdd)
        coVerify(exactly = 1) { manager.addRepository("https://example.org/repo.json", NewRepoProviders.OFF_IF_SEVERAL) }
    }

    @Test
    fun `cancelling the warning adds nothing`() = runTest(mainRule.dispatcher) {
        val vm = PluginViewModel(manager, profiles, context)
        vm.onEvent(PluginUiEvent.AddRepository("https://example.org/repo.json"))
        vm.onEvent(PluginUiEvent.DismissPendingRepoAdd)
        advanceUntilIdle()
        assertNull(vm.uiState.value.pendingRepoAdd)
        coVerify(exactly = 0) { manager.addRepository(any(), any()) }
    }

    @Test
    fun `repos that arrived by sync are listed until acknowledged`() = runTest(mainRule.dispatcher) {
        val vm = PluginViewModel(manager, profiles, context)
        advanceUntilIdle()
        assertEquals(listOf("r1"), vm.uiState.value.syncedReposToReview.map { it.id })

        vm.onEvent(PluginUiEvent.AcknowledgeSyncedRepos)
        advanceUntilIdle()
        coVerify(exactly = 1) { manager.acknowledgeSyncedRepos() }
        toReview.value = emptySet()
        advanceUntilIdle()
        assertEquals(emptyList<PluginRepository>(), vm.uiState.value.syncedReposToReview)
    }
}
