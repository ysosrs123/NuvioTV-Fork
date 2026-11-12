package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.util.StartupLatencyTrace

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.catalogRowStableKey
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.legacyKey
import com.nuvio.tv.domain.model.mergeCatalogPage
import com.nuvio.tv.domain.model.nextCatalogSkip
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.stableKey
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.supportsExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import com.nuvio.tv.core.util.filterReleasedItems
import kotlinx.coroutines.withContext
import java.time.LocalDate

private data class CatalogUpdateResult(
    val displayRows: List<CatalogRow>,
    val heroItems: List<com.nuvio.tv.domain.model.MetaPreview>,
    val gridItems: List<GridItem>,
    val fullRows: List<CatalogRow>,
    val statusRows: List<CatalogRow>,
    val heldWatchedRowKey: String?
)

@OptIn(FlowPreview::class)
internal fun HomeViewModel.observeCollectionsPipeline() {
    viewModelScope.launch {
        combine(collectionsDataStore.collections, builtInCatalogs.collections) { saved, builtIn -> saved + builtIn }
            .distinctUntilChanged()
            .debounce(300)
            .collectLatest { collections ->
                // Deduplicate by collection ID (keep last occurrence) to prevent
                // duplicate LazyColumn keys when users import overlapping collections.
                collectionsCache = collections.associateBy { it.id }.values.toList()
                rebuildCatalogOrder(addonsCache)
                scheduleUpdateCatalogRows()
            }
    }
}

internal fun HomeViewModel.loadHomeCatalogOrderPreferencePipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.homeCatalogOrderKeys.collectLatest { keys ->
            homeCatalogOrderKeys = keys
            rebuildCatalogOrder(addonsCache)
            scheduleUpdateCatalogRows()
        }
    }
}

internal fun HomeViewModel.loadFollowAddonsOrderPipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.followAddonsOrder.collectLatest { enabled ->
            followAddonsOrderEnabled = enabled
            rebuildCatalogOrder(addonsCache)
            scheduleUpdateCatalogRows()
        }
    }
}

internal fun HomeViewModel.loadDisabledHomeCatalogPreferencePipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.disabledHomeCatalogKeys.collectLatest { keys ->
            val newKeys = keys.toSet()
            if (newKeys == disabledHomeCatalogKeys) return@collectLatest
            disabledHomeCatalogKeys = newKeys
            rebuildCatalogOrder(addonsCache)
            if (addonsCache.isNotEmpty()) {
                loadAllCatalogsPipeline(addonsCache)
            } else {
                scheduleUpdateCatalogRows()
            }
        }
    }
}

internal fun HomeViewModel.loadCustomCatalogTitlesPipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.customCatalogTitles.collectLatest { titles ->
            customCatalogTitles = titles
            scheduleUpdateCatalogRows()
        }
    }
}

internal fun HomeViewModel.observeTmdbSettingsPipeline() {
    viewModelScope.launch {
        tmdbSettingsDataStore.settings
            .collectLatest { settings ->
                val languageChanged = currentTmdbSettings.language != settings.language
                currentTmdbSettings = settings
                val tmdbEnabledForLayout = settings.enabled &&
                    (_uiState.value.homeLayout != HomeLayout.MODERN || settings.modernHomeEnabled)
                val enrichEnabled = tmdbEnabledForLayout || externalMetaPrefetchEnabled
                _uiState.update { it.copy(heroEnrichmentEnabled = enrichEnabled) }
                if (languageChanged) {
                    // Allow re-enrichment with the updated TMDB metadata selection on next focus.
                    prefetchedTmdbIds.clear()
                    prefetchedExternalMetaIds.clear()
                    _enrichedPreviews.value = emptyMap()
                    _lastEnrichedPreview.value = null
                    clearEnrichmentFailures()
                }
                scheduleUpdateCatalogRows()
            }
    }
}

@OptIn(FlowPreview::class)
internal fun HomeViewModel.observeInstalledAddonsPipeline() {
    viewModelScope.launch {
        combine(addonRepository.getInstalledAddons(), serverCatalog.addons, builtInCatalogs.addons) { installed, servers, builtIn ->
            val hidden = layoutPreferenceDataStore.hideNewCatalogsByDefault(
                ServerCatalog.hiddenByDefaultKeys(servers) + builtInCatalogs.hiddenByDefaultKeys(builtIn)
            )
            if (hidden.isNotEmpty()) disabledHomeCatalogKeys = disabledHomeCatalogKeys + hidden
            installed.enabledAddons() + servers + builtIn
        }
            .distinctUntilChanged()
            .collectLatest { addons ->
                addonsCache = addons
                loadAllCatalogsPipeline(addons)
            }
    }
}

internal suspend fun HomeViewModel.loadAllCatalogsPipeline(
    addons: List<Addon>,
    forceReload: Boolean = false
) {
    val signature = buildHomeCatalogLoadSignature(addons)
    val hasActiveLoads = synchronized(activeCatalogLoadJobs) { activeCatalogLoadJobs.any { it.isActive } }
    if (!forceReload &&
        signature == activeCatalogLoadSignature &&
        (hasActiveLoads || hasAnyCatalogRows())
    ) {
        return
    }

    activeCatalogLoadSignature = signature
    watchedTitles.refillPages.clear()
    StartupLatencyTrace.mark("catalogs_load_start")
    catalogsLoadInProgress = true
    // A full load leaves every catalog fresh, so the next return to Home has nothing to do.
    lastHomeCatalogRefreshAtMs = android.os.SystemClock.elapsedRealtime()
    catalogLoadGeneration += 1
    val generation = catalogLoadGeneration
    cancelInFlightCatalogLoads()
    failedCatalogRetryJob?.cancel()
    failedCatalogKeys.clear()

    // On reload (not first load), keep existing UI data visible while new
    // catalogs load in the background to avoid a flash of empty content.
    val isReload = _uiState.value.catalogRows.isNotEmpty() || _uiState.value.homeRows.isNotEmpty()
    if (!isReload) {
        _uiState.update { it.copy(isLoading = true, error = null, installedAddonsCount = addons.size) }
        synchronized(catalogStateLock) {
            catalogOrder.clear()
        }
        clearCatalogData()
    } else {
        _uiState.update { it.copy(error = null, installedAddonsCount = addons.size) }
    }
    posterStatusReconcileJob?.cancel()
    reconcilePosterStatusObserversPipeline(emptyList())
    _fullCatalogRows.value = emptyList()
    hasRenderedFirstCatalog = false
    trailerPreviewJob?.cancel()
    trailerPreviewLoadingIds.clear()
    trailerPreviewNegativeCache.clear()
    trailerPreviewUrlsState.clear()
    trailerPreviewAudioUrlsState.clear()
    trailerPreviewValidUntil.clear()
    activeTrailerPreviewItemId = null
    trailerPreviewRequestVersion++
    prefetchedExternalMetaIds.clear()
    externalMetaPrefetchInFlightIds.clear()
    externalMetaPrefetchJob?.cancel()
    pendingExternalMetaPrefetchItemId = null
    prefetchedTmdbIds.clear()
    tmdbEnrichFocusJob?.cancel()
    pendingTmdbEnrichItemId = null
    clearEnrichmentFailures()
    lastHeroEnrichmentSignature = null
    lastHeroEnrichedItems = emptyList()
    heroItemOrder = emptyList()

    try {
        if (addons.isEmpty()) {
            catalogsLoadInProgress = false
            _uiState.update { it.copy(isLoading = false, error = appContext.getString(R.string.home_empty_no_sources_title)) }
            return
        }

        rebuildCatalogOrder(addons)

        // Hero has its own catalog sources (heroCatalogKeys) configured
        // independently in Layout Settings.  When the user has explicitly
        // selected hero catalogs, load those even if they are disabled from
        // home rows.  When no hero catalogs are selected, the hero simply
        // piggybacks on whatever home catalogs are loaded — if none are
        // loaded, the hero has no data and won't render.
        val heroCatalogSet = currentHeroCatalogKeys.toSet()
        val hasHeroSelections = heroCatalogSet.isNotEmpty()

        if (isCatalogOrderEmpty() && !hasHeroSelections) {
            catalogsLoadInProgress = false
            _uiState.update { it.copy(isLoading = false, error = appContext.getString(R.string.home_error_no_catalog_addons)) }
            return
        }

        val catalogsToLoad = addons.flatMap { addon ->
            addon.catalogs
                .filterNot {
                    !it.shouldShowOnHome() || isCatalogDisabled(
                        addonBaseUrl = addon.baseUrl,
                        addonId = addon.id,
                        type = it.apiType,
                        catalogId = it.id,
                        catalogName = it.name
                    )
                }
                .map { catalog -> addon to catalog }
        }

        // Load hero-selected catalogs even if disabled from home rows —
        // the hero has its own catalog source independent of home rows.
        val alreadyLoadingKeys = catalogsToLoad.map { (addon, catalog) ->
            catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)
        }.toSet()
        val heroOnlyCatalogs = if (hasHeroSelections) {
            addons.flatMap { addon ->
                addon.catalogs
                    .filter { catalog ->
                        val key = catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)
                        key in heroCatalogSet && key !in alreadyLoadingKeys && !catalog.isSearchOnlyCatalog()
                    }
                    .map { catalog -> addon to catalog }
            }
        } else {
            emptyList()
        }

        val allCatalogsToLoad = catalogsToLoad + heroOnlyCatalogs
        if (allCatalogsToLoad.isEmpty()) {
            // No home catalogs and no hero catalogs to load —
            // but collections may still exist to render.
            catalogsLoadInProgress = false
            if (hasCatalogOrderEntries()) {
                scheduleUpdateCatalogRows()
            } else {
                _uiState.update { it.copy(isLoading = false, error = appContext.getString(R.string.home_error_no_catalog_addons)) }
            }
            return
        }

        // ── Lazy loading: split into eager and deferred ──
        val heroOnlyKeys = heroOnlyCatalogs.map { (addon, catalog) ->
            catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)
        }.toSet()

        // Build display title helper (respects custom titles)
        val titlesSnapshot = customCatalogTitles
        val showTypeSuffix = _uiState.value.catalogTypeSuffixEnabled
        val strTypeMovie = appContext.getString(R.string.type_movie)
        val strTypeSeries = appContext.getString(R.string.type_series)
        fun displayTitle(addon: Addon, catalog: CatalogDescriptor): String {
            val key = catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)
            val custom = titlesSnapshot[key]
            val baseName = if (!custom.isNullOrBlank()) custom else catalog.name
            val catalogName = baseName.replaceFirstChar { it.uppercase() }
            if (!showTypeSuffix || ServerCatalog.isServerAddonId(addon.id) || builtInCatalogs.skipsTypeSuffix(addon.id)) return catalogName
            val typeLabel = when (catalog.apiType.lowercase()) {
                "movie" -> strTypeMovie.ifBlank { catalog.apiType.replaceFirstChar { it.uppercase() } }
                "series" -> strTypeSeries.ifBlank { catalog.apiType.replaceFirstChar { it.uppercase() } }
                else -> catalog.apiType.replaceFirstChar { it.uppercase() }
            }
            return "$catalogName - $typeLabel"
        }

        // Determine which home catalogs to load eagerly vs lazily.
        // Grid layout loads all catalogs eagerly since it doesn't support
        // placeholder shimmer rows — all content must be available upfront.
        // Wait for layout preferences if not yet ready, to avoid wrong eager/lazy split.
        if (!_uiState.value.layoutPreferencesReady) {
            _uiState.first { it.layoutPreferencesReady }
        }
        val isGridLayout = _uiState.value.homeLayout == HomeLayout.GRID
        // Eager set follows DISPLAY order (catalogOrder), not addon-manifest order,
        // so the eagerly-loaded rows are the ones shown at the top of the home
        // screen -- stable when the user reorders home rows. catalogOrder was
        // rebuilt at rebuildCatalogOrder(addons) above; collection_* keys are not
        // network catalogues and are skipped.
        val eagerHomeCatalogs: List<Pair<Addon, CatalogDescriptor>>
        val lazyHomeCatalogs: List<Pair<Addon, CatalogDescriptor>>
        if (isGridLayout) {
            eagerHomeCatalogs = catalogsToLoad
            lazyHomeCatalogs = emptyList()
        } else {
            val catalogByKey = catalogsToLoad.associateBy { (addon, catalog) ->
                catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)
            }
            val displayOrderKeys = snapshotCatalogState().first.filterNot { it.startsWith("collection_") }
            val displayOrderKeySet = displayOrderKeys.toSet()
            val displayOrdered = displayOrderKeys.mapNotNull { catalogByKey[it] }
            val remainder = catalogsToLoad.filterNot { (addon, catalog) ->
                catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id) in displayOrderKeySet
            }
            val ordered = displayOrdered + remainder
            eagerHomeCatalogs = ordered.take(eagerCatalogLoadCount)
            lazyHomeCatalogs = ordered.drop(eagerCatalogLoadCount)
        }

        // Build placeholder descriptors for lazy catalogs
        synchronized(catalogStateLock) {
            pendingLazyCatalogs.clear()
            placeholderDescriptors.clear()
        }
        lazyLoadRequestedKeys.clear()

        (eagerHomeCatalogs + lazyHomeCatalogs).forEach { (addon, catalog) ->
            val key = catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)
            synchronized(catalogStateLock) {
                placeholderDescriptors.add(
                    HomeViewModel.PlaceholderDescriptor(
                        catalogKey = key,
                        addonId = addon.id,
                        addonName = addon.displayName,
                        addonBaseUrl = addon.baseUrl,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        apiType = catalog.apiType,
                        displayTitle = displayTitle(addon, catalog)
                    )
                )
            }
        }

        lazyHomeCatalogs.forEach { (addon, catalog) ->
            val key = catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)
            synchronized(catalogStateLock) {
                pendingLazyCatalogs[key] = addon to catalog
            }
        }

        Log.d(HomeViewModel.TAG,
            "Lazy loading: eager=${eagerHomeCatalogs.size} lazy=${lazyHomeCatalogs.size}"
        )

        val eagerCatalogs = eagerHomeCatalogs + heroOnlyCatalogs
        pendingCatalogLoads = eagerCatalogs.size
        eagerCatalogs.forEach { (addon, catalog) ->
            loadCatalogPipeline(addon, catalog, generation)
        }

        // Kick the reserved-headroom background sweep so the remaining
        // lazy catalogues fill in on their own, without waiting for scroll.
        startReservedHeadroomSweep(generation)

        // Immediately schedule an update so placeholder rows appear in the UI
        // while catalogs are still loading.
        scheduleUpdateCatalogRows()

        // Safety flush: if catalogs trickle in slowly (e.g., slow addons),
        // ensure the user sees whatever content is available within a
        // reasonable window, even if not all catalogs have completed yet.
        if (eagerCatalogs.size > 1) {
            viewModelScope.launch {
                delay(800L)
                if (pendingCatalogLoads > 0 && hasAnyCatalogRows()) {
                    Log.d(HomeViewModel.TAG, "Safety flush: pending=$pendingCatalogLoads — forcing UI update")
                    scheduleUpdateCatalogRows()
                }
            }
        }
    } catch (e: Exception) {
        catalogsLoadInProgress = false
        _uiState.update { it.copy(isLoading = false, error = e.message) }
    }
}

/**
 * Additively loads hero-selected catalogs that are not already in [catalogsMap].
 * Unlike [loadAllCatalogsPipeline] this does NOT clear existing state — it only
 * fills in missing hero catalog data so the hero section can render.
 *
 * Called from the presentation pipeline when [currentHeroCatalogKeys] arrives
 * after the initial catalog load (due to the layout preference debounce).
 */
internal fun HomeViewModel.loadHeroCatalogsPipeline() {
    val heroCatalogKeys = currentHeroCatalogKeys
    if (heroCatalogKeys.isEmpty() || addonsCache.isEmpty()) return

    val heroCatalogSet = heroCatalogKeys.toSet()
    val alreadyLoadedKeys = snapshotCatalogKeys()
    val missingHeroKeys = heroCatalogSet - alreadyLoadedKeys
    if (missingHeroKeys.isEmpty()) {
        // All hero catalogs already loaded — just refresh presentation
        scheduleUpdateCatalogRows()
        return
    }

    val heroToLoad = addonsCache.flatMap { addon ->
        addon.catalogs
            .filter { catalog ->
                val key = catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)
                key in missingHeroKeys && !catalog.isSearchOnlyCatalog()
            }
            .map { catalog -> addon to catalog }
    }

    if (heroToLoad.isEmpty()) {
        scheduleUpdateCatalogRows()
        return
    }

    val generation = catalogLoadGeneration
    pendingCatalogLoads += heroToLoad.size
    heroToLoad.forEach { (addon, catalog) ->
        loadCatalogPipeline(addon, catalog, generation)
    }
}

internal fun HomeViewModel.loadCatalogPipeline(
    addon: Addon,
    catalog: CatalogDescriptor,
    generation: Long,
    /** True only for the ON_RESUME refresh, where a row already on screen must be merged into. */
    isRefresh: Boolean = false,
    requestedByUser: Boolean = false,
    forceReplace: Boolean = false
): Job {
    val loadJob = viewModelScope.launch {
        var hasCountedCompletion = false
        catalogLoadSemaphore.withPermit {
            if (generation != catalogLoadGeneration) return@withPermit
            val supportsSkip = catalog.supportsExtra("skip")
            val skipStep = catalog.skipStep()
            Log.d(
                HomeViewModel.TAG,
                "Loading home catalog addonId=${addon.id} addonName=${addon.name} type=${catalog.apiType} catalogId=${catalog.id} catalogName=${catalog.name} supportsSkip=$supportsSkip skipStep=$skipStep"
            )
            catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalog.id,
                catalogName = catalog.name,
                type = catalog.apiType,
                skip = 0,
                skipStep = skipStep,
                supportsSkip = supportsSkip
            ).flowOn(serverArtworkContext()).collect { result ->
                if (generation != catalogLoadGeneration) return@collect
                when (result) {
                    is NetworkResult.Success -> {
                        val key = catalogKey(
                            addonId = addon.id,
                            type = catalog.apiType,
                            catalogId = catalog.id
                        )
                        if (!isRefresh || !mergeRefreshedCatalogRow(key, result.data, requestedByUser, forceReplace)) {
                            replaceCatalogRow(key, result.data)
                            watchedTitles.refillPages.remove(key)
                        }
                        failedCatalogKeys.remove(key)
                        // Trigger MDBList batch for newly loaded catalog data.
                        onCatalogRowItemsChanged(key)
                        // Remove placeholder descriptor now that real data is available
                        synchronized(catalogStateLock) {
                            placeholderDescriptors.removeAll { it.catalogKey == key }
                        }
                        if (!hasCountedCompletion) {
                            pendingCatalogLoads = (pendingCatalogLoads - 1).coerceAtLeast(0)
                            hasCountedCompletion = true
                        }
                        Log.d(
                            HomeViewModel.TAG,
                            "Home catalog loaded addonId=${addon.id} type=${catalog.apiType} catalogId=${catalog.id} items=${result.data.items.size} pending=$pendingCatalogLoads"
                        )
                        if (pendingCatalogLoads == 0) {
                            catalogsLoadInProgress = false
                            StartupLatencyTrace.mark("catalogs_load_end")
                        }
                        // The existing debounce coalesces nearby completions. Withholding
                        // this signal until pending==0 strands ready rows behind a slow
                        // add-on, including rows explicitly requested while scrolling.
                        scheduleUpdateCatalogRows()
                    }
                    is NetworkResult.Error -> {
                        val errorKey = catalogKey(
                            addonId = addon.id,
                            type = catalog.apiType,
                            catalogId = catalog.id
                        )
                        // Remove placeholder on error so it doesn't show forever
                        synchronized(catalogStateLock) {
                            placeholderDescriptors.removeAll { it.catalogKey == errorKey }
                        }
                        // One timed retry per failed row, later ones only with a refresh.
                        if (readCatalogRow(errorKey) == null && failedCatalogKeys.add(errorKey)) {
                            scheduleFailedCatalogRetry()
                        }
                        if (!hasCountedCompletion) {
                            pendingCatalogLoads = (pendingCatalogLoads - 1).coerceAtLeast(0)
                            hasCountedCompletion = true
                        }
                        Log.w(
                            HomeViewModel.TAG,
                            "Home catalog failed addonId=${addon.id} type=${catalog.apiType} catalogId=${catalog.id} code=${result.code} message=${result.message}"
                        )
                        if (pendingCatalogLoads == 0) {
                            catalogsLoadInProgress = false
                            StartupLatencyTrace.mark("catalogs_load_end")
                        }
                        scheduleUpdateCatalogRows()
                    }
                    NetworkResult.Loading -> {
                        /* Handled by individual row */
                    }
                }
            }
        }
    }
    registerCatalogLoadJob(loadJob)
    return loadJob
}

internal fun HomeViewModel.loadMoreCatalogItemsPipeline(catalogId: String, addonId: String, type: String) {
    val key = catalogKey(addonId = addonId, type = type, catalogId = catalogId)
    val currentRow = readCatalogRow(key)

    if (currentRow == null) {
        return
    }

    if (currentRow.isLoading || !currentRow.hasMore) {
        return
    }
    if (key in _loadingCatalogs.value) {
        return
    }

    updateCatalogRow(key) { it.copy(isLoading = true) }
    _loadingCatalogs.update { it + key }

    viewModelScope.launch {
        val addon = addonsCache.find { it.id == addonId }
        if (addon == null) {
            return@launch
        }

        val nextSkip = currentRow.nextCatalogSkip()
        catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalogId,
            catalogName = currentRow.catalogName,
            type = currentRow.apiType,
            skip = nextSkip,
            skipStep = currentRow.skipStep,
            supportsSkip = currentRow.supportsSkip
        ).flowOn(serverArtworkContext()).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    updateCatalogRow(key) { latestRow ->
                        val mergedRow = latestRow.mergeCatalogPage(result.data)
                        mergedRow
                    }
                    _loadingCatalogs.update { it - key }
                    scheduleUpdateCatalogRows()
                    onCatalogRowItemsChanged(key)
                }
                is NetworkResult.Error -> {
                    updateCatalogRow(key) { it.copy(isLoading = false) }
                    _loadingCatalogs.update { it - key }
                    scheduleUpdateCatalogRows()
                }
                NetworkResult.Loading -> { }
            }
        }
    }
}

internal suspend fun HomeViewModel.updateCatalogRowsPipeline() {
    val watchedFilter = awaitWatchedFilterPipeline()
    val watchedIds = watchedFilter?.ids?.takeIf { watchedFilter.hides }
    val listedFilter = watchedTitles.listed.value?.takeIf { it.hides }
    val watchedFocusRowKey = if (watchedTitles.homeShown) liveFocusedRowKey else null
    val shownRowsSnapshot = _uiState.value.catalogRows
    val posterProfileId = profileManager.activeProfileId.value
    val (orderedKeys, catalogSnapshot) = snapshotCatalogState()
    val collectionsSnapshot = collectionsCache.associateBy { "collection_${it.id}" }
    val heroCatalogKeys = currentHeroCatalogKeys
    val currentLayout = _uiState.value.homeLayout
    val currentGridItems = _uiState.value.gridItems
    val heroSectionEnabled = _uiState.value.heroSectionEnabled
    val hideUnreleased = _uiState.value.hideUnreleasedContent
    val titlesSnapshot = customCatalogTitles

    val catalogUpdate = withContext(Dispatchers.Default) {
        val rawRows = orderedKeys.mapNotNull { key ->
            val row = catalogSnapshot[key] ?: return@mapNotNull null
            val custom = titlesSnapshot[key]
            if (!custom.isNullOrBlank()) row.copy(catalogName = custom) else row
        }
        val orderedRows = if (hideUnreleased) {
            val today = LocalDate.now()
            rawRows.map {
                ensureActive()
                it.filterReleasedItems(today)
            }
        } else {
            rawRows
        }
        val withoutWatchedRows = if (watchedIds != null) {
            orderedRows.withoutWatched(watchedIds, watchedFocusRowKey, shownRowsSnapshot)
        } else {
            orderedRows
        }
        val visibleRows = if (listedFilter != null) {
            withoutWatchedRows.withoutListed(listedFilter, watchedFocusRowKey, shownRowsSnapshot)
        } else {
            withoutWatchedRows
        }
        ensureActive()
        val heldWatchedRowKey = watchedFocusRowKey?.takeIf { key ->
            visibleRows.any { row ->
                row.stableKey() == key && row.items.any { item ->
                    watchedIds?.isWatched(item) == true || listedFilter?.hidesFrom(row, item) == true
                }
            }
        }
        val selectedHeroCatalogSet = heroCatalogKeys.toSet()
        val orderedKeySet = orderedKeys.toSet()
        val selectedHeroRows = if (selectedHeroCatalogSet.isNotEmpty()) {
            // Include hero catalogs from ordered rows
            val fromOrdered = visibleRows.filter { row ->
                val key = row.legacyKey()
                key in selectedHeroCatalogSet
            }
            // Also include hero catalogs loaded but not in catalog order
            // (e.g., catalogs disabled from home rows but selected for hero)
            val heroOnlyRows = selectedHeroCatalogSet
                .filter { it !in orderedKeySet }
                .mapNotNull { catalogSnapshot[it] }
            val heroOnlyReleased = if (hideUnreleased) {
                val today = LocalDate.now()
                heroOnlyRows.map { it.filterReleasedItems(today) }
            } else {
                heroOnlyRows
            }
            val heroOnlyWatchedFiltered = if (watchedIds != null) {
                heroOnlyReleased.map { it.withoutWatched(watchedIds) }
            } else {
                heroOnlyReleased
            }
            val heroOnlyFiltered = if (listedFilter != null) {
                heroOnlyWatchedFiltered.map { it.withoutListed(listedFilter) }
            } else {
                heroOnlyWatchedFiltered
            }
            fromOrdered + heroOnlyFiltered
        } else {
            emptyList()
        }
        val currentHeroOrder = heroItemOrder

        val computedHeroItems = pickHeroItems(
            selectedHeroRows, { it.hasHeroArtwork() }, currentHeroOrder
        ).ifEmpty {
            pickHeroItems(selectedHeroRows, { true }, currentHeroOrder)
        }.ifEmpty {
            if (selectedHeroCatalogSet.isNotEmpty()) return@ifEmpty emptyList()
            // When orderedRows is empty (all catalogs disabled), include any
            // hero-only loaded catalogs as fallback hero sources.
            val allHeroFallbackRows = if (orderedRows.isNotEmpty()) {
                visibleRows
            } else {
                val nonOrderedRows = catalogSnapshot.keys
                    .filter { it !in orderedKeySet }
                    .mapNotNull { catalogSnapshot[it] }
                val releasedRows = if (hideUnreleased) {
                    val today = LocalDate.now()
                    nonOrderedRows.map { it.filterReleasedItems(today) }
                } else {
                    nonOrderedRows
                }
                val watchedFiltered =
                    if (watchedIds != null) releasedRows.map { it.withoutWatched(watchedIds) } else releasedRows
                if (listedFilter != null) watchedFiltered.map { it.withoutListed(listedFilter) } else watchedFiltered
            }
            pickHeroItems(allHeroFallbackRows, { it.hasHeroArtwork() }, currentHeroOrder)
        }
        ensureActive()

        val computedDisplayRows = visibleRows.map { row ->
            ensureActive()
            val shouldKeepFullRowInModern = currentLayout == HomeLayout.MODERN
            val gridTruncateLimit = 24
            if (row.items.size > gridTruncateLimit && !shouldKeepFullRowInModern) {
                val key = row.legacyKey()
                val cachedEntry = getTruncatedRowCacheEntry(key)
                if (cachedEntry != null && cachedEntry.sourceRow === row) {
                    cachedEntry.truncatedRow
                } else {
                    val truncatedRow = row.copy(
                        items = row.items.take(gridTruncateLimit),
                        hasMore = true
                    )
                    putTruncatedRowCacheEntry(
                        key,
                        HomeViewModel.TruncatedRowCacheEntry(
                            sourceRow = row,
                            truncatedRow = truncatedRow
                        )
                    )
                    truncatedRow
                }
            } else {
                val key = row.legacyKey()
                removeTruncatedRowCacheEntry(key)
                row
            }
        }

        CatalogUpdateResult(
            displayRows = computedDisplayRows,
            heroItems = computedHeroItems,
            gridItems = emptyList(),
            fullRows = orderedRows,
            statusRows = if (watchedIds != null) orderedRows else computedDisplayRows,
            heldWatchedRowKey = heldWatchedRowKey
        )
    }
    val displayRows = catalogUpdate.displayRows
    val baseHeroItems = catalogUpdate.heroItems
    val fullRowsFiltered = catalogUpdate.fullRows
    watchedTitles.heldRowKey = catalogUpdate.heldWatchedRowKey

    _fullCatalogRows.update { rows ->
        if (rows == fullRowsFiltered) rows else fullRowsFiltered
    }

    heroItemOrder = baseHeroItems.map { it.id }

    val (computedHomeRows, nextGridItems) = withContext(Dispatchers.Default) {
        val computedHomeRows = buildList {
            val displayRowsByKey = displayRows.associateBy { it.legacyKey() }
            // Build a lookup of placeholder descriptors by key for lazy catalogs
            val placeholdersByKey = synchronized(catalogStateLock) {
                placeholderDescriptors.associateBy { it.catalogKey }
            }
            val addedCollectionIds = mutableSetOf<String>()
            collectionsCache.forEach { collection ->
                val key = "collection_${collection.id}"
            if (collection.pinToTop && key !in disabledHomeCatalogKeys && addedCollectionIds.add(collection.id)) {
                add(HomeRow.CollectionRow(collection))
            }
        }
        for (key in orderedKeys) {
            ensureActive()
            if (key in disabledHomeCatalogKeys) continue
            val collectionEntry = collectionsSnapshot[key]
            if (collectionEntry != null) {
                if (!collectionEntry.pinToTop && addedCollectionIds.add(collectionEntry.id)) {
                    add(HomeRow.CollectionRow(collectionEntry))
                }
            } else {
                    val catalogRow = displayRowsByKey[key]
                    if (catalogRow != null && catalogRow.items.isNotEmpty()) {
                        add(HomeRow.Catalog(catalogRow))
                    } else {
                        val placeholder = placeholdersByKey[key]
                        if (placeholder != null) {
                        if (currentLayout == HomeLayout.MODERN) {
                            add(HomeRow.PlaceholderCatalog(
                                catalogKey = placeholder.catalogKey,
                                stableCatalogKey = catalogRowStableKey(
                                    placeholder.addonId,
                                    placeholder.addonBaseUrl,
                                    placeholder.apiType,
                                    placeholder.catalogId
                                ),
                                addonId = placeholder.addonId,
                                addonName = placeholder.addonName,
                                addonBaseUrl = placeholder.addonBaseUrl,
                                catalogId = placeholder.catalogId,
                                catalogName = placeholder.catalogName,
                                apiType = placeholder.apiType,
                                displayTitle = placeholder.displayTitle
                            ))
                        } else {
                            val fakeItems = (0 until 8).map { i ->
                                MetaPreview(
                                    id = "__placeholder_${placeholder.catalogKey}_$i",
                                    type = com.nuvio.tv.domain.model.ContentType.fromString(placeholder.apiType),
                                    rawType = placeholder.apiType,
                                    name = " ",
                                    poster = PLACEHOLDER_IMAGE_URL,
                                    posterShape = com.nuvio.tv.domain.model.PosterShape.POSTER,
                                    background = null,
                                    logo = null,
                                    description = null,
                                    releaseInfo = " ",
                                    imdbRating = null,
                                    genres = emptyList()
                                )
                            }
                            add(HomeRow.Catalog(CatalogRow(
                                addonId = placeholder.addonId,
                                addonName = placeholder.addonName,
                                addonBaseUrl = placeholder.addonBaseUrl,
                                catalogId = placeholder.catalogId,
                                catalogName = placeholder.catalogName,
                                type = com.nuvio.tv.domain.model.ContentType.fromString(placeholder.apiType),
                                rawType = placeholder.apiType,
                                items = fakeItems,
                                isLoading = true,
                                hasMore = false
                            )))
                        }
                    }
                }
            }
        }
    }

    val nextGridItems = if (currentLayout == HomeLayout.GRID) {
        val posterCardWidthDp = _uiState.value.posterCardWidthDp
        val rowCount = if (posterCardWidthDp <= 104) 2 else 3
        // Provide generous upper bound of items — the Composable layer will trim
        // based on the actual column count from GridCells.Adaptive layout info.
        // We use 8 as safe max columns (widest known config) to avoid cutting too early.
        val safeMaxColumns = 8
        val maxDisplaySlots = safeMaxColumns * rowCount
        buildList {
            if (heroSectionEnabled && baseHeroItems.isNotEmpty()) {
                add(GridItem.Hero(baseHeroItems))
            }
            computedHomeRows.forEach { homeRow ->
                when (homeRow) {
                    is HomeRow.Catalog -> {
                        val row = homeRow.row
                        val isPlaceholderRow = row.isLoading &&
                            row.items.firstOrNull()?.id?.startsWith("__placeholder_") == true
                        if (row.items.isNotEmpty() && !isPlaceholderRow) {
                            add(GridItem.SectionDivider(
                                catalogName = row.catalogName,
                                catalogId = row.catalogId,
                                addonBaseUrl = row.addonBaseUrl,
                                addonId = row.addonId,
                                type = row.apiType
                            ))
                            // Show "See All" if there are more items than fit in the
                            // displayed rows, or the API indicates more pages exist.
                            val showSeeAll = row.hasMore || row.items.size > maxDisplaySlots
                            val rawMax = if (showSeeAll) maxDisplaySlots - 1 else maxDisplaySlots
                            val displayItems = row.items.take(rawMax)
                            displayItems.forEach { item ->
                                add(GridItem.Content(
                                    item = item,
                                    addonBaseUrl = row.addonBaseUrl,
                                    catalogId = row.catalogId,
                                    catalogName = row.catalogName,
                                    addonId = row.addonId
                                ))
                            }
                            if (showSeeAll) {
                                add(GridItem.SeeAll(
                                    catalogId = row.catalogId,
                                    addonId = row.addonId,
                                    addonBaseUrl = row.addonBaseUrl,
                                    type = row.apiType
                                ))
                            }
                        }
                    }
                    is HomeRow.CollectionRow -> {
                        val col = homeRow.collection
                        add(GridItem.CollectionHeader(
                            collectionId = col.id,
                            title = col.title
                        ))
                        col.folders.forEach { folder ->
                            add(GridItem.CollectionFolder(
                                collectionId = col.id,
                                collectionTitle = col.title,
                                focusGlowEnabled = col.focusGlowEnabled,
                                folder = folder
                            ))
                        }
                    }
                    is HomeRow.PlaceholderCatalog -> {
                        // Grid layout: skip placeholders (grid loads all at once)
                    }
                }
            }
        }.let { replaceGridHeroItemsPipeline(it, baseHeroItems) }
    } else {
        currentGridItems
    }

        computedHomeRows to nextGridItems
    }

    // Capture the displayed profile's artwork while its own catalog pipeline is active.
    // A profile switch during computation must never relabel the old rows.
    if (profileManager.activeProfileId.value != posterProfileId) return
    val wallPosters = displayRows.asSequence().flatMap { it.items.asSequence() }
        .mapNotNull { it.poster }.filter(String::isNotBlank).distinct().take(24).toList()
    withContext(Dispatchers.IO) {
        com.nuvio.tv.ui.v2.profile.ProfilePosterWall.rememberPosters(appContext, wallPosters, posterProfileId)
    }

    // Clear any stale error when content is now available (e.g., hero
    // catalogs loaded after the initial startup race set an error).
    val hasContent = computedHomeRows.isNotEmpty() || baseHeroItems.isNotEmpty() || displayRows.isNotEmpty()

    _uiState.update { state ->
        state.copy(
            catalogRows = if (state.catalogRows == displayRows) state.catalogRows else displayRows,
            heroItems = if (state.heroItems == baseHeroItems) state.heroItems else baseHeroItems,
            gridItems = if (state.gridItems == nextGridItems) state.gridItems else nextGridItems,
            homeRows = if (state.homeRows == computedHomeRows) state.homeRows else computedHomeRows,
            isLoading = false,
            error = if (hasContent) null else state.error
        )
    }

    val tmdbSettings = currentTmdbSettings
    val tmdbEnabledForCurrentLayout = tmdbSettings.enabled &&
        (currentLayout != HomeLayout.MODERN || tmdbSettings.modernHomeEnabled)
    val shouldUseEnrichedHeroItems = tmdbEnabledForCurrentLayout &&
        (tmdbSettings.useArtwork || tmdbSettings.useBasicInfo || tmdbSettings.useDetails)

    if (shouldUseEnrichedHeroItems && baseHeroItems.isNotEmpty()) {
        heroEnrichmentJob?.cancel()
        heroEnrichmentJob = viewModelScope.launch {
            val enrichmentSignature = heroEnrichmentSignaturePipeline(baseHeroItems, tmdbSettings)
            if (lastHeroEnrichmentSignature == enrichmentSignature) {
                val cached = lastHeroEnrichedItems
                _uiState.update { state ->
                    state.copy(
                        heroItems = if (state.heroItems == cached) state.heroItems else cached,
                        gridItems = if (currentLayout == HomeLayout.GRID) {
                            val enrichedGrid = replaceGridHeroItemsPipeline(state.gridItems, cached)
                            if (state.gridItems == enrichedGrid) state.gridItems else enrichedGrid
                        } else state.gridItems
                    )
                }
            } else {
                val enrichedItems = enrichHeroItemsPipeline(baseHeroItems, tmdbSettings)
                lastHeroEnrichmentSignature = enrichmentSignature
                lastHeroEnrichedItems = enrichedItems
                _uiState.update { state ->
                    state.copy(
                        heroItems = if (state.heroItems == enrichedItems) state.heroItems else enrichedItems,
                        gridItems = if (currentLayout == HomeLayout.GRID) {
                            val enrichedGrid = replaceGridHeroItemsPipeline(state.gridItems, enrichedItems)
                            if (state.gridItems == enrichedGrid) state.gridItems else enrichedGrid
                        } else state.gridItems
                    )
                }
            }
        }
    } else {
        lastHeroEnrichmentSignature = null
        lastHeroEnrichedItems = emptyList()
        heroItemOrder = emptyList()
    }

    schedulePosterStatusReconcilePipeline(catalogUpdate.statusRows)
    if (watchedIds != null || listedFilter != null) {
        requestWatchedRefillPages(fullRowsFiltered, displayRows)
    }
}

/**
 * Shares seven hero slots across [rows]. Titles already in [currentOrder] keep their place, the rest of
 * each slot is filled in a stable per-catalog order, and a title seen in an earlier row is never picked again.
 */
internal suspend fun pickHeroItems(
    rows: List<CatalogRow>,
    filter: (MetaPreview) -> Boolean,
    currentOrder: List<String>
): List<MetaPreview> {
    val totalCatalogs = rows.size.coerceAtLeast(1)
    val baseSlot = HERO_SLOT_COUNT / totalCatalogs
    val remainder = HERO_SLOT_COUNT % totalCatalogs
    val seen = HashSet<String>()
    val result = mutableListOf<MetaPreview>()
    for ((index, row) in rows.withIndex()) {
        currentCoroutineContext().ensureActive()
        val slot = baseSlot + if (index < remainder) 1 else 0
        // Slots only shrink down the list, so no later row can add anything.
        if (slot == 0) break
        val existing = currentOrder.filter { id -> row.items.any { it.id == id } }
        val byId = HashMap<String, MetaPreview>(row.items.size)
        row.items.forEach { if (filter(it)) byId[it.id] = it }
        val picked = ArrayList<MetaPreview>(slot)
        existing.forEach { id ->
            val item = byId[id] ?: return@forEach
            if (seen.add(item.id) && picked.size < slot) picked += item
        }
        val need = slot - picked.size
        if (need > 0) {
            val prefixHash = "${row.addonId}|${row.apiType}|${row.catalogId}|".hashCode()
            val topKeys = IntArray(need)
            val top = arrayOfNulls<MetaPreview>(need)
            var size = 0
            for (item in byId.values) {
                if (item.id in existing || item.id in seen) continue
                val key = stableHeroSortKey(prefixHash, item.id)
                if (size == need && compareHeroKeys(key, item.id, topKeys[size - 1], top[size - 1]!!.id) >= 0) continue
                var pos = if (size < need) size++ else need - 1
                while (pos > 0 && compareHeroKeys(key, item.id, topKeys[pos - 1], top[pos - 1]!!.id) < 0) {
                    topKeys[pos] = topKeys[pos - 1]
                    top[pos] = top[pos - 1]
                    pos--
                }
                topKeys[pos] = key
                top[pos] = item
            }
            for (i in 0 until size) picked += top[i]!!
        }
        seen.addAll(byId.keys)
        result += picked
    }
    return result
}

private const val HERO_SLOT_COUNT = 7

/** Same value as `(prefix + id).hashCode()` without building the joined string. */
internal fun stableHeroSortKey(prefixHash: Int, id: String): Int {
    var hash = prefixHash
    for (ch in id) hash = 31 * hash + ch.code
    return hash
}

private fun compareHeroKeys(key: Int, id: String, otherKey: Int, otherId: String): Int {
    val byKey = key.compareTo(otherKey)
    return if (byKey != 0) byKey else id.compareTo(otherId)
}

internal fun HomeViewModel.schedulePosterStatusReconcilePipeline(rows: List<CatalogRow>) {
    posterStatusReconcileJob?.cancel()
    if (rows.isEmpty()) {
        reconcilePosterStatusObserversPipeline(rows)
        return
    }
    posterStatusReconcileJob = viewModelScope.launch {
        delay(500)
        reconcilePosterStatusObserversPipeline(rows)
    }
}

internal fun HomeViewModel.reconcilePosterStatusObserversPipeline(rows: List<CatalogRow>) {
    val allMovieItemsByKey = linkedMapOf<String, String>()
    rows.asSequence()
        .flatMap { row -> row.items.asSequence() }
        .filter { it.apiType.equals("movie", ignoreCase = true) }
        .forEach { item ->
            val key = homeItemStatusKey(item.id, item.apiType)
            if (key !in allMovieItemsByKey) {
                allMovieItemsByKey[key] = item.id
            }
        }
    val desiredMovieKeys = allMovieItemsByKey.keys

    val allSeriesItemsByKey = linkedMapOf<String, String>()
    rows.asSequence()
        .flatMap { row -> row.items.asSequence() }
        .filter { it.apiType.equals("series", ignoreCase = true) || it.apiType.equals("tv", ignoreCase = true) || it.apiType.equals("anime", ignoreCase = true) }
        .forEach { item ->
            val key = homeItemStatusKey(item.id, item.apiType)
            if (key !in allSeriesItemsByKey) {
                allSeriesItemsByKey[key] = item.id
            }
        }


    if (desiredMovieKeys != lastMovieWatchedItemKeys) {
        lastMovieWatchedItemKeys = desiredMovieKeys
        movieWatchedObserverJobs.values.forEach { it.cancel() }
        movieWatchedObserverJobs.clear()
        movieWatchedBatchJob?.cancel()

        if (desiredMovieKeys.isNotEmpty()) {
            movieWatchedBatchJob = viewModelScope.launch {
                watchProgressRepository.observeWatchedMovieIds()
                    .collectLatest { watchedIds ->
                        _uiState.update { state ->
                            val movieStatus = buildMap {
                                allMovieItemsByKey.forEach { (statusKey, contentId) ->
                                    put(statusKey, contentId in watchedIds)
                                }
                            }
                            // Merge with existing status to preserve series entries.
                            val merged = state.movieWatchedStatus
                                .filterKeys { it !in desiredMovieKeys } + movieStatus
                            if (state.movieWatchedStatus == merged) {
                                state
                            } else {
                                state.copy(movieWatchedStatus = merged)
                            }
                        }
                    }
            }
        }
    }

    // Update series watched status from CW pipeline's fully-watched resolution.
    // This piggybacks on the meta lookups CW already performs — no extra network calls.
    if (allSeriesItemsByKey.isNotEmpty()) {
        seriesWatchedObserverJob?.cancel()
        seriesWatchedObserverJob = viewModelScope.launch {
            combine(
                fullyWatchedSeriesIds.fullyWatchedSeriesIds,
                watchProgressRepository.watchedItems
            ) { fullyWatched, watchedItems ->
                fullyWatched to watchedItems
            }.collectLatest { (fullyWatched, watchedItems) ->
                val effectiveFullyWatched = if (
                    watchProgressRepository.activeProviderOwnsCompletedHistoryProjection()
                ) {
                    fullyWatched
                } else {
                    reconcileFullyWatchedFromLocalItems(
                        fullyWatched = fullyWatched,
                        watchedItems = watchedItems,
                        seriesContentIds = allSeriesItemsByKey.values
                    )
                }
                val seriesStatus = buildMap {
                    allSeriesItemsByKey.forEach { (statusKey, contentId) ->
                        put(statusKey, contentId in effectiveFullyWatched)
                    }
                }
                _uiState.update { state ->
                    val merged = state.movieWatchedStatus
                        .filterKeys { it !in allSeriesItemsByKey.keys } + seriesStatus
                    if (state.movieWatchedStatus == merged) state
                    else state.copy(movieWatchedStatus = merged)
                }
            }
        }
    } else {
        seriesWatchedObserverJob?.cancel()
        seriesWatchedObserverJob = null
    }

    _uiState.update { state ->
        val trimmedMovieWatchedPending =
            state.movieWatchedPending.filterTo(linkedSetOf()) { it in desiredMovieKeys }

        if (trimmedMovieWatchedPending == state.movieWatchedPending) {
            state
        } else {
            state.copy(movieWatchedPending = trimmedMovieWatchedPending)
        }
    }
}

private fun HomeViewModel.reconcileFullyWatchedFromLocalItems(
    fullyWatched: Set<String>,
    watchedItems: List<WatchedItem>,
    seriesContentIds: Iterable<String>
): Set<String> {
    val watchedEpisodesByContentId = watchedItems
        .filter { it.season != null && it.episode != null }
        .groupBy { it.contentId }
        .mapValues { (_, items) -> items.map { it.season!! to it.episode!! }.toSet() }
    val cacheResolvedIds = mutableSetOf<String>()
    val cacheResolvedFullyWatched = buildSet {
        seriesContentIds.forEach { contentId ->
            val requiredEpisodes = synchronized(cwBadgeEpisodeCache) {
                cwBadgeEpisodeCache["series:$contentId"] ?: cwBadgeEpisodeCache["tv:$contentId"]
            } ?: return@forEach
            cacheResolvedIds.add(contentId)
            val watchedEpisodes = watchedEpisodesByContentId[contentId].orEmpty()
            if (requiredEpisodes.isNotEmpty() && requiredEpisodes.all { it in watchedEpisodes }) {
                add(contentId)
            }
        }
    }
    if (cacheResolvedIds.isEmpty()) return fullyWatched
    val mergedHolderIds = (fullyWatched - cacheResolvedIds) + cacheResolvedFullyWatched
    if (mergedHolderIds != fullyWatchedSeriesIds.fullyWatchedSeriesIds.value) {
        fullyWatchedSeriesIds.updateWithValidation(mergedHolderIds, cacheResolvedIds)
    }
    return mergedHolderIds
}

private const val CATALOG_SWEEP_CONCURRENCY = 2
private const val CATALOG_SWEEP_YIELD_POLL_MS = 150L
/** The first seconds after the visible rows land belong to the user's first moves, not to off-screen rows. */
private const val CATALOG_SWEEP_START_DELAY_MS = 5_000L

// Reserved-headroom background sweep.
// Once the eager catalogue batch is dispatched, this drains the remaining
// pendingLazyCatalogs automatically, in display (insertion) order, so off-screen
// rows fill in on their own without the user scrolling to them. It yields to
// eager and on-demand (scrolled-to) loads: it only dispatches while no non-sweep
// catalogue load is pending and while fewer than CATALOG_SWEEP_CONCURRENCY sweep
// loads are already in flight, leaving at least three of the shared five
// catalogLoadSemaphore permits free so a scrolled-to row never queues behind
// background work. Aborts if the catalogue generation changes (refresh / layout
// switch). It starts CATALOG_SWEEP_START_DELAY_MS after the eager batch is done, and
// its rows reach the screen in batches (see scheduleUpdateCatalogRows).
internal fun HomeViewModel.startReservedHeadroomSweep(generation: Long) {
    catalogSweepJob?.cancel()
    catalogSweepJob = viewModelScope.launch {
        while (isActive && generation == catalogLoadGeneration && pendingCatalogLoads > catalogSweepInFlight.get()) {
            delay(CATALOG_SWEEP_YIELD_POLL_MS)
        }
        delay(CATALOG_SWEEP_START_DELAY_MS)
        while (isActive && generation == catalogLoadGeneration) {
            val nonSweepPending = pendingCatalogLoads > catalogSweepInFlight.get()
            val atSweepCap = catalogSweepInFlight.get() >= CATALOG_SWEEP_CONCURRENCY
            if (nonSweepPending || atSweepCap) {
                delay(CATALOG_SWEEP_YIELD_POLL_MS)
                continue
            }
            val next = pullNextSweepCatalog() ?: break
            catalogSweepInFlight.incrementAndGet()
            pendingCatalogLoads = pendingCatalogLoads + 1
            val job = loadCatalogPipeline(next.first, next.second, generation)
            job.invokeOnCompletion { catalogSweepInFlight.decrementAndGet() }
        }
    }
}

// Atomically claim the next pending lazy catalogue for the sweep, in display
// (insertion) order, sharing the same catalogStateLock and lazyLoadRequestedKeys
// dedup as the on-demand requestLazyCatalogLoad path so a catalogue is never
// loaded twice. Returns null once the pending set is drained.
private fun HomeViewModel.pullNextSweepCatalog(): Pair<Addon, CatalogDescriptor>? {
    return synchronized(catalogStateLock) {
        val iterator = pendingLazyCatalogs.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val key = entry.key
            val value = entry.value
            iterator.remove()
            if (lazyLoadRequestedKeys.add(key)) {
                return@synchronized value
            }
        }
        null
    }
}

/**
 * Re-request page 1 of every catalog that is already on screen and swap each result in
 * place.  Unlike [loadAllCatalogsPipeline] with `forceReload`, this leaves the row list,
 * its order and the loading placeholders untouched, so the vertical layout never reflows
 * and focus stays on the card the user left it on.
 */
/**
 * Merges a freshly fetched page 1 into the row that is already on screen.
 *
 * Returns true when the row has been dealt with here, false when the caller should just replace
 * it.  Three outcomes, in order:
 *
 *  - **nothing changed**: keep the row, and with it the pages the user has already paginated in;
 *  - **a pure prepend**: the addon put items in front and what follows still matches the head of
 *    the row, in order.  Nothing disappears and no card changes identity, so this is applied
 *    straight away even while the row holds focus.  The pagination window moves by the same
 *    amount, otherwise the next page would re-serve items the row already has;
 *  - **a real restructuring**: reorder, removal or turnover.  Rebuilding drops the cards under
 *    the focus ring, so the row the user has focus on is left alone and picked up on a later
 *    pass, once focus has moved on.
 */
internal fun HomeViewModel.mergeRefreshedCatalogRow(
    key: String,
    fresh: CatalogRow,
    /** True when the user asked for the refresh, in which case seeing the change wins over
     *  keeping their place in the row they happen to be on. */
    requestedByUser: Boolean = false,
    /** True when poster URLs may have changed (e.g. custom poster pattern update).
     *  Forces a full row replacement even if IDs haven't changed. */
    forceReplace: Boolean = false
): Boolean {
    val current = readCatalogRow(key) ?: return false
    if (current.items.isEmpty()) return false
    if (fresh.items.isEmpty()) return true
    if (forceReplace) return false

    val identity = { item: com.nuvio.tv.domain.model.MetaPreview -> item.apiType + ":" + item.id }
    val currentIds = current.items.map(identity)
    val freshIds = fresh.items.map(identity)

    if (freshIds == currentIds.take(freshIds.size)) {
        return true
    }

    val existing = currentIds.toHashSet()
    val added = fresh.items.takeWhile { identity(it) !in existing }
    val rest = freshIds.drop(added.size)
    val isPrepend = added.isNotEmpty() && rest.isNotEmpty() &&
        rest.size <= currentIds.size &&
        rest.indices.all { rest[it] == currentIds[it] }

    val focusedRowKey = liveFocusedRowKey
    val rowHasFocus = focusedRowKey != null && focusedRowKey == fresh.stableKey()

    if (isPrepend) {
        // Nothing is removed and every card already on screen keeps its key, so the focused card
        // only shifts along and its node is reused. That holds in the modern layout, which keeps
        // the whole row; the others cut it at a fixed length, where the focused card can be
        // pushed past the cut and no key brings back a card that has left the list.
        if (!requestedByUser && rowHasFocus && _uiState.value.homeLayout != HomeLayout.MODERN) {
            return true
        }
        val shiftedSkip = if (current.supportsSkip && current.nextSkip > 0) {
            current.nextSkip + added.size
        } else {
            current.nextSkip
        }
        replaceCatalogRow(key, current.copy(items = added + current.items, nextSkip = shiftedSkip))
        Log.d(
            HomeViewModel.TAG,
            "Home catalog refresh: +${added.size} item(s) catalogId=${fresh.catalogId}"
        )
        onCatalogRowItemsChanged(key)
        return true
    }

    // The row was restructured. Rebuilding it drops the cards under the focus ring, so leave the
    // focused row alone and pick it up once focus has moved on.
    return rowHasFocus && !requestedByUser
}


private const val FAILED_CATALOG_RETRY_DELAY_MS = 30_000L

private fun HomeViewModel.scheduleFailedCatalogRetry() {
    if (failedCatalogRetryJob?.isActive == true) return
    failedCatalogRetryJob = viewModelScope.launch {
        delay(FAILED_CATALOG_RETRY_DELAY_MS)
        refreshVisibleCatalogsPipeline(failedOnly = true)
    }
}

internal fun HomeViewModel.refreshVisibleCatalogsPipeline(
    requestedByUser: Boolean = false,
    forceReplace: Boolean = false,
    failedOnly: Boolean = false
) {
    val loadedKeys = if (failedOnly) {
        failedCatalogKeys.toSet()
    } else {
        synchronized(catalogStateLock) { catalogsMap.keys.toSet() } + failedCatalogKeys
    }
    if (loadedKeys.isEmpty()) return

    val toRefresh = addonsCache.flatMap { addon ->
        addon.catalogs
            .filter { catalog ->
                !catalog.isSearchOnlyCatalog() && catalogKey(
                    addonId = addon.id,
                    type = catalog.apiType,
                    catalogId = catalog.id
                ) in loadedKeys
            }
            .map { catalog -> addon to catalog }
    }
    if (toRefresh.isEmpty()) return

    Log.d(HomeViewModel.TAG, "Refreshing ${toRefresh.size} home catalogs in place")
    val generation = catalogLoadGeneration
    pendingCatalogLoads += toRefresh.size
    toRefresh.forEach { (addon, catalog) ->
        loadCatalogPipeline(addon, catalog, generation, isRefresh = true, requestedByUser = requestedByUser, forceReplace = forceReplace)
    }
}
