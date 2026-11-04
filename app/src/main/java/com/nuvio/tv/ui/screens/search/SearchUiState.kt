package com.nuvio.tv.ui.screens.search

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.MetaPreview

internal const val MIN_SEARCH_QUERY_LENGTH = 2

internal fun submittedSearchQuery(rawQuery: String): String =
    rawQuery.trim().takeIf { it.length >= MIN_SEARCH_QUERY_LENGTH }.orEmpty()

internal fun shouldShowDiscoverInSearch(
    discoverLocation: DiscoverLocation,
    query: String,
    submittedQuery: String
): Boolean =
    discoverLocation == DiscoverLocation.IN_SEARCH &&
        query.trim().length < MIN_SEARCH_QUERY_LENGTH &&
        submittedQuery.isBlank()

@Immutable
data class SearchUiState(
    val query: String = "",
    val submittedQuery: String = "",
    val isSearching: Boolean = false,
    /** The submitted query whose rows are ready to move into, before every catalog has answered. */
    val focusReadyQuery: String = "",
    /** The first row of [focusReadyQuery] that holds its own results, where focus lands. */
    val focusTargetRowKey: String? = null,
    /** Rows still showing the previous query's results while this one loads. */
    val updatingRowKeys: Set<String> = emptySet(),
    val error: String? = null,
    val catalogRows: List<CatalogRow> = emptyList(),
    val installedAddons: List<Addon> = emptyList(),
    val discoverLocation: DiscoverLocation = DiscoverLocation.IN_SEARCH,
    val discoverInitialized: Boolean = false,
    val discoverLoading: Boolean = false,
    val discoverLoadingMore: Boolean = false,
    val discoverCatalogs: List<DiscoverCatalog> = emptyList(),
    val selectedDiscoverType: String = "movie",
    val selectedDiscoverCatalogKey: String? = null,
    val selectedDiscoverGenre: String? = null,
    val selectedDiscoverLetter: String? = null,
    val discoverUnwatchedOnly: Boolean = false,
    val discoverResults: List<MetaPreview> = emptyList(),
    val pendingDiscoverResults: List<MetaPreview> = emptyList(),
    val discoverHasMore: Boolean = true,
    val discoverPage: Int = 1,
    val posterLabelsEnabled: Boolean = true,
    val catalogAddonNameEnabled: Boolean = true,
    val catalogTypeSuffixEnabled: Boolean = true,
    val posterCardWidthDp: Int = 126,
    val posterCardHeightDp: Int = 189,
    val posterCardCornerRadiusDp: Int = 12,
    val recentSearches: List<String> = emptyList(),
    val suggestions: List<String> = emptyList()
)

@Immutable
data class DiscoverCatalog(
    val key: String,
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val catalogId: String,
    val catalogName: String,
    val type: String,
    val genres: List<String>,
    val supportsSkip: Boolean,
    val skipStep: Int,
    val letters: List<String> = emptyList(),
    val offersUnwatched: Boolean = false
)
