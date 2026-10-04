package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.CatalogRow
import kotlinx.coroutines.flow.Flow

interface CatalogRepository {
    fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int = 0,
        skipStep: Int = 100,
        extraArgs: Map<String, String> = emptyMap(),
        supportsSkip: Boolean = false,
        posterScreen: com.nuvio.tv.core.poster.CustomPosterScreen = com.nuvio.tv.core.poster.CustomPosterScreen.HOME
    ): Flow<NetworkResult<CatalogRow>>

    /**
     * Drops every catalogue cache layer this repository owns: the in-memory
     * freshness LRU and the persistent first-paint disk cache (map plus
     * backing file). Used by the Settings clear-cache action; subsequent
     * catalogue reads go to the network.
     */
    suspend fun clearCaches()
}
