package com.nuvio.tv.ui.screens.addon

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.data.local.*
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogOrderViewModelTest {
    @Before fun setup() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun cleanup() = Dispatchers.resetMain()

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    @Test fun `reordering and enable controls persist without crossing profile boundaries`() = runTest {
        val active = MutableStateFlow(1)
        val manager = mockk<ProfileManager> {
            every { activeProfileId } returns active
            every { profiles } returns MutableStateFlow(listOf(
                UserProfile(id = 1, name = "Primary", avatarColorHex = "#112233"),
                UserProfile(id = 2, name = "Independent", avatarColorHex = "#334455", usesPrimaryAddons = false)
            ))
        }
        val stores = mapOf(1 to MemoryStore(), 2 to MemoryStore())
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), "layout_settings") } answers { stores.getValue(firstArg()) }
        val layout = LayoutPreferenceDataStore(factory, manager)
        val addon = Addon("a", "Addon", version = "1", description = null, logo = null,
            baseUrl = "https://example.test", catalogs = listOf(
                CatalogDescriptor(ContentType.MOVIE, id = "one", name = "One"),
                CatalogDescriptor(ContentType.MOVIE, id = "two", name = "Two"),
                CatalogDescriptor(ContentType.MOVIE, id = "three", name = "Three")
            ), types = listOf(ContentType.MOVIE), resources = emptyList())
        val addons = mockk<AddonRepository> { every { getInstalledAddons() } returns flowOf(listOf(addon)) }
        val collections = mockk<CollectionsDataStore> { every { this@mockk.collections } returns flowOf(emptyList()) }
        val sync = mockk<HomeCatalogSettingsSyncService>(relaxed = true)
        val vm = CatalogOrderViewModel(addons, collections, layout, sync, mockk { every { this@mockk.addons } returns flowOf(emptyList()) })
        try {
            advanceUntilIdle()
            val initial = vm.uiState.value.items
            assertEquals(listOf("One", "Two", "Three"), initial.map { it.catalogName })
            vm.moveUp(initial.first().key) // Boundary is a no-op.
            advanceUntilIdle()
            assertTrue(layout.homeCatalogOrderKeys.first().isEmpty())
            vm.moveUp(initial[1].key)
            advanceUntilIdle()
            assertEquals(listOf(initial[1].key, initial[0].key, initial[2].key), layout.homeCatalogOrderKeys.first())
            vm.toggleCatalogEnabled(initial[1].disableKey)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.items.first().isDisabled)
            active.value = 2
            advanceUntilIdle()
            assertEquals(initial.map { it.key }, vm.uiState.value.items.map { it.key })
            assertFalse(vm.uiState.value.items.any { it.isDisabled })
            active.value = 1
            advanceUntilIdle()
            assertEquals(initial[1].key, vm.uiState.value.items.first().key)
            assertTrue(vm.uiState.value.items.first().isDisabled)
            vm.toggleFollowAddonsOrder(true)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.items.any { it.canMoveUp || it.canMoveDown })
        } finally { vm.viewModelScope.cancel() }
    }
}
