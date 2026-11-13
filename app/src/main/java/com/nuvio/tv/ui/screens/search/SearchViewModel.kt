package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.domain.model.catalogRowLegacyKey
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.DiscoverSelectionDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.SearchHistoryDataStore
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.catalogRowStableKey
import com.nuvio.tv.domain.model.mergeCatalogPage
import com.nuvio.tv.domain.model.nextCatalogSkip
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.stableKey
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.core.util.filterReleasedItems
import com.nuvio.tv.core.util.isUnreleased
import com.nuvio.tv.domain.repository.AddonRepository
import java.time.LocalDate
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val metaRepository: com.nuvio.tv.domain.repository.MetaRepository,
    private val discoverSelectionDataStore: DiscoverSelectionDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val searchHistoryDataStore: SearchHistoryDataStore,
    private val watchProgressRepository: com.nuvio.tv.domain.repository.WatchProgressRepository,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController,
    private val serverCatalog: ServerCatalog,
    private val catalogSpeed: SearchCatalogSpeed,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** Saved focus state for restoring scroll/focus position after returning from details. */
    var savedFocusRowKey: String? = null
    var savedFocusItemIndex: Int = -1
    var savedRowScrollPositions: Map<String, Pair<Int, Int>> = emptyMap()
    var hasSavedSearchFocus: Boolean = false

    private val _watchedMovieIds = MutableStateFlow<Set<String>>(emptySet())
    val watchedMovieIds: StateFlow<Set<String>> = _watchedMovieIds.asStateFlow()
    val watchedSeriesIds: StateFlow<Set<String>> = watchedSeriesStateHolder.fullyWatchedSeriesIds

    private val catalogsMap = linkedMapOf<String, CatalogRow>()
    private val catalogOrder = mutableListOf<String>()

    private var activeSearchJobs: List<Job> = emptyList()

    /**
     * Load-more jobs, tracked separately from [activeSearchJobs] because that list is replaced
     * wholesale by each search run. Cancelled alongside it in [cancelSearchRun].
     */
    private val activeLoadMoreJobs: MutableSet<Job> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var searchRunJob: Job? = null
    private var activeSearchQuery: String? = null
    private var searchGeneration = 0L
    private var discoverJob: Job? = null
    private var catalogRowsUpdateJob: Job? = null
    private var liveSearchJob: Job? = null
    private var serversHeldBackQuery: String? = null
    private var lastRequestKey: String? = null
    private var lastCompletedRequestKey: String? = null
    private var hasRenderedFirstCatalog = false

    /** The query whose answers [catalogsMap] holds. */
    private var runQuery: String? = null
    private val answeredKeys = mutableSetOf<String>()
    private var searchSettled = false
    private var focusGraceElapsed = false
    private var updatingLabelsShown = false

    /** Rows already marked as updating when this run started keep the mark instead of blinking off. */
    private var carriedUpdatingKeys: Set<String> = emptySet()

    /** A submit that landed while a live run for the same query was still under way. */
    private var explicitSubmitQuery: String? = null
    private var historyQuery: String? = null

    /** Completed by a submit, so catalogs held back while typing start at once. */
    private var slowCatalogGate: CompletableDeferred<Unit>? = null

    /** Live search fills the keyboard strip from its own answers, keyed by catalog position. */
    private var feedSuggestions = false
    private val suggestionNames = mutableMapOf<Int, List<String>>()
    private var suggestionCatalogCount = 0
    private var revealBatchAfterNextDiscoverFetch = false
    private var hideUnreleasedContent = false

    private companion object {
        const val DISCOVER_INITIAL_LIMIT = 100
        const val DISCOVER_SHOW_MORE_BATCH = 50
        /**
         * Live search fires while typing, but each run fans out to every enabled addon catalog, so
         * it waits longer than the suggestion debounce to avoid a request storm per keystroke.
         */
        const val LIVE_SEARCH_DEBOUNCE_MS = 350L

        /** Catalogs known to be slow wait this much longer while typing, so a half-typed word skips them. */
        const val SLOW_CATALOG_TYPING_WAIT_MS = 800L

        /**
         * Media servers search their own database, and some slow right down on short words, so while
         * typing they wait for a longer pause and are not asked about a word shorter than this.
         */
        const val SERVER_TYPING_WAIT_MS = 250L
        const val SERVER_MIN_TYPED_LENGTH = 3

        /** Rows are up but a catalog above them has not answered: focus moves in after this anyway. */
        const val FOCUS_GRACE_MS = 1_500L

        /** A catalog still out after this stops holding the loading state. Its rows are still added if they come. */
        const val SEARCH_SETTLE_CAP_MS = 12_000L

        const val ROWS_UPDATE_INTERVAL_MS = 150L

        /** More search catalogs than this on one server and their answer times are not used. */
        const val CATALOGS_PER_HOST_TIMED = 5

        /** Rows from the previous query are only marked once they have been out of date this long, so quick answers do not flicker. */
        const val UPDATING_LABEL_DELAY_MS = 600L

        const val MAX_SUGGESTIONS = 8
        /** Splits titles and queries into words. */
        private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
        const val MAX_RECENT_SEARCHES = 8
    }

    init {
        posterOptions.bind(viewModelScope)
        viewModelScope.launch {
            watchProgressRepository.observeWatchedMovieIds()
                .collect { ids -> _watchedMovieIds.value = ids }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.discoverLocation.distinctUntilChanged().collectLatest { location ->
                _uiState.update { it.copy(discoverLocation = location) }
                if (location == DiscoverLocation.OFF) {
                    discoverJob?.cancel()
                    discoverJob = null
                    revealBatchAfterNextDiscoverFetch = false
                    _uiState.update {
                        it.copy(
                            discoverInitialized = false,
                            discoverLoading = false,
                            discoverLoadingMore = false,
                            discoverCatalogs = emptyList(),
                            selectedDiscoverType = "movie",
                            selectedDiscoverCatalogKey = null,
                            selectedDiscoverGenre = null,
                            selectedDiscoverLetter = null,
                            discoverUnwatchedOnly = false,
                            discoverResults = emptyList(),
                            pendingDiscoverResults = emptyList(),
                            discoverHasMore = true,
                            discoverPage = 1
                        )
                    }
                }
            }
        }
        // Combine all layout preference flows into a single collector to reduce coroutine overhead
        viewModelScope.launch {
            combine(
                layoutPreferenceDataStore.posterCardWidthDp,
                layoutPreferenceDataStore.posterLabelsEnabled,
                layoutPreferenceDataStore.catalogAddonNameEnabled,
                layoutPreferenceDataStore.posterCardHeightDp,
                layoutPreferenceDataStore.posterCardCornerRadiusDp
            ) { widthDp, labelsEnabled, addonNameEnabled, heightDp, cornerRadiusDp ->
                LayoutPrefs(widthDp, labelsEnabled, addonNameEnabled, heightDp, cornerRadiusDp)
            }.collectLatest { prefs ->
                _uiState.update {
                    it.copy(
                        posterCardWidthDp = prefs.widthDp,
                        posterLabelsEnabled = prefs.labelsEnabled,
                        catalogAddonNameEnabled = prefs.addonNameEnabled,
                        posterCardHeightDp = prefs.heightDp,
                        posterCardCornerRadiusDp = prefs.cornerRadiusDp
                    )
                }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.catalogTypeSuffixEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(catalogTypeSuffixEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.hideUnreleasedContent.collectLatest { enabled ->
                hideUnreleasedContent = enabled
                scheduleCatalogRowsUpdate()
            }
        }
        viewModelScope.launch {
            searchHistoryDataStore.recentSearches.collectLatest { recent ->
                _uiState.update { it.copy(recentSearches = recent.take(MAX_RECENT_SEARCHES)) }
            }
        }
    }

    private data class LayoutPrefs(
        val widthDp: Int,
        val labelsEnabled: Boolean,
        val addonNameEnabled: Boolean,
        val heightDp: Int,
        val cornerRadiusDp: Int
    )

    fun ensureDiscoverLoaded() {
        val state = _uiState.value
        if (state.discoverLocation == DiscoverLocation.OFF) return
        if (state.discoverInitialized || state.discoverLoading) return
        viewModelScope.launch { loadDiscoverCatalogs() }
    }

    private val metaPrefetchedIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var metaPrefetchJob: Job? = null

    /**
     * Prefetch meta from addons in background when an item receives focus.
     * Warms the MetaRepository cache so the detail screen loads instantly.
     * Debounced to avoid flooding the network during rapid scrolling.
     */
    fun prefetchMetaOnFocus(id: String, type: String) {
        if (id.isBlank() || id in metaPrefetchedIds) return
        metaPrefetchJob?.cancel()
        metaPrefetchJob = viewModelScope.launch {
            delay(150)
            if (id in metaPrefetchedIds) return@launch
            metaPrefetchedIds.add(id)
            val meta = metaRepository.getMetaFromAllAddons(type = type, id = id)
                .first { it !is com.nuvio.tv.core.network.NetworkResult.Loading }
            watchProgressRepository.getAllEpisodeProgress(id.substringBefore(":")).first()
            (meta as? com.nuvio.tv.core.network.NetworkResult.Success)?.data?.let {
                com.nuvio.tv.core.image.warmDetailArtworkOnDisk(context, it.background, it.logo)
            }
        }
    }

    /**
     * Returns the cached backdrop URL from a previously prefetched meta, or null.
     */
    fun getCachedBackdrop(id: String, type: String): String? {
        return metaRepository.getCachedMeta(type, id)?.backdropUrl
    }

    fun onEvent(event: SearchEvent) {
        when (event) {
            is SearchEvent.QueryChanged -> onQueryChanged(event.query)
            SearchEvent.SubmitSearch -> submitSearch()
            SearchEvent.RememberSearchFromTextInput -> rememberSearchFromTextInput()
            SearchEvent.ClearRecentSearches -> clearRecentSearches()
            is SearchEvent.RemoveRecentSearch -> removeRecentSearch(event.query)
            is SearchEvent.LoadMoreCatalog -> loadMoreCatalogItems(
                catalogId = event.catalogId,
                addonId = event.addonId,
                type = event.type
            )
            is SearchEvent.SelectDiscoverType -> selectDiscoverType(event.type)
            is SearchEvent.SelectDiscoverCatalog -> selectDiscoverCatalog(event.catalogKey)
            is SearchEvent.SelectDiscoverGenre -> selectDiscoverGenre(event.genre)
            is SearchEvent.SelectDiscoverLetter -> selectDiscoverFilter { it.copy(selectedDiscoverLetter = event.letter) }
            is SearchEvent.SetDiscoverUnwatched -> selectDiscoverFilter { it.copy(discoverUnwatchedOnly = event.unwatched) }
            SearchEvent.LoadNextDiscoverResults -> loadNextDiscoverResults()
            SearchEvent.Retry -> {
                // An explicit retry must refetch even though nothing about the request changed.
                lastRequestKey = null
                lastCompletedRequestKey = null
                cancelSearchRun()
                performSearch(uiState.value.submittedQuery.ifBlank { uiState.value.query })
            }
        }
    }

    private fun onQueryChanged(query: String) {
        _uiState.update {
            val trimmedInput = query.trim()
            // Narrow the strip to what still matches before anything is fetched, so a letter
            // that rules a title out drops it on that keystroke rather than a fetch later.
            // Narrowing does not clear the strip when every current title stops matching. The
            // fetch stays authoritative for an empty result.
            val narrowed = rankedSuggestions(it.suggestions, trimmedInput.lowercase())
            it.copy(
                query = query,
                suggestions = if (narrowed.isEmpty()) it.suggestions else narrowed,
                error = null,
                isSearching = false,
                // Keep whatever is on screen while a keystroke waits to run. Clearing here flashed
                // the no-results state on every letter, because on a remote each letter outlasts the
                // debounce. The screen renders skeleton rows for this window instead.
                catalogRows = if (trimmedInput.length < MIN_SEARCH_QUERY_LENGTH) emptyList() else it.catalogRows
            )
        }

        // Drop in-flight requests for the previous keystroke before scheduling the next run.
        cancelSearchRun()
        explicitSubmitQuery = null
        historyQuery = null

        // Live search: results follow what you type, like mobile. Debounced because each run hits
        // every enabled addon catalog.
        liveSearchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length >= MIN_SEARCH_QUERY_LENGTH) {
            liveSearchJob = viewModelScope.launch {
                kotlinx.coroutines.delay(LIVE_SEARCH_DEBOUNCE_MS)
                performSearch(query, keepSuggestions = true)
            }
        } else {
            // Emptying the field has to retire the submitted query too. Leaving it set kept the
            // screen in its results state with nothing to show, instead of falling back to recent
            // searches, until the screen was rebuilt by navigating away and back.
            performSearch(query)
        }
    }

    /** Match rank for [title], lower being better, or null when it does not match [queryLower]. */
    private fun suggestionRank(title: String, queryLower: String): Int? {
        val titleLower = title.lowercase()
        if (titleLower == queryLower) return 0
        if (titleLower.startsWith(queryLower)) return 1
        if (titleLower.contains(queryLower)) return 2

        // Allow multi-word matches such as "wolf wall" -> "The Wolf of Wall Street".
        // Each query word must consume a different title word.
        val queryWords = queryLower.split(WORD_SEPARATOR).filter { it.isNotEmpty() }
        if (queryWords.size < 2) return null
        val unmatchedTitleWords = titleLower.split(WORD_SEPARATOR).filterTo(mutableListOf()) { it.isNotEmpty() }
        val everyWordMatches = queryWords.all { word ->
            val index = unmatchedTitleWords.indexOfFirst { it.startsWith(word) }
            if (index >= 0) unmatchedTitleWords.removeAt(index)
            index >= 0
        }
        return if (everyWordMatches) 3 else null
    }

    /**
     * The strip contents for [names], best match first, capped at [MAX_SUGGESTIONS].
     *
     * Titles of the same rank keep the order of [names], which is the addon's own relevance order.
     * Sorting them alphabetically put five titles ahead of "Jurassic Park" for "juras", and of
     * "Slow Horses" for "slo", past the few completions a TV keyboard shows.
     */
    private fun rankedSuggestions(names: Collection<String>, queryLower: String): List<String> =
        names
            .mapNotNull { name -> suggestionRank(name, queryLower)?.let { name to it } }
            .sortedBy { it.second }
            .map { it.first }
            .take(MAX_SUGGESTIONS)

    /**
     * Every catalog's titles, taken one position at a time: each catalog's first title, then each
     * one's second, and so on, in catalog order. Each catalog's best matches lead, and the result
     * does not depend on which catalog happened to answer first.
     */
    private fun mergedCatalogNames(namesByCatalog: Map<Int, List<String>>, catalogCount: Int): List<String> {
        val catalogs = (0 until catalogCount).mapNotNull { namesByCatalog[it] }
        val merged = LinkedHashSet<String>()
        for (position in 0 until (catalogs.maxOfOrNull { it.size } ?: 0)) {
            catalogs.forEach { names -> names.getOrNull(position)?.let(merged::add) }
        }
        return merged.toList()
    }

    private fun offerSuggestions(catalogIndex: Int, names: List<String>, query: String) {
        if (_uiState.value.query.trim() != query) return
        val previous = suggestionNames.put(catalogIndex, names)
        // Answers arrive independently and are merged, so a batch whose titles all fail the filter
        // ranks to nothing while the batch holding the match is still out. Only the settle at the
        // end of the run may empty the strip: an empty push tells the keyboard there are no
        // completions, and it does not always take them back when the next batch lands.
        if (names == previous) return
        val ranked = rankedSuggestions(mergedCatalogNames(suggestionNames, suggestionCatalogCount), query.lowercase())
        if (ranked.isNotEmpty()) {
            _uiState.update { it.copy(suggestions = ranked) }
        }
    }

    /** Every catalog has answered, so this is the first point the query is known to have no suggestions. */
    private fun settleSuggestions(query: String) {
        if (!feedSuggestions || _uiState.value.query.trim() != query) return
        val ranked = rankedSuggestions(mergedCatalogNames(suggestionNames, suggestionCatalogCount), query.lowercase())
        _uiState.update { it.copy(suggestions = ranked) }
    }

    private fun submitSearch() {
        // An explicit submit just skips the remaining debounce; the live run would land anyway.
        liveSearchJob?.cancel()
        performSearch(_uiState.value.query, rememberToHistory = true)
    }

    /**
     * Moving from the text input into the results confirms the current query the same way
     * Done/submit does. Live search may already have results on screen without an explicit
     * submit; saving here
     * avoids losing useful history while still not recording every keystroke prefix.
     */
    private fun rememberSearchFromTextInput() {
        askHeldBackServers()
        val state = _uiState.value
        val query = state.submittedQuery.trim().ifBlank { state.query.trim() }
        if (query.length < MIN_SEARCH_QUERY_LENGTH) return
        val hasRealResults = state.catalogRows.any { row ->
            row.items.any { item -> !item.id.startsWith("__placeholder_") }
        } || catalogsMap.values.any { row ->
            row.items.any { item -> !item.id.startsWith("__placeholder_") }
        }
        if (!hasRealResults) return
        viewModelScope.launch {
            searchHistoryDataStore.saveRecentSearch(query, MAX_RECENT_SEARCHES)
        }
    }

    /** Moving into the results confirms a short word, so the media servers held back from it are asked now. */
    private fun askHeldBackServers() {
        val query = runQuery ?: return
        val generation = searchGeneration
        if (serversHeldBackQuery != query || !isCurrentSearch(generation, query)) return
        serversHeldBackQuery = null
        val allTargets = buildSearchTargets(_uiState.value.installedAddons)
        catalogOrder.clear()
        allTargets.mapTo(catalogOrder) { (addon, catalog) -> catalogKey(addon.id, addon.baseUrl, catalog.apiType, catalog.id) }
        val jobs = allTargets.mapIndexedNotNull { catalogIndex, (addon, catalog) ->
            if (!ServerCatalog.isServerAddon(addon.baseUrl)) return@mapIndexedNotNull null
            viewModelScope.launch { loadCatalog(addon, catalog, query, generation, catalogIndex, timed = true) }
        }
        activeSearchJobs = activeSearchJobs + jobs
        val requestKey = buildRequestKey(query, allTargets)
        lastRequestKey = requestKey
        viewModelScope.launch {
            jobs.joinAll()
            if (isCurrentSearch(generation, query) && lastRequestKey == requestKey) {
                lastCompletedRequestKey = requestKey
                updateCatalogRowsNow()
            }
        }
    }

    private fun clearRecentSearches() {
        viewModelScope.launch {
            searchHistoryDataStore.clearRecentSearches()
        }
    }

    private fun removeRecentSearch(query: String) {
        viewModelScope.launch {
            searchHistoryDataStore.removeRecentSearch(query)
        }
    }

    private fun resetCatalogAccumulator() {
        catalogsMap.clear()
        catalogOrder.clear()
        hasRenderedFirstCatalog = false
        runQuery = null
        answeredKeys.clear()
        searchSettled = false
        focusGraceElapsed = false
        updatingLabelsShown = false
        carriedUpdatingKeys = emptySet()
        suggestionNames.clear()
    }

    private fun cancelSearchRun() {
        searchGeneration++
        searchRunJob?.cancel()
        searchRunJob = null
        activeSearchJobs.forEach { it.cancel() }
        activeSearchJobs = emptyList()
        activeLoadMoreJobs.forEach { it.cancel() }
        activeLoadMoreJobs.clear()
        activeSearchQuery = null
        if (_uiState.value.focusReadyQuery.isNotEmpty() || _uiState.value.focusTargetRowKey != null) {
            _uiState.update { it.copy(focusReadyQuery = "", focusTargetRowKey = null) }
        }
    }

    /**
     * Identifies a search by everything that changes what it would return: the query, the released
     * filter, and the exact set of catalogs it would hit. Enabling an addon or flipping the filter
     * changes the key, so those still refetch.
     */
    private fun buildRequestKey(
        query: String,
        searchTargets: List<Pair<Addon, CatalogDescriptor>>
    ): String = buildString {
        append(query.lowercase())
        append('|')
        append(hideUnreleasedContent)
        append('|')
        append(
            searchTargets.joinToString(separator = "|") { (addon, catalog) ->
                "${addon.baseUrl}:${catalog.apiType}:${catalog.id}"
            }
        )
    }


    /**
     * @param keepSuggestions live search runs this on every keystroke, while the field is still
     * being typed into and the suggestion strip is the whole point. Those runs leave the strip
     * alone. A submit or a retry replaces the screen with results, which retires it.
     */
    private fun performSearch(
        rawQuery: String,
        rememberToHistory: Boolean = false,
        keepSuggestions: Boolean = false
    ) {
        val query = rawQuery.trim()
        if (!keepSuggestions) {
            feedSuggestions = false
            slowCatalogGate?.complete(Unit)
            explicitSubmitQuery = query
            if (rememberToHistory) historyQuery = query
        }
        _uiState.update {
            it.copy(
                submittedQuery = submittedSearchQuery(query),
                query = rawQuery,
                suggestions = if (keepSuggestions) it.suggestions else emptyList()
            )
        }

        if (query.length < MIN_SEARCH_QUERY_LENGTH) {
            cancelSearchRun()
            catalogRowsUpdateJob?.cancel()
            resetCatalogAccumulator()
            lastRequestKey = null
            lastCompletedRequestKey = null
            _uiState.update {
                it.copy(
                    isSearching = false,
                    error = null,
                    catalogRows = emptyList()
                )
            }
            ensureDiscoverLoaded()
            return
        }

        // Submit can immediately follow the debounced live-search launch. Reuse an active run for
        // the same query, but cancel a different query's entire scope before starting this one.
        if (activeSearchQuery == query && searchRunJob?.isActive == true &&
            (keepSuggestions || serversHeldBackQuery != query)
        ) return
        cancelSearchRun()
        val generation = searchGeneration
        activeSearchQuery = query

        val job = viewModelScope.launch {
            val addons = try {
                searchAddons()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == searchGeneration && activeSearchQuery == query) {
                    _uiState.update { it.copy(isSearching = false, error = e.message ?: context.getString(com.nuvio.tv.R.string.search_error_load_addons_failed)) }
                }
                return@launch
            }

            if (generation != searchGeneration || activeSearchQuery != query) return@launch

            // A submit can land while this live run is still looking up the addons.
            val explicit = !keepSuggestions || explicitSubmitQuery == query
            val allTargets = buildSearchTargets(addons)
            val addonTargets = allTargets.filterNot { (addon, _) -> ServerCatalog.isServerAddon(addon.baseUrl) }
            // With only media servers to search, holding them back would leave nothing to show.
            val searchTargets = if (explicit || query.length >= SERVER_MIN_TYPED_LENGTH || addonTargets.isEmpty()) {
                allTargets
            } else {
                addonTargets
            }

            // Same query against the same catalogs, and that run either finished or is still
            // arriving, so there is nothing new to fetch. Without this, pressing Done after live
            // search had already run the query tore the rows down and refetched everything, and
            // deleting a letter then retyping it did the same. A run that was cancelled part way
            // is deliberately not counted, so it gets to finish rather than staying half filled.
            val requestKey = buildRequestKey(query, searchTargets)
            val alreadySatisfied = requestKey == lastRequestKey &&
                (requestKey == lastCompletedRequestKey || activeSearchJobs.any { it.isActive })
            if (alreadySatisfied) {
                // An explicit submit that lands on a query the live search already finished still
                // counts as a search the user confirmed, so remember it instead of skipping out
                // of the history save below.
                if (rememberToHistory && catalogsMap.values.any { row -> row.items.isNotEmpty() }) {
                    viewModelScope.launch {
                        searchHistoryDataStore.saveRecentSearch(query, MAX_RECENT_SEARCHES)
                    }
                }
                // Starting this run cleared the ready mark; the rows it refers to are still here.
                updateCatalogRowsNow()
                return@launch
            }
            lastRequestKey = requestKey
            serversHeldBackQuery = query.takeIf { searchTargets.size < allTargets.size }

            // Committed to a new run: drop the previous query's work and accumulated rows.
            activeSearchJobs.forEach { it.cancel() }
            activeSearchJobs = emptyList()
            catalogRowsUpdateJob?.cancel()
            val previousUpdatingKeys = _uiState.value.updatingRowKeys
            resetCatalogAccumulator()
            runQuery = query
            carriedUpdatingKeys = previousUpdatingKeys
            feedSuggestions = !explicit
            suggestionCatalogCount = searchTargets.size

            // Rows are left alone here. Clearing them produced an empty frame between the old
            // results and the placeholders below, which is the flash this screen used to show.
            _uiState.update {
                it.copy(isSearching = true, error = null, installedAddons = addons, focusReadyQuery = "")
            }

            if (searchTargets.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        error = context.getString(R.string.search_error_no_catalogs),
                        catalogRows = emptyList()
                    )
                }
                return@launch
            }

            // Preserve addon manifest order.
            searchTargets.forEach { (addon, catalog) ->
                val key = catalogKey(
                    addonId = addon.id,
                    addonBaseUrl = addon.baseUrl,
                    type = catalog.apiType,
                    catalogId = catalog.id
                )
                if (key !in catalogOrder) {
                    catalogOrder.add(key)
                }
            }

            // Emit placeholder rows with shimmer items so the UI shows
            // skeleton rows immediately instead of a spinner.
            val placeholderRows = searchTargets.map { (addon, catalog) ->
                val key = catalogKey(
                    addonId = addon.id,
                    addonBaseUrl = addon.baseUrl,
                    type = catalog.apiType,
                    catalogId = catalog.id
                )
                val fakeItems = (0 until 8).map { i ->
                    MetaPreview(
                        id = "__placeholder_${key}_$i",
                        type = ContentType.fromString(catalog.apiType),
                        rawType = catalog.apiType,
                        name = " ",
                        poster = PLACEHOLDER_IMAGE_URL,
                        posterShape = PosterShape.POSTER,
                        background = null,
                        logo = null,
                        description = null,
                        releaseInfo = " ",
                        imdbRating = null,
                        genres = emptyList()
                    )
                }
                CatalogRow(
                    addonId = addon.id,
                    addonName = addon.displayName,
                    addonBaseUrl = addon.baseUrl,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    type = ContentType.fromString(catalog.apiType),
                    rawType = catalog.apiType,
                    items = fakeItems,
                    isLoading = true,
                    hasMore = false,
                    currentPage = 0,
                    supportsSkip = false,
                    skipStep = 0,
                    extraArgs = emptyMap()
                )
            }
            // Only shimmer when there is nothing real to look at. If the previous query's results
            // are still up, they stay until this query's results replace them, so refining a search
            // is a single swap rather than results -> shimmer -> results on every letter.
            _uiState.update { state ->
                val showingRealRows = state.catalogRows.any { row ->
                    row.items.firstOrNull()?.id?.startsWith("__placeholder_") != true
                }
                if (showingRealRows) state else state.copy(catalogRows = placeholderRows)
            }

            // A submit opens the gate at once. While typing, slow catalogs wait for a longer pause.
            val gate = CompletableDeferred<Unit>()
            if (explicit) gate.complete(Unit)
            slowCatalogGate = gate

            // A server answers a few requests at a time, so with many catalogs on one server the
            // later ones mostly wait their turn. That wait says nothing about the catalog.
            val crowdedHosts = searchTargets
                .groupingBy { (addon, _) -> hostOf(addon.baseUrl) }
                .eachCount()
                .filterValues { it > CATALOGS_PER_HOST_TIMED }
                .keys
            val jobs = searchTargets.mapIndexed { catalogIndex, (addon, catalog) ->
                launch {
                    val typingWaitMs = when {
                        catalogSpeed.isSlow(SearchCatalogSpeed.key(addon.baseUrl, catalog.apiType, catalog.id)) ->
                            SLOW_CATALOG_TYPING_WAIT_MS
                        ServerCatalog.isServerAddon(addon.baseUrl) -> SERVER_TYPING_WAIT_MS
                        else -> 0L
                    }
                    if (typingWaitMs > 0) withTimeoutOrNull(typingWaitMs) { gate.await() }
                    loadCatalog(
                        addon, catalog, query, generation, catalogIndex,
                        timed = hostOf(addon.baseUrl) !in crowdedHosts
                    )
                }
            }
            activeSearchJobs = jobs

            val updatingLabels = launch {
                delay(UPDATING_LABEL_DELAY_MS)
                if (isCurrentSearch(generation, query)) {
                    updatingLabelsShown = true
                    updateCatalogRowsNow()
                }
            }
            val focusGrace = launch {
                delay(FOCUS_GRACE_MS)
                if (isCurrentSearch(generation, query)) {
                    focusGraceElapsed = true
                    updateCatalogRowsNow()
                }
            }
            val settleCap = launch {
                delay(SEARCH_SETTLE_CAP_MS)
                if (isCurrentSearch(generation, query)) {
                    settleSuggestions(query)
                    // With nothing to show yet, the skeleton stays rather than a "no results" that
                    // a late answer would contradict.
                    if (catalogsMap.values.any { it.items.isNotEmpty() }) {
                        searchSettled = true
                        _uiState.update { it.copy(isSearching = false) }
                        updateCatalogRowsNow()
                    }
                }
            }

            // Wait for all jobs to complete so we can stop showing the global loading state.
            try {
                jobs.joinAll()
            } finally {
                updatingLabels.cancel()
                focusGrace.cancel()
                settleCap.cancel()
                if (
                    generation == searchGeneration &&
                    activeSearchQuery == query &&
                    uiState.value.submittedQuery.trim() == query
                ) {
                    if (lastRequestKey == requestKey) lastCompletedRequestKey = requestKey
                    searchSettled = true
                    _uiState.update { it.copy(isSearching = false) }
                    updateCatalogRowsNow()
                    settleSuggestions(query)
                    // Only explicit submit (or moving into results from the text input — see
                    // rememberSearchFromTextInput)
                    // writes history. Live search fires per keystroke and would otherwise record
                    // every prefix ("do", "dog") even when the user never confirmed the search.
                    if (
                        (rememberToHistory || historyQuery == query) &&
                        catalogsMap.values.any { row -> row.items.isNotEmpty() }
                    ) {
                        viewModelScope.launch {
                            searchHistoryDataStore.saveRecentSearch(query, MAX_RECENT_SEARCHES)
                        }
                    }
                }
            }
        }
        searchRunJob = job
    }

    private suspend fun loadCatalog(
        addon: Addon,
        catalog: CatalogDescriptor,
        query: String,
        generation: Long,
        catalogIndex: Int,
        timed: Boolean
    ) {
        val supportsSkip = catalog.supportsExtra("skip")
        val skipStep = catalog.skipStep()
        val key = catalogKey(
            addonId = addon.id,
            addonBaseUrl = addon.baseUrl,
            type = catalog.apiType,
            catalogId = catalog.id
        )
        val speedKey = SearchCatalogSpeed.key(addon.baseUrl, catalog.apiType, catalog.id)
        val started = catalogSpeed.markNow()
        var answered = false
        try {
            catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalog.id,
                catalogName = catalog.name,
                type = catalog.apiType,
                skip = 0,
                skipStep = skipStep,
                extraArgs = mapOf("search" to query),
                supportsSkip = supportsSkip,
                posterScreen = com.nuvio.tv.core.poster.CustomPosterScreen.SEARCH
            ).collect { result ->
                if (result !is NetworkResult.Loading && !answered) {
                    answered = true
                    if (timed) catalogSpeed.recordAnswer(speedKey, started.elapsedNow().inWholeMilliseconds)
                }
                when (result) {
                    is NetworkResult.Success -> {
                        if (!isCurrentSearch(generation, query)) return@collect
                        val ratingLimits = com.nuvio.tv.core.ratinglimit.ratingLimitGateOrNull(context)
                        val limit = ratingLimits?.let { gate -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { gate.activeLimit() } }
                        val row = if (limit == null || ratingLimits == null) result.data else result.data.copy(
                            items = ratingLimits.keepFitting(limit, result.data.items) { it.id to it.apiType }
                        )
                        if (!isCurrentSearch(generation, query)) return@collect
                        catalogsMap[key] = row
                        answeredKeys += key
                        if (feedSuggestions) {
                            offerSuggestions(catalogIndex, result.data.items.map { it.name }, query)
                        }
                        scheduleCatalogRowsUpdate()
                    }
                    is NetworkResult.Error -> {
                        if (!isCurrentSearch(generation, query)) return@collect
                        answeredKeys += key
                        // Ignore per-catalog errors unless we have nothing to show.
                        if (catalogsMap.isEmpty()) {
                            _uiState.update { it.copy(error = result.message ?: context.getString(com.nuvio.tv.R.string.search_error_failed)) }
                        }
                        scheduleCatalogRowsUpdate()
                    }
                    NetworkResult.Loading -> {
                        // No-op; screen shows global loading when empty.
                    }
                }
            }
        } catch (e: CancellationException) {
            if (!answered && timed) catalogSpeed.recordUnanswered(speedKey, started.elapsedNow().inWholeMilliseconds)
            throw e
        }
    }

    private fun isCurrentSearch(generation: Long, query: String): Boolean =
        generation == searchGeneration && uiState.value.submittedQuery.trim() == query

    /**
     * Pages one row of the current search. The page belongs to the search run that created it:
     * it is keyed on [SearchUiState.submittedQuery] rather than the live field, and every
     * assignment back into [catalogsMap] is gated on that run still being current, so a late page
     * cannot merge into a different query's rows.
     */
    private fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) {
        val (key, currentRow) = catalogsMap.entries.firstOrNull { (_, row) ->
            row.addonId == addonId && row.apiType == type && row.catalogId == catalogId
        }?.let { it.key to it.value } ?: return

        if (currentRow.isLoading || !currentRow.hasMore) {
            return
        }

        val generation = searchGeneration
        val query = uiState.value.submittedQuery.trim()
        if (query.isBlank()) {
            return
        }

        catalogsMap[key] = currentRow.copy(isLoading = true)
        scheduleCatalogRowsUpdate()

        val job = viewModelScope.launch {
            val addon = uiState.value.installedAddons.find { it.id == addonId && it.baseUrl == currentRow.addonBaseUrl }
                ?: uiState.value.installedAddons.find { it.id == addonId } ?: run {
                clearRowLoading(key, generation, query)
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
                extraArgs = mapOf("search" to query),
                supportsSkip = currentRow.supportsSkip,
                posterScreen = com.nuvio.tv.core.poster.CustomPosterScreen.SEARCH
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        if (!isCurrentSearch(generation, query)) return@collect
                        val latestRow = catalogsMap[key] ?: return@collect
                        catalogsMap[key] = latestRow.mergeCatalogPage(result.data)
                        scheduleCatalogRowsUpdate()
                    }
                    is NetworkResult.Error -> {
                        clearRowLoading(key, generation, query)
                    }
                    NetworkResult.Loading -> Unit
                }
            }
        }
        activeLoadMoreJobs.add(job)
        job.invokeOnCompletion { activeLoadMoreJobs.remove(job) }
    }

    /** Drops the loading flag for a row, but only while its search run is still current. */
    private fun clearRowLoading(key: String, generation: Long, query: String) {
        if (!isCurrentSearch(generation, query)) return
        val row = catalogsMap[key] ?: return
        if (!row.isLoading) return
        catalogsMap[key] = row.copy(isLoading = false)
        scheduleCatalogRowsUpdate()
    }

    /**
     * The first answer shows at once; after that the rows refresh at most every
     * [ROWS_UPDATE_INTERVAL_MS]. A pending refresh is not restarted by later answers, which could
     * otherwise hold back rows that have already arrived while answers keep coming in.
     */
    private fun scheduleCatalogRowsUpdate() {
        if (catalogRowsUpdateJob?.isActive == true) return
        catalogRowsUpdateJob = viewModelScope.launch {
            if (!hasRenderedFirstCatalog && catalogsMap.isNotEmpty()) {
                hasRenderedFirstCatalog = true
                updateCatalogRowsNow()
                return@launch
            }
            delay(ROWS_UPDATE_INTERVAL_MS)
            updateCatalogRowsNow()
        }
    }

    private fun updateCatalogRowsNow() {
        _uiState.update { state ->
            // A catalog that has not answered this query yet keeps what it showed before. Once it has
            // answered, or the run has settled, that older row goes: it belongs to another query.
            val updatingKeys = mutableSetOf<String>()
            val orderedRows = catalogOrder.map { key ->
                catalogsMap[key]
                    ?: state.catalogRows.find {
                        it.stableKey() == key
                    }?.takeIf { key !in answeredKeys && !searchSettled }
                        ?.also { row ->
                            val isPlaceholder = row.items.firstOrNull()?.id?.startsWith("__placeholder_") == true
                            if ((updatingLabelsShown || key in carriedUpdatingKeys) && !isPlaceholder) updatingKeys += key
                        }
            }.filterNotNull().filter { row ->
                // Keep placeholder rows (shimmer) and rows with real items.
                // Drop rows that came back empty from the API.
                val isPlaceholder = row.isLoading &&
                    row.items.firstOrNull()?.id?.startsWith("__placeholder_") == true
                isPlaceholder || row.items.isNotEmpty()
            }
            val filteredRows = if (hideUnreleasedContent) {
                val today = LocalDate.now()
                orderedRows.map { row ->
                    if (row.isLoading && row.items.firstOrNull()?.id?.startsWith("__placeholder_") == true) {
                        row
                    } else {
                        row.filterReleasedItems(today)
                    }
                }
            } else {
                orderedRows
            }
            val targetKey = runQuery
                ?.takeIf { it == state.submittedQuery.trim() && it == activeSearchQuery }
                ?.let { focusTargetKey(filteredRows) }
            state.copy(
                catalogRows = filteredRows,
                focusReadyQuery = if (targetKey != null) runQuery.orEmpty() else "",
                focusTargetRowKey = targetKey,
                updatingRowKeys = updatingKeys
            )
        }
    }

    /**
     * The first row holding this run's own results, once nothing can still land above it or the
     * grace period is over; null before that. A later row may then appear above it.
     */
    private fun focusTargetKey(rows: List<CatalogRow>): String? {
        val rowsWithTitles = rows.asSequence()
            .filter { it.items.isNotEmpty() }
            .map { it.stableKey() }
            .toSet()
        val firstReal = catalogOrder.indexOfFirst { key -> key in catalogsMap && key in rowsWithTitles }
        if (firstReal < 0) return null
        val ready = searchSettled || focusGraceElapsed || catalogOrder.take(firstReal).all { it in answeredKeys }
        return if (ready) catalogOrder[firstReal] else null
    }

    private suspend fun searchAddons(): List<Addon> =
        addonRepository.getInstalledAddons().first().enabledAddons() + serverCatalog.searchAddons.first()

    private suspend fun loadDiscoverCatalogs() {
        if (_uiState.value.discoverLocation == DiscoverLocation.OFF) return
        _uiState.update { it.copy(discoverLoading = true) }
        val addons = try {
            searchAddons()
        } catch (_: Exception) {
            _uiState.update { it.copy(discoverInitialized = true, discoverLoading = false) }
            return
        }

        val discoverCatalogs = addons.flatMap { addon ->
            addon.catalogs
                .filter { catalog ->
                    !(catalog.supportsExtra("search") &&
                        catalog.extra.any { it.name.equals("search", ignoreCase = true) && it.isRequired })
                }
                .map { catalog ->
                    val genres = catalog.extra
                        .firstOrNull { it.name.equals("genre", ignoreCase = true) }
                        ?.options
                        .orEmpty()
                    val server = ServerCatalog.isServerAddon(addon.baseUrl)
                    DiscoverCatalog(
                        key = catalogRowLegacyKey(addon.id, catalog.apiType, catalog.id),
                        addonId = addon.id,
                        addonName = addon.displayName,
                        addonBaseUrl = addon.baseUrl,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.apiType,
                        genres = genres,
                        supportsSkip = catalog.supportsExtra("skip"),
                        skipStep = catalog.skipStep(),
                        letters = if (server) catalog.extra.firstOrNull { it.name == ServerCatalog.LETTER_EXTRA }?.options.orEmpty() else emptyList(),
                        offersUnwatched = server && catalog.supportsExtra(ServerCatalog.UNWATCHED_EXTRA)
                    )
                }
        }

        val selectedCatalog = resolveDiscoverCatalog(
            catalogs = discoverCatalogs,
            preferredKey = discoverSelectionDataStore.getSelectedCatalogKey(),
            currentKey = _uiState.value.selectedDiscoverCatalogKey
        )
        val selectedType = selectedCatalog?.type ?: "movie"
        val selectedGenre: String? = null

        _uiState.update {
            it.copy(
                installedAddons = addons,
                discoverCatalogs = discoverCatalogs,
                selectedDiscoverType = selectedType,
                selectedDiscoverCatalogKey = selectedCatalog?.key,
                selectedDiscoverGenre = selectedGenre,
                selectedDiscoverLetter = null,
                discoverUnwatchedOnly = false,
                discoverInitialized = true,
                discoverLoading = false,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverHasMore = true,
                discoverPage = 1
            )
        }
        selectedCatalog?.let { catalog ->
            viewModelScope.launch {
                discoverSelectionDataStore.setSelectedCatalogKey(catalog.key)
            }
        }
        fetchDiscoverContent(reset = true)
    }

    private fun selectDiscoverType(type: String) {
        val catalogs = _uiState.value.discoverCatalogs
        val selectedCatalog = pickDiscoverCatalog(
            catalogs = catalogs,
            selectedType = type,
            preferredKey = _uiState.value.selectedDiscoverCatalogKey
        )
        val selectedGenre: String? = null
        _uiState.update {
            it.copy(
                selectedDiscoverType = type,
                selectedDiscoverCatalogKey = selectedCatalog?.key,
                selectedDiscoverGenre = selectedGenre,
                selectedDiscoverLetter = null,
                discoverUnwatchedOnly = false,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverPage = 1,
                discoverHasMore = true
            )
        }
        selectedCatalog?.let { catalog ->
            viewModelScope.launch {
                discoverSelectionDataStore.setSelectedCatalogKey(catalog.key)
            }
        }
        fetchDiscoverContent(reset = true)
    }

    private fun selectDiscoverCatalog(catalogKey: String) {
        val catalog = _uiState.value.discoverCatalogs.firstOrNull { it.key == catalogKey } ?: return
        _uiState.update {
            it.copy(
                selectedDiscoverCatalogKey = catalog.key,
                selectedDiscoverType = catalog.type,
                selectedDiscoverGenre = null,
                selectedDiscoverLetter = null,
                discoverUnwatchedOnly = false,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverPage = 1,
                discoverHasMore = true
            )
        }
        viewModelScope.launch {
            discoverSelectionDataStore.setSelectedCatalogKey(catalog.key)
        }
        fetchDiscoverContent(reset = true)
    }

    private fun selectDiscoverGenre(genre: String?) {
        _uiState.update {
            it.copy(
                selectedDiscoverGenre = genre,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverPage = 1,
                discoverHasMore = true
            )
        }
        fetchDiscoverContent(reset = true)
    }

    private fun selectDiscoverFilter(change: (SearchUiState) -> SearchUiState) {
        _uiState.update {
            change(it).copy(
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverPage = 1,
                discoverHasMore = true
            )
        }
        fetchDiscoverContent(reset = true)
    }

    private fun loadNextDiscoverResults() {
        if (_uiState.value.pendingDiscoverResults.isNotEmpty()) {
            showMoreDiscoverResults()
        } else {
            revealBatchAfterNextDiscoverFetch = true
            loadMoreDiscoverResults()
        }
    }

    private fun showMoreDiscoverResults() {
        val pending = _uiState.value.pendingDiscoverResults
        if (pending.isEmpty()) return
        val nextBatch = pending.take(DISCOVER_SHOW_MORE_BATCH)
        val remaining = pending.drop(DISCOVER_SHOW_MORE_BATCH)
        _uiState.update {
            it.copy(
                discoverResults = it.discoverResults + nextBatch,
                pendingDiscoverResults = remaining
            )
        }
    }

    private fun loadMoreDiscoverResults() {
        val state = _uiState.value
        if (state.query.trim().isNotEmpty()) return
        if (!state.discoverHasMore || state.discoverLoadingMore || state.pendingDiscoverResults.isNotEmpty()) return
        fetchDiscoverContent(reset = false)
    }

    private fun fetchDiscoverContent(reset: Boolean) {
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch {
            val state = _uiState.value
            if (state.query.trim().isNotEmpty()) return@launch
            val selectedCatalog = state.discoverCatalogs.firstOrNull { it.key == state.selectedDiscoverCatalogKey }
                ?: return@launch

            if (reset) {
                revealBatchAfterNextDiscoverFetch = false
                _uiState.update {
                    it.copy(
                        discoverLoading = true,
                        discoverResults = emptyList(),
                        pendingDiscoverResults = emptyList(),
                        discoverPage = 1,
                        discoverHasMore = true
                    )
                }
            } else {
                _uiState.update { it.copy(discoverLoadingMore = true) }
            }

            val currentPage = if (reset) 1 else state.discoverPage + 1
            val skip = if (currentPage <= 1) 0 else (currentPage - 1) * selectedCatalog.skipStep
            val visibleCountBeforeRequest = state.discoverResults.size
            val extraArgs = buildMap<String, String> {
                state.selectedDiscoverGenre?.takeIf { it.isNotBlank() }?.let { put("genre", it) }
                state.selectedDiscoverLetter?.takeIf { it in selectedCatalog.letters }?.let { put(ServerCatalog.LETTER_EXTRA, it) }
                if (state.discoverUnwatchedOnly && selectedCatalog.offersUnwatched) put(ServerCatalog.UNWATCHED_EXTRA, "true")
            }

            catalogRepository.getCatalog(
                addonBaseUrl = selectedCatalog.addonBaseUrl,
                addonId = selectedCatalog.addonId,
                addonName = selectedCatalog.addonName,
                catalogId = selectedCatalog.catalogId,
                catalogName = selectedCatalog.catalogName,
                type = selectedCatalog.type,
                skip = skip,
                skipStep = selectedCatalog.skipStep,
                extraArgs = extraArgs,
                supportsSkip = selectedCatalog.supportsSkip,
                posterScreen = com.nuvio.tv.core.poster.CustomPosterScreen.SEARCH
            ).collect { result ->
                if (_uiState.value.discoverLocation == DiscoverLocation.OFF) return@collect
                when (result) {
                    is NetworkResult.Success -> {
                        val incoming = result.data.items
                        val existing = if (reset) {
                            emptyList()
                        } else {
                            _uiState.value.discoverResults + _uiState.value.pendingDiscoverResults
                        }
                        val existingKeys = existing.asSequence()
                            .map { "${it.apiType}:${it.id}" }
                            .toSet()
                        val hasNewUniqueIncoming = incoming.any { item ->
                            "${item.apiType}:${item.id}" !in existingKeys
                        }
                        val merged = if (reset) incoming else (existing + incoming)
                        val rawDeduped = merged.distinctBy { "${it.apiType}:${it.id}" }
                        val deduped = if (hideUnreleasedContent) {
                            val today = LocalDate.now()
                            rawDeduped.filterNot { it.isUnreleased(today) }
                        } else {
                            rawDeduped
                        }
                        val shouldRevealBatch = !reset && revealBatchAfterNextDiscoverFetch
                        val visibleLimit = if (reset) {
                            DISCOVER_INITIAL_LIMIT
                        } else if (shouldRevealBatch) {
                            (visibleCountBeforeRequest + DISCOVER_SHOW_MORE_BATCH)
                                .coerceAtLeast(DISCOVER_INITIAL_LIMIT)
                        } else {
                            visibleCountBeforeRequest.coerceAtLeast(DISCOVER_INITIAL_LIMIT)
                        }
                        val visible = deduped.take(visibleLimit)
                        val pending = deduped.drop(visibleLimit)
                        val shouldStopPagination = !reset && !hasNewUniqueIncoming
                        _uiState.update {
                            it.copy(
                                discoverLoading = false,
                                discoverLoadingMore = false,
                                discoverResults = visible,
                                pendingDiscoverResults = pending,
                                discoverHasMore = if (shouldStopPagination) false else result.data.hasMore,
                                discoverPage = if (shouldStopPagination) it.discoverPage else currentPage
                            )
                        }
                        revealBatchAfterNextDiscoverFetch = false
                    }
                    is NetworkResult.Error -> {
                        revealBatchAfterNextDiscoverFetch = false
                        _uiState.update {
                            it.copy(
                                discoverLoading = false,
                                discoverLoadingMore = false,
                                discoverHasMore = false
                            )
                        }
                    }
                    NetworkResult.Loading -> Unit
                }
            }
        }
    }

    private fun pickDiscoverCatalog(
        catalogs: List<DiscoverCatalog>,
        selectedType: String,
        preferredKey: String?
    ): DiscoverCatalog? {
        val filtered = catalogs.filter { it.type == selectedType }
        return filtered.firstOrNull { it.key == preferredKey } ?: filtered.firstOrNull()
    }

    private fun buildSearchTargets(addons: List<Addon>): List<Pair<Addon, CatalogDescriptor>> {
        val allSearchTargets = addons.flatMap { addon ->
            addon.catalogs
                .filter { catalog -> catalog.isSearchable() }
                .map { catalog -> addon to catalog }
        }

        // A catalog listed twice would be asked twice and give the screen two rows with one key.
        return allSearchTargets.distinctBy { (addon, catalog) ->
            catalogKey(addon.id, addon.baseUrl, catalog.apiType, catalog.id)
        }
    }

    /**
     * A catalog is only searchable if a search is all it needs. One that also requires something we
     * cannot supply, a mandatory genre for instance, answers with an error or nothing at all, so
     * querying it just costs a request per keystroke and leaves a row that never fills in.
     */
    private fun CatalogDescriptor.isSearchable(): Boolean =
        supportsExtra("search") &&
            extra.none { property ->
                property.isRequired && !property.name.equals("search", ignoreCase = true)
            }

    private fun hostOf(baseUrl: String): String =
        runCatching { java.net.URI(baseUrl).host }.getOrNull() ?: baseUrl

    private fun catalogKey(addonId: String, addonBaseUrl: String, type: String, catalogId: String): String {
        return catalogRowStableKey(addonId, addonBaseUrl, type, catalogId)
    }
}

internal fun resolveDiscoverCatalog(
    catalogs: List<DiscoverCatalog>,
    preferredKey: String?,
    currentKey: String?
): DiscoverCatalog? =
    catalogs.firstOrNull { it.key == preferredKey }
        ?: catalogs.firstOrNull { it.key == currentKey }
        ?: catalogs.firstOrNull()
