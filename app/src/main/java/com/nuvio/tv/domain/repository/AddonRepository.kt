package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import kotlinx.coroutines.flow.Flow

interface AddonRepository {
    fun getInstalledAddons(): Flow<List<Addon>>
    suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon>
    suspend fun addAddon(url: String)
    suspend fun removeAddon(url: String)
    suspend fun setAddonOrder(urls: List<String>)
    suspend fun setAddonEnabled(url: String, enabled: Boolean)

    /**
     * Force a manifest re-fetch of every installed, enabled addon, bypassing
     * the in-memory cache. Heals addons that dropped out of the installed list
     * because their manifest fetch failed. Default no-op so test doubles and
     * any other implementors are unaffected.
     */
    suspend fun refreshAllManifests() {}
}
