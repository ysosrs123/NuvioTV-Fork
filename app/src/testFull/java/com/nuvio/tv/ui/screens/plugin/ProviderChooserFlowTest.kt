package com.nuvio.tv.ui.screens.plugin

import android.content.Context
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.NewRepoProviders
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.RepositoryType
import com.nuvio.tv.domain.model.ScraperInfo
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
class ProviderChooserFlowTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val existing = PluginRepository(id = "old", name = "CSX", url = "https://example.org/csx.json")
    private val added = PluginRepository(id = "new", name = "Phisher", url = "https://example.org/phisher.json", scraperCount = 3)
    private val repos = MutableStateFlow(listOf(existing))
    private val scrapers = MutableStateFlow(emptyList<ScraperInfo>())

    private fun provider(id: String) = ScraperInfo(
        id = id, name = id, description = "", version = "1", filename = "$id.cs3",
        supportedTypes = listOf("movie"), enabled = true, manifestEnabled = true, logo = null,
        contentLanguage = emptyList(), repositoryId = "new", formats = null, type = RepositoryType.EXTERNAL_DEX
    )

    private val manager = mockk<PluginManager>(relaxed = true) {
        every { pluginsEnabled } returns MutableStateFlow(true)
        every { groupStreamsByRepository } returns MutableStateFlow(false)
        every { repositories } returns repos
        every { scrapers } returns this@ProviderChooserFlowTest.scrapers
        every { syncedReposToReview } returns MutableStateFlow(emptySet())
        every { lastOutcomes } returns MutableStateFlow(emptyMap())
        coEvery { addRepository("https://example.org/phisher.json", NewRepoProviders.OFF_IF_SEVERAL) } answers {
            repos.value = repos.value + added
            this@ProviderChooserFlowTest.scrapers.value = listOf(provider("a"), provider("b"), provider("c"))
            Result.success(added)
        }
        coEvery { addRepository("https://example.org/csx.json", NewRepoProviders.OFF_IF_SEVERAL) } returns Result.success(existing)
    }
    private val profiles = mockk<ProfileManager>(relaxed = true) { every { activeProfile } returns null }
    private val context = mockk<Context>(relaxed = true) {
        every { getString(any()) } returns ""
        every { getString(any(), *anyVararg()) } returns ""
    }

    private suspend fun kotlinx.coroutines.test.TestScope.add(vm: PluginViewModel, url: String) {
        vm.onEvent(PluginUiEvent.AddRepository(url))
        vm.onEvent(PluginUiEvent.ConfirmPendingRepoAdd)
        advanceUntilIdle()
    }

    @Test
    fun `a new repo opens the chooser and leaving it keeps everything off`() = runTest(mainRule.dispatcher) {
        val vm = PluginViewModel(manager, profiles, context)
        add(vm, "https://example.org/phisher.json")
        val chooser = vm.uiState.value.providerChooser!!
        assertEquals(setOf<String>(), chooser.selected)
        assertEquals(3, chooser.providers.size)

        vm.onEvent(PluginUiEvent.ChooserDismiss)
        advanceUntilIdle()
        assertNull(vm.uiState.value.providerChooser)
        coVerify(exactly = 1) { manager.setProvidersForRepo("new", emptySet()) }
    }

    @Test
    fun `backing out of a new repo with providers ticked keeps them off`() = runTest(mainRule.dispatcher) {
        val vm = PluginViewModel(manager, profiles, context)
        add(vm, "https://example.org/phisher.json")
        vm.onEvent(PluginUiEvent.ChooserToggle("a"))
        vm.onEvent(PluginUiEvent.ChooserToggle("b"))
        vm.onEvent(PluginUiEvent.ChooserDismiss)
        advanceUntilIdle()
        assertNull(vm.uiState.value.providerChooser)
        coVerify(exactly = 1) { manager.setProvidersForRepo("new", emptySet()) }
        coVerify(exactly = 0) { manager.setProvidersForRepo("new", match { it.isNotEmpty() }) }
    }

    @Test
    fun `turning on the ticked providers saves only those`() = runTest(mainRule.dispatcher) {
        val vm = PluginViewModel(manager, profiles, context)
        add(vm, "https://example.org/phisher.json")
        vm.onEvent(PluginUiEvent.ChooserToggle("a"))
        vm.onEvent(PluginUiEvent.ChooserToggle("c"))
        vm.onEvent(PluginUiEvent.ChooserApply)
        advanceUntilIdle()
        coVerify(exactly = 1) { manager.setProvidersForRepo("new", setOf("a", "c")) }
    }

    @Test
    fun `adding a repo you already have does not open the chooser`() = runTest(mainRule.dispatcher) {
        val vm = PluginViewModel(manager, profiles, context)
        add(vm, "https://example.org/csx.json")
        assertNull(vm.uiState.value.providerChooser)
    }

    @Test
    fun `choosing providers clears that repo from the account notice`() = runTest(mainRule.dispatcher) {
        repos.value = listOf(existing, added)
        scrapers.value = listOf(provider("a").copy(enabled = false), provider("b").copy(enabled = false))
        val vm = PluginViewModel(manager, profiles, context)
        vm.onEvent(PluginUiEvent.OpenProviderChooser("new"))
        advanceUntilIdle()
        vm.onEvent(PluginUiEvent.ChooserToggle("a"))
        vm.onEvent(PluginUiEvent.ChooserApply)
        advanceUntilIdle()
        coVerify(exactly = 1) { manager.setProvidersForRepo("new", setOf("a")) }
        coVerify(exactly = 1) { manager.acknowledgeSyncedRepo("new") }
    }

    @Test
    fun `backing out from the account notice leaves it showing`() = runTest(mainRule.dispatcher) {
        repos.value = listOf(existing, added)
        scrapers.value = listOf(provider("a").copy(enabled = false), provider("b").copy(enabled = false))
        val vm = PluginViewModel(manager, profiles, context)
        vm.onEvent(PluginUiEvent.OpenProviderChooser("new"))
        advanceUntilIdle()
        vm.onEvent(PluginUiEvent.ChooserDismiss)
        advanceUntilIdle()
        coVerify(exactly = 0) { manager.acknowledgeSyncedRepo(any()) }
    }

    @Test
    fun `reopening from the repo card and backing out changes nothing`() = runTest(mainRule.dispatcher) {
        repos.value = listOf(existing, added)
        scrapers.value = listOf(provider("a"), provider("b").copy(enabled = false))
        val vm = PluginViewModel(manager, profiles, context)
        vm.onEvent(PluginUiEvent.OpenProviderChooser("new"))
        advanceUntilIdle()
        assertEquals(setOf("a"), vm.uiState.value.providerChooser!!.selected)
        vm.onEvent(PluginUiEvent.ChooserDismiss)
        advanceUntilIdle()
        coVerify(exactly = 0) { manager.setProvidersForRepo(any(), any()) }
    }
}
