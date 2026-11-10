@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlinx.coroutines.FlowPreview::class
)

package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.mediaserver.DrawnArtworkWidths
import com.nuvio.tv.ui.v2.components.sidebarPageContent
import com.nuvio.tv.ui.v2.appearance.LocalUiScaleDecision
import com.nuvio.tv.ui.v2.scale.posterSizeDp

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.metrics.performance.PerformanceMetricsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil3.request.CachePolicy
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import kotlin.math.abs
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.zIndex
import com.nuvio.tv.R
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.ContinueWatchingOptionsDialog
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.ui.util.LocalRecompositionHighlighterEnabled
import com.nuvio.tv.ui.util.StableList
import com.nuvio.tv.ui.util.StableRef
import com.nuvio.tv.ui.util.asStable
import com.nuvio.tv.ui.util.formatHeroRuntime
import com.nuvio.tv.ui.util.recompositionHighlighter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Height of the wide card as a fraction of its width, matching the 2.5:1 shape of the mobile card.
private const val WIDE_CARD_HEIGHT_RATIO = 0.4f

/**
 * Vertical cache window for the Modern home rows list: keep one viewport of rows
 * composed ahead of the scroll direction and one behind. Value-equal instances are
 * treated as the same key by rememberLazyListState, but a single file-level
 * instance avoids re-allocating it on every recomposition.
 */
private val MODERN_HOME_ROWS_CACHE_WINDOW = LazyLayoutCacheWindow(
    aheadFraction = 1f,
    behindFraction = 1f
)

private data class ExpandFlowInputs(
    val focusKey: String?,
    val interactionNonce: Int,
    val verticalScrolling: Boolean,
    val sidebarExpanded: Boolean
)

private class ItemIdentitySnapshot(
    var byRow: Map<String, StableList<String>> = emptyMap()
)

internal fun findRelocatedItemIndex(
    previousIdentities: List<String>?,
    currentIdentities: List<String>,
    storedIndex: Int?
): Int? {
    if (storedIndex == null) return null
    val previousIdentity = previousIdentities?.getOrNull(storedIndex) ?: return null
    return currentIdentities.indexOf(previousIdentity).takeIf { it >= 0 }
}

/** Where a row scrolls so its focused card keeps its place on screen after the card moved to another index. */
internal fun scrollKeepingFocusedSlot(
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    oldFocusedIndex: Int,
    newFocusedIndex: Int
): Pair<Int, Int> {
    val first = newFocusedIndex - (oldFocusedIndex - firstVisibleIndex).coerceAtLeast(0)
    return if (first < 0) 0 to 0 else first to firstVisibleOffset
}

@Composable
fun ModernHomeContent(
    uiState: HomeUiState,
    modernPresentation: ModernHomePresentationState = ModernHomePresentationState(),
    focusState: HomeScreenFocusState,
    enrichingItemId: () -> String? = { null },
    enrichedPreviews: () -> Map<String, MetaPreview> = { emptyMap() },
    failedEnrichmentIds: () -> Set<String> = { emptySet() },
    heroSeriesFacts: () -> Map<String, HeroSeriesFacts> = { emptyMap() },
    onSeriesHeroSettled: (HeroSeriesRef) -> Unit = {},
    trailerPreviewUrls: Map<String, String> = emptyMap(),
    trailerPreviewAudioUrls: Map<String, String> = emptyMap(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = {},
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit = {},
    showContinueWatchingManualPlayOption: Boolean = false,
    onRequestTrailerPreview: (String, String, String?, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onServerArtworkWindow: (List<Pair<String, List<MetaPreview>>>) -> Unit = {},
    onRemoveContinueWatching: (String, Int?, Int?, Boolean) -> Unit,
    onHideFromComingUp: (ContinueWatchingItem) -> Unit = {},
    onMarkContinueWatchingWatched: (ContinueWatchingItem) -> Unit = {},
    isCatalogItemWatched: (MetaPreview) -> Boolean = { false },
    onCatalogItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    onItemFocus: (MetaPreview) -> Unit = {},
    onPreloadAdjacentItem: (MetaPreview) -> Unit = {},
    onSaveFocusState: (Int, Int, String?, Map<String, String>, Map<String, Int>, Map<String, String>, Int, Int) -> Unit,
    onSaveRowScrollOffsets: (Map<String, Int>) -> Unit = {},
    onFocusedRowKeyChanged: (String?) -> Unit = {},
    scrollToTopTrigger: Int = 0,
    onRequestLazyCatalogLoad: (String) -> Unit = {},
    onRowItemFocusedCallback: (String, Int, Boolean) -> Unit = { _, _, _ -> },
    blockLeftOnFirstExpandedItem: Boolean = false
) {
    val onRowItemFocusedPassedDown = rememberUpdatedState(onRowItemFocusedCallback)
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val sidebarExpanded = LocalSidebarExpanded.current
    val isSidebarExpanded = remember(sidebarExpanded) { derivedStateOf { sidebarExpanded } }
    val latestSidebarExpanded = rememberUpdatedState(sidebarExpanded)
    val lifecycleOwner by rememberUpdatedState(LocalLifecycleOwner.current)
    val useLandscapePosters = uiState.modernLandscapePostersEnabled
    val v2Appearance = com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current
    val isV2 = v2Appearance != null
    val alwaysShowLandscapeClearlogo = uiState.alwaysShowLandscapeClearlogo
    val addonLandscapeBackdropLogo = uiState.addonLandscapeBackdropLogo
    val fullScreenBackdrop = uiState.modernHeroFullScreenBackdropEnabled
    val artworkAccent = com.nuvio.tv.ui.v2.appearance.LocalArtworkAccent.current
    val trailerPlaybackTarget = uiState.focusedPosterBackdropTrailerPlaybackTarget
    val effectiveAutoplayEnabled =
        uiState.focusedPosterBackdropTrailerEnabled &&
            (trailerPlaybackTarget != FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD ||
                useLandscapePosters || uiState.focusedPosterBackdropExpandEnabled)
    val landscapeExpandedCardMode =
        useLandscapePosters &&
            effectiveAutoplayEnabled &&
            trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD
    val effectiveExpandEnabled =
        (uiState.focusedPosterBackdropExpandEnabled && !useLandscapePosters) || landscapeExpandedCardMode
    val shouldActivateFocusedPosterFlow =
        effectiveExpandEnabled ||
            (effectiveAutoplayEnabled &&
                trailerPlaybackTarget != FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD)
    val presentation = modernPresentation
    val carouselRows = presentation.rows

    val hasCollections = remember(uiState.homeRows) {
        uiState.homeRows.any { it is HomeRow.CollectionRow }
    }
    val wallContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(uiState.catalogRows) {
        if (isV2) withContext(Dispatchers.IO) {
            // A synced profile may contain thousands of titles. Only walk far enough
            // to fill the 24-poster wall; keep preferences/JSON work off the UI thread.
            val posters = uiState.catalogRows.asSequence().flatMap { it.items.asSequence() }
                .mapNotNull { it.poster }.filter(String::isNotBlank).distinct().take(24).toList()
            com.nuvio.tv.ui.v2.profile.ProfilePosterWall.rememberPosters(wallContext, posters)
        }
    }
    val hasCatalogs = uiState.catalogRows.isNotEmpty()
    if (hasCollections && !hasCatalogs && uiState.installedAddonsCount > 0 && uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }

    if (carouselRows.list.isEmpty()) {
        if (uiState.heroSectionEnabled && uiState.heroItems.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                com.nuvio.tv.ui.components.HeroCarousel(
                    items = uiState.heroItems.asStable(),
                    showImdbRatings = uiState.homeImdbRatingsVisibility.showRatings,
                    descriptionAutoScrollEnabled = uiState.heroDescriptionAutoScrollEnabled,
                    onItemClick = { item ->
                        onNavigateToDetail(item.id, item.apiType, "")
                    },
                    onItemFocus = { item -> onItemFocus(item) }
                )
            }
        }
        return
    }

    val carouselLookups = presentation.lookups
    val rowIndexByKey = carouselLookups.rowIndexByKey
    val rowByKey = carouselLookups.rowByKey
    val activeRowKeys = carouselLookups.activeRowKeys
    val activeCatalogItemIds = carouselLookups.activeCatalogItemIds

    // Pre-compose rows one viewport ahead and one behind during idle frames, so a
    // DPAD_DOWN/UP press never composes the incoming row's cards on the press
    // frame (that first composition costs a ~150 ms vertical hitch).
    // Symmetric to match the symmetric poster prewarm in ModernHomeRowsList.
    val verticalRowListState = rememberLazyListState(
        cacheWindow = MODERN_HOME_ROWS_CACHE_WINDOW,
        initialFirstVisibleItemIndex = focusState.verticalScrollIndex,
        initialFirstVisibleItemScrollOffset = focusState.verticalScrollOffset
    )
    val isVerticalRowsScrollingState = remember(verticalRowListState) {
        derivedStateOf { verticalRowListState.isScrollInProgress }
    }

    val rowListStates = remember { mutableStateMapOf<String, LazyListState>() }
    val loadMoreRequestedTotals = remember { mutableStateMapOf<String, Int>() }

    val initialFocus = remember { resolveModernHomeInitialFocus(carouselRows.list, focusState) }
    val focusedItemByRow = remember {
        mutableStateMapOf<String, Int>().apply {
            initialFocus?.let { (row, index) -> put(row.key, index) }
        }
    }
    val itemIdentitySnapshot = remember { ItemIdentitySnapshot() }
    val stableFocusedItemByRow = remember { StableRef<MutableMap<String, Int>>(focusedItemByRow) }
    val stableRowListStates = remember { StableRef<MutableMap<String, LazyListState>>(rowListStates) }
    val stableLoadMoreRequestedTotals = remember { StableRef<MutableMap<String, Int>>(loadMoreRequestedTotals) }
    if (focusState.hasSavedFocus && Snapshot.withoutReadObservation { focusedItemByRow.isEmpty() }) {
        // Every row is saved, so restore every row, not only the one that held focus.
        val rowsByKey = carouselRows.list.associateBy { it.key }
        focusState.focusedItemKeyByRow.forEach { (rowKey, savedItemKey) ->
            if (savedItemKey.isBlank()) return@forEach
            val row = rowsByKey[rowKey] ?: return@forEach
            val itemIndex = row.items.list.indexOfFirst { it.key == savedItemKey }
            if (itemIndex >= 0) focusedItemByRow[rowKey] = itemIndex
        }
    }

    val focusHolder = remember {
        object {
            var activeRowKey: String? = initialFocus?.first?.key
            var activeItemIndex: Int = initialFocus?.second ?: 0
        }
    }
    val activeRowKey = remember { mutableStateOf(initialFocus?.first?.key) }
    val activeItemIndex = remember { mutableIntStateOf(initialFocus?.second ?: 0) }
    val pendingRowFocusKey = remember { mutableStateOf(initialFocus?.first?.key) }
    val pendingRowFocusIndex = remember { mutableStateOf(initialFocus?.second) }
    val pendingRowFocusNonce = remember { mutableIntStateOf(if (initialFocus != null) 1 else 0) }
    val restoredFromSavedState = remember { mutableStateOf(initialFocus != null) }
    val settledHeroSelection = remember {
        mutableStateOf(initialFocus?.let { (row, index) -> row.heroSelectionAt(index) })
    }
    val optionsItem = remember { mutableStateOf<ContinueWatchingItem?>(null) }
    val optionsCard = remember { mutableStateOf<Pair<String, Int>?>(null) }
    val lastFocusedContinueWatchingIndex = remember { mutableIntStateOf(-1) }
    val lastHeroNavigationAtMs = remember { mutableLongStateOf(0L) }
    val heroFocusSettleDelayMs = remember { mutableLongStateOf(MODERN_HERO_FOCUS_DEBOUNCE_MS) }
    val isFastScrolling = remember { mutableStateOf(false) }
    val isRapidHorizontalNav = remember { mutableStateOf(false) }
    val focusedCatalogSelection = remember { mutableStateOf<FocusedCatalogSelection?>(null) }
    var lastRequestedTrailerFocusKey by remember { mutableStateOf<String?>(null) }
    val expandedCatalogFocusKey = remember { mutableStateOf<String?>(null) }
    val focusedHeroMediaNonce = remember { mutableIntStateOf(0) }
    var endedCollectionHeroVideoPlaybackKey by remember { mutableStateOf<String?>(null) }
    val expansionInteractionNonce = remember { mutableIntStateOf(0) }

    // Improved Back navigation: when focused item is not the first in a row,
    // scroll the row to the start and focus the first item instead of opening the sidebar.
    // Disabled when the sidebar owns focus (expanded) so Back can exit the app.
    val backScrollScope = rememberCoroutineScope()
    val contentHasFocus = remember { mutableStateOf(false) }
    val fullscreenTrailerPlaying = remember { mutableStateOf(false) }
    val fullscreenTrailerDismiss = remember { mutableStateOf<(() -> Unit)?>(null) }
    val shouldInterceptBack = remember {
        derivedStateOf {
            if (!contentHasFocus.value) return@derivedStateOf false
            if (fullscreenTrailerPlaying.value) return@derivedStateOf true
            val rowKey = activeRowKey.value ?: return@derivedStateOf false
            val itemIndex = focusedItemByRow[rowKey] ?: 0
            itemIndex > 0
        }
    }
    BackHandler(enabled = shouldInterceptBack.value) {
        if (fullscreenTrailerPlaying.value) {
            fullscreenTrailerDismiss.value?.invoke()
            return@BackHandler
        }
        val rowKey = activeRowKey.value ?: return@BackHandler
        val listState = rowListStates[rowKey]
        focusedItemByRow[rowKey] = 0
        pendingRowFocusKey.value = rowKey
        pendingRowFocusIndex.value = 0
        pendingRowFocusNonce.intValue++
        backScrollScope.launch {
            listState?.scrollToItem(0, 0)
        }
    }

    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) {
            verticalRowListState.scrollToItem(0, 0)
        }
    }

    val currentView = LocalView.current
    val metricsHolder = PerformanceMetricsState.getHolderForHierarchy(currentView)
    LaunchedEffect(verticalRowListState) {
        snapshotFlow { verticalRowListState.isScrollInProgress }
            .collect { scrolling ->
                metricsHolder.state?.putState("HomeScrolling", scrolling.toString())
            }
    }
    LaunchedEffect(enrichingItemId) {
        snapshotFlow { enrichingItemId() != null }.collect { enriching ->
            metricsHolder.state?.putState("HeroEnriching", enriching.toString())
        }
    }

    LaunchedEffect(
        shouldActivateFocusedPosterFlow,
        trailerPlaybackTarget,
        uiState.focusedPosterBackdropExpandDelaySeconds
    ) {
        // Re-run when the sidebar takes/returns focus so a pending expand cannot
        // complete while the user is on the Home button (#2815).
        snapshotFlow {
            ExpandFlowInputs(
                focusedCatalogSelection.value?.focusKey,
                expansionInteractionNonce.intValue,
                verticalRowListState.isScrollInProgress,
                latestSidebarExpanded.value
            )
        }.collectLatest { inputs ->
            // Always clear first so sidebar open / selection change collapses immediately.
            expandedCatalogFocusKey.value = null
            if (!shouldActivateFocusedPosterFlow) return@collectLatest
            if (inputs.sidebarExpanded || inputs.verticalScrolling) return@collectLatest
            val selection = focusedCatalogSelection.value ?: return@collectLatest
            if (selection.payload !is ModernPayload.Catalog) return@collectLatest
            val expansionDelayMs = (uiState.focusedPosterBackdropExpandDelaySeconds.coerceAtLeast(0) * 1000L).coerceAtLeast(150L)
            delay(expansionDelayMs)
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@collectLatest
            if (!latestSidebarExpanded.value &&
                !verticalRowListState.isScrollInProgress &&
                focusedCatalogSelection.value?.focusKey == selection.focusKey
            ) {
                expandedCatalogFocusKey.value = selection.focusKey
            }
        }
    }

    val latestOnRequestTrailerPreview by rememberUpdatedState(onRequestTrailerPreview)
    LaunchedEffect(effectiveAutoplayEnabled) {
        if (!effectiveAutoplayEnabled) {
            lastRequestedTrailerFocusKey = null
            return@LaunchedEffect
        }
        snapshotFlow {
            focusedCatalogSelection.value?.focusKey to verticalRowListState.isScrollInProgress
        }.collectLatest { (_, verticalScrolling) ->
            if (verticalScrolling) return@collectLatest
            val selection = focusedCatalogSelection.value ?: run {
                lastRequestedTrailerFocusKey = null
                return@collectLatest
            }
            val payload = selection.payload as? ModernPayload.Catalog ?: run {
                lastRequestedTrailerFocusKey = null
                return@collectLatest
            }
            if (selection.focusKey == lastRequestedTrailerFocusKey) return@collectLatest
            delay(150)
            if (focusedCatalogSelection.value?.focusKey != selection.focusKey) return@collectLatest
            latestOnRequestTrailerPreview(
                payload.itemId,
                payload.trailerTitle,
                payload.trailerReleaseInfo,
                payload.trailerApiType
            )
            lastRequestedTrailerFocusKey = selection.focusKey
        }
    }

    val currentItemIdentitiesByRow = carouselLookups.itemIdentitiesByRow.map
    if (itemIdentitySnapshot.byRow !== currentItemIdentitiesByRow) {
        currentItemIdentitiesByRow.forEach { (rowKey, currentIdentities) ->
            if (pendingRowFocusKey.value == rowKey) {
                findRelocatedItemIndex(
                    previousIdentities = itemIdentitySnapshot.byRow[rowKey]?.list,
                    currentIdentities = currentIdentities.list,
                    storedIndex = pendingRowFocusIndex.value
                )?.let { pendingRowFocusIndex.value = it }
            }
            val storedIndex = focusedItemByRow[rowKey]
            val relocatedIndex = findRelocatedItemIndex(
                previousIdentities = itemIdentitySnapshot.byRow[rowKey]?.list,
                currentIdentities = currentIdentities.list,
                storedIndex = storedIndex
            )
            if (relocatedIndex != null && relocatedIndex != storedIndex) {
                focusedItemByRow[rowKey] = relocatedIndex
                // The row keeps its first card when items move, so the focused card could slide off screen.
                if (rowKey == activeRowKey.value && storedIndex != null) {
                    rowListStates[rowKey]?.let { state ->
                        val (index, offset) = Snapshot.withoutReadObservation {
                            scrollKeepingFocusedSlot(
                                state.firstVisibleItemIndex,
                                state.firstVisibleItemScrollOffset,
                                storedIndex,
                                relocatedIndex
                            )
                        }
                        state.requestScrollToItem(index, offset)
                    }
                }
                // The hero reads its own index, synced by an effect keyed on the row size, so it
                // would land a frame late and show whatever took the old index meanwhile.
                if (rowKey == activeRowKey.value) {
                    focusHolder.activeItemIndex = relocatedIndex
                    activeItemIndex.intValue = relocatedIndex
                }
            }
        }
        itemIdentitySnapshot.byRow = currentItemIdentitiesByRow
    }

    LaunchedEffect(carouselRows, focusState.hasSavedFocus) {
        rowListStates.keys.retainAll(activeRowKeys)
        loadMoreRequestedTotals.keys.retainAll(activeRowKeys)
        val staleSelection = focusedCatalogSelection.value?.let { selection ->
            when (val payload = selection.payload) {
                is ModernPayload.Catalog -> !payload.itemId.startsWith("__placeholder_") && payload.itemId !in activeCatalogItemIds.set
                is ModernPayload.CollectionFolder -> carouselRows.list.none { row ->
                    row.items.list.any { item ->
                        (item.payload as? ModernPayload.CollectionFolder)?.focusKey == selection.focusKey
                    }
                }
                is ModernPayload.ContinueWatching -> true
            }
        } ?: false
        if (staleSelection) {
            focusedCatalogSelection.value = null
            expandedCatalogFocusKey.value = null
        }
        val currentSelection = focusedCatalogSelection.value
        val currentCatalogPayload = currentSelection?.payload as? ModernPayload.Catalog
        if (currentSelection != null && currentCatalogPayload != null && currentCatalogPayload.itemId.startsWith("__placeholder_")) {
            val activeKey = focusHolder.activeRowKey
            val activeIdx = focusHolder.activeItemIndex
            val activeRow = activeKey?.let { rowByKey.map[it] }
            val realItem = activeRow?.items?.list?.getOrNull(activeIdx)
            val realPayload = realItem?.payload as? ModernPayload.Catalog
            if (realPayload != null && !realPayload.itemId.startsWith("__placeholder_")) {
                focusedCatalogSelection.value = FocusedCatalogSelection(
                    focusKey = realPayload.focusKey,
                    payload = realPayload
                )
                lastRequestedTrailerFocusKey = null
                expandedCatalogFocusKey.value = null
            }
        }

        carouselRows.list.forEach { row ->
            if (row.items.list.isNotEmpty() && row.key !in focusedItemByRow) {
                focusedItemByRow[row.key] = 0
            }
        }

        if (!restoredFromSavedState.value && focusState.hasSavedFocus) {
            val restored = resolveModernHomeInitialFocus(carouselRows.list, focusState)
            if (restored != null) {
                val (resolvedRow, resolvedIndex) = restored
                focusHolder.activeRowKey = resolvedRow.key
                focusHolder.activeItemIndex = resolvedIndex
                activeRowKey.value = resolvedRow.key
                activeItemIndex.intValue = resolvedIndex
                focusedItemByRow[resolvedRow.key] = resolvedIndex
                settledHeroSelection.value = resolvedRow.heroSelectionAt(resolvedIndex)
                pendingRowFocusKey.value = resolvedRow.key
                pendingRowFocusIndex.value = resolvedIndex
                pendingRowFocusNonce.intValue++
                restoredFromSavedState.value = true
                return@LaunchedEffect
            }
        }

        val hadActiveRow = focusHolder.activeRowKey != null
        val existingActive = focusHolder.activeRowKey?.let { rowByKey[it] }
        val firstRow = carouselRows.list.firstOrNull()

        val resolvedActive = when {
            existingActive != null -> existingActive
            hadActiveRow -> null // Wait for data to reappear
            else -> firstRow     // Initial state
        }

        if (resolvedActive != null) {
            val resolvedIndex = focusedItemByRow[resolvedActive.key]
                ?.coerceIn(0, (resolvedActive.items.size - 1).coerceAtLeast(0))
                ?: 0
            focusHolder.activeRowKey = resolvedActive.key
            focusHolder.activeItemIndex = resolvedIndex
            activeRowKey.value = resolvedActive.key
            activeItemIndex.intValue = resolvedIndex
            focusedItemByRow[resolvedActive.key] = resolvedIndex
            // Catalogue/enrichment updates must not publish transient poster focus.
            if (settledHeroSelection.value == null) {
                settledHeroSelection.value = resolvedActive.heroSelectionAt(resolvedIndex)
            }

            if (!focusState.hasSavedFocus && !hadActiveRow) {
                pendingRowFocusKey.value = resolvedActive.key
                pendingRowFocusIndex.value = resolvedIndex
                pendingRowFocusNonce.intValue++
            }
        }

        if (!restoredFromSavedState.value && carouselRows.list.isNotEmpty()) {
            restoredFromSavedState.value = true
        }
    }

    LaunchedEffect(verticalRowListState) {
        val targetIndex = focusState.verticalScrollIndex
        val targetOffset = focusState.verticalScrollOffset
        if (verticalRowListState.firstVisibleItemIndex == targetIndex &&
            verticalRowListState.firstVisibleItemScrollOffset == targetOffset
        ) {
            return@LaunchedEffect
        }
        if (targetIndex > 0 || targetOffset > 0) {
            verticalRowListState.scrollToItem(targetIndex, targetOffset)
        }
    }

    val latestRowByKey = rememberUpdatedState(rowByKey)
    val activeRowState = remember {
        derivedStateOf {
            val activeKey = activeRowKey.value
            if (activeKey == null) null
            else latestRowByKey.value[activeKey]
        }
    }
    val clampedActiveItemIndexState = remember {
        derivedStateOf {
            activeRowState.value?.let { row ->
                activeItemIndex.intValue.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
            } ?: 0
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { activeRowState.value?.let { it.key to it.items.size } }.collect {
            val row = activeRowState.value ?: return@collect
            val savedIdx = focusedItemByRow[row.key] ?: 0
            val clampedIndex = savedIdx.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
            if (focusHolder.activeItemIndex != clampedIndex) {
                focusHolder.activeItemIndex = clampedIndex
                activeItemIndex.intValue = clampedIndex
            }
            focusedItemByRow[row.key] = clampedIndex
        }
    }

    val activeHeroItemKeyState = remember {
        derivedStateOf {
            val row = activeRowState.value ?: return@derivedStateOf null
            row.items.getOrNull(clampedActiveItemIndexState.value)?.key ?: row.items.firstOrNull()?.key
        }
    }

    // Detect rapid horizontal navigation (holding DPAD left/right).
    // Activates after two successive item changes within the SAME row within 300ms.
    val lastRapidNavRowKey = remember { mutableStateOf<String?>(null) }
    val lastRapidNavAtMs = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        snapshotFlow { activeHeroItemKeyState.value }.collectLatest { heroItemKey ->
            if (heroItemKey == null) return@collectLatest
            val currentRowKey = activeRowKey.value
            val now = android.os.SystemClock.uptimeMillis()
            val timeSinceLast = now - lastRapidNavAtMs.longValue
            // Only activate rapid nav if we're in the same row as last navigation
            if (lastRapidNavAtMs.longValue != 0L && timeSinceLast in 0..300 && lastRapidNavRowKey.value == currentRowKey) {
                isRapidHorizontalNav.value = true
            }
            lastRapidNavRowKey.value = currentRowKey
            lastRapidNavAtMs.longValue = now
            delay(heroFocusSettleDelayMs.longValue)
            isRapidHorizontalNav.value = false
        }
    }

    val latestOnHeroItemFocus by rememberUpdatedState(onItemFocus)
    val latestOnSeriesHeroSettled by rememberUpdatedState(onSeriesHeroSettled)
    LaunchedEffect(verticalRowListState) {
        snapshotFlow {
            ModernHeroFocus(
                activeRowState.value?.heroSelectionAt(clampedActiveItemIndexState.value),
                heroFocusSettleDelayMs.longValue
            )
        }.settledModernHeroSelections(snapshotFlow { verticalRowListState.isScrollInProgress })
            .collect { selection ->
                // Publish artwork/text and request enrichment from the same settled identity.
                settledHeroSelection.value = selection
                val settledItem = activeRowState.value?.takeIf { it.key == selection.rowKey }
                    ?.items?.firstOrNull { it.key == selection.itemKey }
                settledItem?.metaPreview?.let { latestOnHeroItemFocus(it) }
                settledItem?.heroSeriesRef()?.let { latestOnSeriesHeroSettled(it) }
            }
    }

    val latestCarouselRows by rememberUpdatedState(carouselRows)
    val latestVerticalRowListState by rememberUpdatedState(verticalRowListState)
    val latestRowIndexByKey = rememberUpdatedState(rowIndexByKey)
    val latestSavedScrollAnchors by rememberUpdatedState(focusState.catalogRowScrollAnchors)
    DisposableEffect(Unit) {
        onDispose {
            val row = activeRowState.value
            val focusedRowKey = row?.key
            // Only save focus state if home screen actually had focus (focusedRowKey is not null)
            // This prevents saving invalid state like row -1 when sidebar was open
            if (focusedRowKey == null) {
                // Home screen didn't have focus, don't overwrite the saved state
                return@onDispose
            }
            
            val focusedRowIndex = focusedRowKey?.let { latestRowIndexByKey.value.map[it] } ?: -1
            val focusedItemIndex = activeItemIndex.intValue
            
            val focusedItemKeyByRow = latestCarouselRows
                .associate { rowState ->
                    val focusedIdx = focusedItemByRow[rowState.key] ?: 0
                    val itemKey = rowState.items.list.getOrNull(focusedIdx)?.key ?: ""
                    rowState.key to itemKey
                }

            val catalogRowScrollStates = latestCarouselRows
                .associate { rowState ->
                    val scrollIndex = rowListStates[rowState.key]?.firstVisibleItemIndex
                        ?: (focusedItemByRow[rowState.key] ?: 0)
                    rowState.key to scrollIndex
                }

            // A row not composed since the return has no state: keep the anchor it came back with.
            val liveRowKeys = latestCarouselRows.map { it.key }.toSet()
            val catalogRowScrollAnchors = latestSavedScrollAnchors.filterKeys { it in liveRowKeys } + latestCarouselRows
                .mapNotNull { rowState ->
                    val state = rowListStates[rowState.key] ?: return@mapNotNull null
                    // The card last measured there: an off-screen row is not re-measured when items land in front.
                    val anchorKey = (state.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == state.firstVisibleItemIndex }?.key as? String)
                        ?.takeIf { key -> rowState.items.list.any { it.key == key } }
                        ?: rowState.items.list.getOrNull(state.firstVisibleItemIndex)?.key
                        ?: return@mapNotNull null
                    rowState.key to anchorKey
                }
                .toMap()

            // A row only scrolls as far as needed, so its first card is often partly off screen: keep that too.
            onSaveRowScrollOffsets(
                latestCarouselRows.mapNotNull { rowState ->
                    rowListStates[rowState.key]?.let { rowState.key to it.firstVisibleItemScrollOffset }
                }.toMap()
            )
            onSaveFocusState(
                latestVerticalRowListState.firstVisibleItemIndex,
                latestVerticalRowListState.firstVisibleItemScrollOffset,
                focusedRowKey,
                focusedItemKeyByRow,
                catalogRowScrollStates,
                catalogRowScrollAnchors,
                focusedRowIndex,
                focusedItemIndex
            )
        }
    }

    val uiScale = LocalUiScaleDecision.current
    val portraitBaseWidth = uiScale.posterSizeDp(uiState.posterCardWidthDp).dp
    val portraitBaseHeight = uiScale.posterSizeDp(uiState.posterCardHeightDp).dp
    val portraitModernPosterScale = 1.08f
    val landscapeModernPosterScale = 1.34f
    val portraitCatalogCardWidth = portraitBaseWidth * 0.84f * portraitModernPosterScale
    val drawnDensity = LocalDensity.current
    val drawnPosterPx = with(drawnDensity) { portraitCatalogCardWidth.roundToPx() }
    val drawnLogoPx = with(drawnDensity) { 220.dp.roundToPx() }
    val drawnScreenPx = LocalContext.current.resources.displayMetrics.widthPixels
    LaunchedEffect(drawnPosterPx, drawnLogoPx, drawnScreenPx) {
        DrawnArtworkWidths.report(drawnPosterPx, drawnLogoPx, drawnScreenPx)
    }
    val portraitCatalogCardHeight = portraitBaseHeight * 0.84f * portraitModernPosterScale
    val landscapeCatalogCardWidth = portraitBaseWidth * 1.24f * landscapeModernPosterScale
    val landscapeCatalogCardHeight = landscapeCatalogCardWidth / 1.77f
    // Poster style reuses the portrait catalog dimensions so its artwork matches the catalogs below it.
    val continueWatchingStyle = uiState.continueWatchingCardStyle
    val continueWatchingScale = 1.34f
    val continueWatchingCardWidth = when (continueWatchingStyle) {
        ContinueWatchingCardStyle.POSTER -> portraitCatalogCardWidth
        // Wide still scales with the poster width setting so it matches the rest of the row.
        ContinueWatchingCardStyle.WIDE -> portraitBaseWidth * 2.1f
        ContinueWatchingCardStyle.CARD -> portraitBaseWidth * 1.24f * continueWatchingScale
    }
    val continueWatchingCardHeight = when (continueWatchingStyle) {
        ContinueWatchingCardStyle.POSTER -> portraitCatalogCardHeight
        ContinueWatchingCardStyle.WIDE -> continueWatchingCardWidth * WIDE_CARD_HEIGHT_RATIO
        ContinueWatchingCardStyle.CARD -> continueWatchingCardWidth / 1.77f
    }

    // Frame rule: derive the height budget from real window pixels through
    // the current (scaled) density. The Configuration screen-height value ignores
    // the UI-scale provider, so Configuration-derived dp inflate by the scale factor
    // and squeeze the bottom-anchored hero text block.
    val screenHeight = with(LocalDensity.current) {
        LocalContext.current.resources.displayMetrics.heightPixels.toDp()
    }

    Box(modifier = Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
            val posterCardCornerRadius = remember(uiState.posterCardCornerRadiusDp) { uiState.posterCardCornerRadiusDp.dp }
            val rowHorizontalPadding = if (isV2) 24.dp else 52.dp

            val settledCarouselItemState = remember(rowByKey) {
                derivedStateOf { resolveModernHeroItem(rowByKey.map, settledHeroSelection.value) }
            }

            val resolvedHeroState = remember(settledCarouselItemState, enrichedPreviews, enrichingItemId, settledHeroSelection, uiState.heroEnrichmentEnabled, uiState.homeImdbRatingsVisibility, failedEnrichmentIds, heroSeriesFacts) {
                derivedStateOf {
                    val heroCarouselItem = settledCarouselItemState.value
                    val heroItemId = heroCarouselItem?.metaPreview?.id
                    val enrichingId = enrichingItemId()
                    val enrichmentActive = enrichingId != null && enrichingId == heroItemId
                    
                    val enrichedItem = heroItemId?.let { enrichedPreviews()[it] }
                    val enrichedHero = if (enrichedItem != null) {
                        HeroPreview(
                            title = enrichedItem.name,
                            logo = enrichedItem.logo,
                            description = enrichedItem.description,
                            contentTypeText = heroCarouselItem?.heroPreview?.contentTypeText,
                            isSeries = isSeriesType(enrichedItem.apiType),
                            yearText = extractYearText(enrichedItem.type, enrichedItem.releaseInfo, enrichedItem.released)
                                ?: heroCarouselItem?.heroPreview?.yearText,
                            runtimeText = formatHeroRuntime(enrichedItem.runtime)
                                ?: heroCarouselItem?.heroPreview?.runtimeText,
                            secondaryHighlightText = heroCarouselItem?.metaPreview?.scoutReason ?: enrichedItem.scoutReason,
                            imdbText = enrichedItem.imdbRating
                                ?.let { String.format(java.util.Locale.US, "%.1f", it) },
                            ageRatingText = enrichedItem.ageRating,
                            statusText = enrichedItem.status,
                            countryText = enrichedItem.country,
                            languageText = enrichedItem.language?.uppercase(),
                            genres = enrichedItem.genres.take(3).asStable(),
                            poster = enrichedItem.poster,
                            backdrop = enrichedItem.backdropUrl,
                            imageUrl = heroCarouselItem?.heroPreview?.imageUrl,
                            mdbListRatings = enrichedItem.mdbListRatings,
                            mdbListRatingOrder = enrichedItem.mdbListRatingOrder,
                            frozenBackdropUrl = heroCarouselItem?.heroPreview?.frozenBackdropUrl,
                            frozenLogoUrl = heroCarouselItem?.heroPreview?.frozenLogoUrl
                        )
                    } else null

                    val resolvedHero = when {
                        heroCarouselItem == null -> null
                        enrichedHero != null -> enrichedHero
                        else -> heroCarouselItem.heroSeriesRef()
                            ?.let { ref -> heroCarouselItem.heroPreview.withSeriesFacts(heroSeriesFacts()[ref.key]) }
                            ?: heroCarouselItem.heroPreview
                    }
                    
                    // Only use the real enrichmentActive flag from the ViewModel.
                    // Additionally, if enrichment is enabled but no enriched data exists yet
                    // for this item, treat as pending to avoid showing un-enriched addon data.
                    // Exception: if enrichment already failed for this item, show addon data.
                    // Also treat as pending when heroCarouselItem is null (row not yet resolved).
                    val heroEnrichmentEnabled = uiState.heroEnrichmentEnabled
                    val enrichmentFailed = heroItemId != null && heroItemId in failedEnrichmentIds()
                    val effectiveEnrichmentActive = heroCarouselItem == null || enrichmentActive ||
                        (enrichedHero == null && heroItemId != null && heroEnrichmentEnabled && !enrichmentFailed)
                    
                    // Artwork is available independently of metadata enrichment. All candidates
                    // come from the settled item; a row fallback can belong to another title.
                    val artSource = enrichedItem ?: heroCarouselItem?.metaPreview
                    val artwork = landscapeHeroArtwork(
                        owner = heroCarouselItem?.key,
                        poster = artSource?.poster ?: resolvedHero?.poster,
                        background = artSource?.background ?: resolvedHero?.backdrop,
                        landscape = artSource?.landscapePoster,
                        fallbackBackground = heroCarouselItem?.heroPreview?.backdrop,
                        fallbackPoster = heroCarouselItem?.heroPreview?.poster,
                        widePoster = heroCarouselItem?.metaPreview
                            ?.takeIf { it.posterShape == PosterShape.LANDSCAPE }?.poster
                            ?: heroCarouselItem?.heroPreview?.backdrop
                                ?.takeIf { heroCarouselItem.payload is ModernPayload.CollectionFolder }
                    )
                    ResolvedModernHeroState(artwork, resolvedHero, effectiveEnrichmentActive)
                }
            }

            val expandedFocusedSelectionState = remember {
                derivedStateOf {
                    focusedCatalogSelection.value
                        ?.takeIf { it.focusKey == expandedCatalogFocusKey.value }
                        ?.takeIf { it.payload is ModernPayload.Catalog }
                }
            }

            val heroTrailerUrlsState = remember(trailerPreviewUrls, trailerPreviewAudioUrls) {
                derivedStateOf {
                    val expandedFocusedSelection = expandedFocusedSelectionState.value
                    val itemId = (expandedFocusedSelection?.payload as? ModernPayload.Catalog)?.itemId
                    val url = itemId?.let { trailerPreviewUrls[it] }
                    val audioUrl = itemId?.let { trailerPreviewAudioUrls[it] }
                    url to audioUrl
                }
            }
            val collectionHeroVideoUrl by remember {
                derivedStateOf { (focusedCatalogSelection.value?.payload as? ModernPayload.CollectionFolder)?.heroVideoUrl }
            }
            val collectionHeroVideoPlaybackKey by remember {
                derivedStateOf {
                    val focusKey = focusedCatalogSelection.value?.focusKey
                    val url = collectionHeroVideoUrl?.takeIf { it.isNotBlank() }
                    if (focusKey != null && url != null) "$focusKey::${focusedHeroMediaNonce.intValue}::$url" else null
                }
            }
            val isScrollStoppedState = remember(verticalRowListState) {
                derivedStateOf { !verticalRowListState.isScrollInProgress }
            }
            val profileEntry = com.nuvio.tv.ui.v2.profile.LocalProfileEntryState.current
            val shouldPlayCatalogHeroTrailerState = remember(
                isScrollStoppedState,
                effectiveAutoplayEnabled,
                trailerPlaybackTarget,
                heroTrailerUrlsState,
                isSidebarExpanded,
                isRapidHorizontalNav,
                settledCarouselItemState
            ) {
                derivedStateOf {
                    profileEntry?.pending != true &&
                        isScrollStoppedState.value &&
                        effectiveAutoplayEnabled &&
                        !isSidebarExpanded.value &&
                        !isRapidHorizontalNav.value &&
                        expandedFocusedSelectionState.value?.focusKey ==
                            (settledCarouselItemState.value?.payload as? ModernPayload.Catalog)?.focusKey &&
                        trailerPlaybackTarget != FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD &&
                        !heroTrailerUrlsState.value.first.isNullOrBlank()
                }
            }
            val shouldPlayCollectionHeroVideoState = remember(
                isScrollStoppedState,
                collectionHeroVideoUrl,
                collectionHeroVideoPlaybackKey,
                endedCollectionHeroVideoPlaybackKey,
                isSidebarExpanded,
                settledCarouselItemState
            ) {
                derivedStateOf {
                    profileEntry?.pending != true &&
                        isScrollStoppedState.value &&
                        !isSidebarExpanded.value &&
                        !isRapidHorizontalNav.value &&
                        focusedCatalogSelection.value?.focusKey ==
                            (settledCarouselItemState.value?.payload as? ModernPayload.CollectionFolder)?.focusKey &&
                        !collectionHeroVideoUrl.isNullOrBlank() &&
                        collectionHeroVideoPlaybackKey != null &&
                        endedCollectionHeroVideoPlaybackKey != collectionHeroVideoPlaybackKey
                }
            }
            val heroMediaDataState = remember(shouldPlayCollectionHeroVideoState, collectionHeroVideoUrl, heroTrailerUrlsState, collectionHeroVideoPlaybackKey) {
                derivedStateOf {
                    val shouldPlayCollectionHeroVideo = shouldPlayCollectionHeroVideoState.value
                    val (heroTrailerUrl, heroTrailerAudioUrl) = heroTrailerUrlsState.value
                    val url = if (shouldPlayCollectionHeroVideo) collectionHeroVideoUrl else heroTrailerUrl
                    val audioUrl = if (shouldPlayCollectionHeroVideo) null else heroTrailerAudioUrl
                    val playbackKey = if (shouldPlayCollectionHeroVideo) collectionHeroVideoPlaybackKey else heroTrailerUrl
                    Triple(url, audioUrl, playbackKey)
                }
            }
            val shouldPlayHeroTrailerState = remember(shouldPlayCatalogHeroTrailerState, shouldPlayCollectionHeroVideoState) {
                derivedStateOf { shouldPlayCatalogHeroTrailerState.value || shouldPlayCollectionHeroVideoState.value }
            }
            val heroMediaMutedState = remember(uiState.focusedPosterBackdropTrailerMuted) {
                derivedStateOf { uiState.focusedPosterBackdropTrailerMuted }
            }
            var heroTrailerFirstFrameRendered by remember { mutableStateOf(false) }
            LaunchedEffect(heroMediaDataState.value.third) {
                heroTrailerFirstFrameRendered = false
            }

            // Collection hero videos should trigger the same fullscreen layout
            // and content fade as catalog trailers — otherwise the video plays
            // "behind" the collection cards instead of expanding into the hero area (#2683).
            val isTrailerPlayingFullscreenState = remember(
                trailerPlaybackTarget,
                fullScreenBackdrop,
                shouldPlayCatalogHeroTrailerState,
                shouldPlayCollectionHeroVideoState
            ) {
                derivedStateOf {
                    trailerPlaybackTarget != FocusedPosterTrailerPlaybackTarget.FEATHERED_WINDOW && fullScreenBackdrop &&
                        (shouldPlayCatalogHeroTrailerState.value || shouldPlayCollectionHeroVideoState.value) &&
                        heroTrailerFirstFrameRendered
                }
            }
            // Keep the top-level flag in sync so the row-scroll BackHandler
            // stays disabled while a fullscreen trailer is visible.
            fullscreenTrailerPlaying.value = isTrailerPlayingFullscreenState.value
            fullscreenTrailerDismiss.value = remember(focusedCatalogSelection, expandedCatalogFocusKey) {
                {
                    focusedCatalogSelection.value = null
                    expandedCatalogFocusKey.value = null
                }
            }
            val liveHeroSceneState = remember(
                resolvedHeroState,
                isV2,
                shouldPlayHeroTrailerState,
                heroMediaDataState,
                heroMediaMutedState,
                trailerPlaybackTarget,
                fullScreenBackdrop
            ) {
                derivedStateOf {
                    val (artwork, resolvedHero, enrichmentActive) = resolvedHeroState.value
                    val (heroMediaUrl, heroMediaAudioUrl, heroMediaPlaybackKey) = heroMediaDataState.value
                    val preview = resolvedHero
                    ModernHeroSceneState(
                        artwork = artwork,
                        heroBackdrop = artwork.urls.firstOrNull(),
                        preview = preview,
                        enrichmentActive = enrichmentActive,
                        shouldPlayTrailer = shouldPlayHeroTrailerState.value,
                        trailerFirstFrameRendered = heroTrailerFirstFrameRendered,
                        trailerUrl = heroMediaUrl,
                        trailerAudioUrl = heroMediaAudioUrl,
                        trailerPlaybackKey = heroMediaPlaybackKey,
                        trailerMuted = heroMediaMutedState.value,
                        fullScreenBackdrop = fullScreenBackdrop,
                        featheredTrailer = trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.FEATHERED_WINDOW
                    )
                }
            }
            val localDensity = LocalDensity.current
            val rowsViewportHeightFraction = if (useLandscapePosters) 0.49f else 0.52f
            val tallestRowCardHeight = maxOf(
                if (useLandscapePosters) landscapeCatalogCardHeight else portraitCatalogCardHeight,
                continueWatchingCardHeight
            )
            val rowsViewportHeight = remember(screenHeight, rowsViewportHeightFraction, tallestRowCardHeight) {
                modernRowsViewportHeight(screenHeight, rowsViewportHeightFraction, tallestRowCardHeight)
            }
            val rowTitleLineHeight = MaterialTheme.typography.titleMedium.lineHeight
            val rowTitleHeight = remember(rowTitleLineHeight, localDensity) {
                with(localDensity) {
                    runCatching { rowTitleLineHeight.toDp() }
                        .getOrDefault(NuvioTheme.spacing.xl)
                }
            }
            val heroBackdropHeight = remember(screenHeight, rowsViewportHeight, rowTitleHeight, trailerPlaybackTarget) {
                val normalHeight = screenHeight - rowsViewportHeight + rowTitleHeight + 14.dp
                // Give the compact feathered video more height while rows retain their viewport.
                (normalHeight * if (trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.FEATHERED_WINDOW) 1.12f else 1f)
                    .coerceAtMost(screenHeight)
            }
            val verticalRowBringIntoViewSpec = remember(localDensity, defaultBringIntoViewSpec) {
                val topInsetPx = with(localDensity) { MODERN_ROW_HEADER_FOCUS_INSET.toPx() }
                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                object : BringIntoViewSpec {
                    override val scrollAnimationSpec: AnimationSpec<Float> = defaultBringIntoViewSpec.scrollAnimationSpec
                    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                        val currentLeadingEdge = offset
                        if (abs(currentLeadingEdge - topInsetPx) < 1f) return 0f
                        val distance = currentLeadingEdge - topInsetPx
                        if (distance < 0f && !verticalRowListState.canScrollBackward) return 0f
                        return distance
                    }
                }
            }
            val contentFocusRequester = LocalContentFocusRequester.current
            val heroWindowWidthPx = LocalContext.current.resources.displayMetrics.widthPixels
            val heroMediaWidthPx = remember(heroWindowWidthPx, fullScreenBackdrop) {
                (if (fullScreenBackdrop) heroWindowWidthPx
                else (heroWindowWidthPx * MODERN_HERO_MEDIA_WIDTH_FRACTION).toInt()).coerceAtLeast(1)
            }
            val heroMediaHeightPx = remember(heroBackdropHeight, screenHeight, localDensity, fullScreenBackdrop) {
                with(localDensity) {
                    if (fullScreenBackdrop) screenHeight.roundToPx()
                    else heroBackdropHeight.roundToPx()
                }.coerceAtLeast(1)
            }

            val preparedHeroScene = rememberPreparedHeroScene(liveHeroSceneState, heroMediaWidthPx, heroMediaHeightPx)
            val heroSceneStateLambda = remember(preparedHeroScene) { { preparedHeroScene.value } }
            LaunchedEffect(artworkAccent, preparedHeroScene) {
                snapshotFlow { preparedHeroScene.value.artwork.ownerKey to preparedHeroScene.value.heroBackdrop }
                    .collect { (owner, url) -> artworkAccent?.select(owner.orEmpty(), url) }
            }

            val isFullScreenState = remember(liveHeroSceneState) {
                derivedStateOf { liveHeroSceneState.value.fullScreenBackdrop }
            }
            val isFullScreenLambda = remember(isFullScreenState) {
                { isFullScreenState.value }
            }

            val heroMediaModifier = remember(heroBackdropHeight, screenHeight, fullScreenBackdrop, isV2) {
                if (fullScreenBackdrop) {
                    Modifier.align(Alignment.TopStart).fillMaxWidth().height(screenHeight)
                } else {
                    Modifier.align(Alignment.TopEnd).offset(x = if (isV2) 0.dp else NuvioTheme.spacing.huge).fillMaxWidth(MODERN_HERO_MEDIA_WIDTH_FRACTION).height(heroBackdropHeight)
                }
            }

            val fullScreenBackdropUpdated by rememberUpdatedState(fullScreenBackdrop)
            val shouldPlayCatalogHeroTrailerUpdated by rememberUpdatedState(shouldPlayCatalogHeroTrailerState.value)
            val shouldPlayCollectionHeroVideoUpdated by rememberUpdatedState(shouldPlayCollectionHeroVideoState.value)
            val heroTrailerFirstFrameRenderedUpdated by rememberUpdatedState(heroTrailerFirstFrameRendered)

            val latestShouldPlayCollectionHeroVideoState = rememberUpdatedState(shouldPlayCollectionHeroVideoState)
            val onTrailerEndedLambda = remember {
                {
                    if (latestShouldPlayCollectionHeroVideoState.value.value && collectionHeroVideoPlaybackKey != null) {
                        endedCollectionHeroVideoPlaybackKey = collectionHeroVideoPlaybackKey
                    } else {
                        expandedCatalogFocusKey.value = null
                    }
                }
            }
            val onFirstFrameRenderedLambda = remember { { heroTrailerFirstFrameRendered = true } }

            ModernHeroSection(
                heroSceneState = heroSceneStateLambda,
                isFullScreen = isFullScreenLambda,
                heroMediaWidthPx = heroMediaWidthPx,
                heroMediaHeightPx = heroMediaHeightPx,
                trailerBottomLimit = screenHeight - rowsViewportHeight - 8.dp,
                modifier = heroMediaModifier,
                onTrailerEnded = onTrailerEndedLambda,
                onFirstFrameRendered = onFirstFrameRenderedLambda
            )

            // Fade content rows when ANY hero media (catalog trailer or collection
            // hero video) is playing in fullscreen — not just catalog trailers.
            val trailerContentAlphaState = animateFloatAsState(
                targetValue = if (trailerPlaybackTarget != FocusedPosterTrailerPlaybackTarget.FEATHERED_WINDOW && fullScreenBackdropUpdated && (shouldPlayCatalogHeroTrailerUpdated || shouldPlayCollectionHeroVideoUpdated) && heroTrailerFirstFrameRenderedUpdated) 0f else 1f,
                animationSpec = tween(durationMillis = 480),
                label = "trailerContentFade"
            )

            val shouldPlayTrailerLambda = remember { { shouldPlayCatalogHeroTrailerUpdated || shouldPlayCollectionHeroVideoUpdated } }
            val heroTrailerRenderedLambda = remember { { heroTrailerFirstFrameRenderedUpdated } }

            val foregroundModifier = Modifier.sidebarPageContent()
            val heroMetadataModifier = remember(rowHorizontalPadding, rowsViewportHeight, screenHeight, foregroundModifier) {
                foregroundModifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = rowHorizontalPadding,
                        end = NuvioTheme.spacing.xxxl,
                        bottom = NuvioTheme.spacing.none + rowsViewportHeight + NuvioTheme.spacing.lg
                    )
                    .heightIn(max = modernHeroTextMaxHeight(screenHeight, rowsViewportHeight))
                    .fillMaxWidth(MODERN_HERO_TEXT_WIDTH_FRACTION)
            }

            val appContext = LocalContext.current.applicationContext
            val uiScaleFlow = remember(appContext) { com.nuvio.tv.data.local.UiScalePreference.flow(appContext) }
            val heroDescriptionScalePercent by uiScaleFlow.collectAsState(initial = 100)
            val heroDescriptionMaxLines = when {
                heroDescriptionScalePercent <= 95 -> 4
                heroDescriptionScalePercent <= 100 -> 4
                else -> 2
            }
            HeroTitleBlock(
                previewProvider = { heroSceneStateLambda().preview },
                enrichmentActive = { false },
                preparedLogo = heroSceneStateLambda().preparedLogo,
                portraitMode = !useLandscapePosters,
                showImdbRatings = uiState.homeImdbRatingsVisibility.showRatings,
                mdbListShowOnHero = uiState.mdbListShowOnHero,
                mdbListRatingOrder = uiState.mdbListRatingOrder,
                trailerPlaying = {
                    if (isRapidHorizontalNav.value) false
                    else {
                        val state = heroSceneStateLambda()
                        !state.featheredTrailer && state.fullScreenBackdrop && shouldPlayTrailerLambda() && heroTrailerRenderedLambda()
                    }
                },
                descriptionMaxLines = heroDescriptionMaxLines,
                descriptionAutoScrollEnabled = uiState.heroDescriptionAutoScrollEnabled,
                modifier = heroMetadataModifier
            )

            val latestOnFocusedRowKeyChanged by rememberUpdatedState(onFocusedRowKeyChanged)
            val onActiveRowKeyChangeLambda = remember {
                { key: String? ->
                    focusHolder.activeRowKey = key
                    activeRowKey.value = key
                    // saveFocusState only runs on dispose, which a system Home press never
                    // triggers, so report the focused row as it changes instead.
                    latestOnFocusedRowKeyChanged(key)
                }
            }
            val onActiveItemIndexChangeLambda = remember { { index: Int -> focusHolder.activeItemIndex = index; activeItemIndex.intValue = index } }
            val onLastHeroNavigationAtMsChangeLambda = remember { { ms: Long -> lastHeroNavigationAtMs.longValue = ms } }
            val onHeroFocusSettleDelayChangeLambda = remember { { delay: Long -> heroFocusSettleDelayMs.longValue = delay } }
            val onLastFocusedContinueWatchingIndexChangeLambda = remember { { index: Int -> lastFocusedContinueWatchingIndex.intValue = index } }
            val onFocusedCatalogSelectionChangeLambda = remember { { selection: FocusedCatalogSelection? -> focusedCatalogSelection.value = selection } }
            val onFocusedHeroMediaNonceChangeLambda = remember { { nonce: Int -> focusedHeroMediaNonce.intValue = nonce } }
            val onExpansionInteractionNonceChangeLambda = remember { { nonce: Int -> expansionInteractionNonce.intValue = nonce } }
            val onFastScrollingChangedLambda = remember { { scrolling: Boolean -> isFastScrolling.value = scrolling } }
            val onExpandedCatalogFocusKeyChangeLambda = remember { { key: String? -> expandedCatalogFocusKey.value = key } }
            val onBackdropInteractionLambda = remember { { expansionInteractionNonce.intValue++; Unit } }
            val onContinueWatchingOptionsLambda = remember {
                { item: ContinueWatchingItem ->
                    optionsCard.value = activeRowKey.value?.let { it to (focusedItemByRow[it] ?: 0) }
                    optionsItem.value = item
                }
            }
            val onPendingRowFocusClearedLambda = remember {
                {
                    pendingRowFocusKey.value = null
                    pendingRowFocusIndex.value = null
                }
            }
            val onRowItemFocusedInternalLambda = remember(onRowItemFocusedPassedDown) {
                { rowKey: String, index: Int, isCw: Boolean ->
                    onRowItemFocusedPassedDown.value.invoke(rowKey, index, isCw)
                }
            }
            val stableTrailerContentAlphaLambda = remember { { trailerContentAlphaState.value } }
            val stableExpandedTrailerPreviewUrl = remember(heroTrailerUrlsState) { { heroTrailerUrlsState.value.first } }
            val stableExpandedTrailerPreviewAudioUrl = remember(heroTrailerUrlsState) { { heroTrailerUrlsState.value.second } }
            val latestEnrichedPreviews by rememberUpdatedState(enrichedPreviews)
            val stableEnrichedPreviews = remember { derivedStateOf { latestEnrichedPreviews().asStable() } }
            val stableTrailerPreviewUrls = remember(trailerPreviewUrls) { trailerPreviewUrls.asStable() }
            val stableTrailerPreviewAudioUrls = remember(trailerPreviewAudioUrls) { trailerPreviewAudioUrls.asStable() }

            val stableOnRequestLazyCatalogLoad = remember(onRequestLazyCatalogLoad) {
                { catalogKey: String -> onRequestLazyCatalogLoad(catalogKey) }
            }
            // Enrichment is driven solely by the scroll-settle trigger; an
            // immediate per-focus call races the D-pad focus->scroll ordering and
            // leaks mid-scroll, so the rows' onItemFocus is a no-op here.
            val stableOnItemFocus = remember { { _: MetaPreview -> } }
            val stableOnPreloadAdjacentItem = remember(onPreloadAdjacentItem) { { item: MetaPreview -> onPreloadAdjacentItem(item) } }

            ModernHomeRowsList(
                carouselRows = carouselRows,
                verticalRowListState = verticalRowListState,
                focusedItemByRow = stableFocusedItemByRow,
                rowListStates = stableRowListStates,
                loadMoreRequestedTotals = stableLoadMoreRequestedTotals,
                focusState = focusState,
                activeRowKey = activeRowKey,
                activeItemIndex = activeItemIndex,
                isFastScrolling = isFastScrolling,
                onFastScrollingChanged = onFastScrollingChangedLambda,
                contentFocusRequester = contentFocusRequester,
                rowsViewportHeight = rowsViewportHeight,
                catalogBottomPadding = NuvioTheme.spacing.none,
                trailerContentAlpha = stableTrailerContentAlphaLambda,
                verticalRowBringIntoViewSpec = verticalRowBringIntoViewSpec,
                onRowItemFocusedInternal = onRowItemFocusedInternalLambda,
                onNavigateToDetail = onNavigateToDetail,
                onNavigateToFolderDetail = onNavigateToFolderDetail,
                onLoadMoreCatalog = onLoadMoreCatalog,
                onServerArtworkWindow = onServerArtworkWindow,
                onContinueWatchingClick = onContinueWatchingClick,
                onContinueWatchingOptions = onContinueWatchingOptionsLambda,
                onRequestLazyCatalogLoad = stableOnRequestLazyCatalogLoad,
                onBackdropInteraction = onBackdropInteractionLambda,
                onItemFocus = stableOnItemFocus,
                onPreloadAdjacentItem = stableOnPreloadAdjacentItem,
                onExpandedCatalogFocusKeyChange = onExpandedCatalogFocusKeyChangeLambda,
                isCatalogItemWatched = isCatalogItemWatched,
                onCatalogItemLongPress = onCatalogItemLongPress,
                enrichedPreviews = stableEnrichedPreviews,
                trailerPreviewUrls = stableTrailerPreviewUrls,
                trailerPreviewAudioUrls = stableTrailerPreviewAudioUrls,
                useLandscapePosters = useLandscapePosters,
                alwaysShowLandscapeClearlogo = alwaysShowLandscapeClearlogo,
                addonLandscapeBackdropLogo = addonLandscapeBackdropLogo,
                showLabels = uiState.posterLabelsEnabled,
                posterCardCornerRadius = posterCardCornerRadius,
                focusedPosterBackdropTrailerMuted = uiState.focusedPosterBackdropTrailerMuted,
                effectiveExpandEnabled = effectiveExpandEnabled,
                effectiveAutoplayEnabled = effectiveAutoplayEnabled,
                trailerPlaybackTarget = trailerPlaybackTarget,
                expandedCatalogFocusKey = expandedCatalogFocusKey,
                expandedTrailerPreviewUrl = stableExpandedTrailerPreviewUrl,
                expandedTrailerPreviewAudioUrl = stableExpandedTrailerPreviewAudioUrl,
                portraitCatalogCardWidth = portraitCatalogCardWidth,
                portraitCatalogCardHeight = portraitCatalogCardHeight,
                landscapeCatalogCardWidth = landscapeCatalogCardWidth,
                landscapeCatalogCardHeight = landscapeCatalogCardHeight,
                continueWatchingCardWidth = continueWatchingCardWidth,
                continueWatchingCardHeight = continueWatchingCardHeight,
                blurUnwatchedEpisodes = uiState.blurUnwatchedEpisodes,
                useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                continueWatchingCardStyle = continueWatchingStyle,
                continueWatchingCornerRadius = uiState.posterCardCornerRadiusDp.dp,
                pendingRowFocusKey = pendingRowFocusKey,
                pendingRowFocusIndex = pendingRowFocusIndex,
                pendingRowFocusNonce = pendingRowFocusNonce,
                onPendingRowFocusCleared = onPendingRowFocusClearedLambda,
                onActiveRowKeyChange = onActiveRowKeyChangeLambda,
                onActiveItemIndexChange = onActiveItemIndexChangeLambda,
                lastHeroNavigationAtMs = lastHeroNavigationAtMs,
                onLastHeroNavigationAtMsChange = onLastHeroNavigationAtMsChangeLambda,
                onHeroFocusSettleDelayChange = onHeroFocusSettleDelayChangeLambda,
                lastFocusedContinueWatchingIndex = lastFocusedContinueWatchingIndex,
                onLastFocusedContinueWatchingIndexChange = onLastFocusedContinueWatchingIndexChangeLambda,
                focusedCatalogSelection = focusedCatalogSelection,
                onFocusedCatalogSelectionChange = onFocusedCatalogSelectionChangeLambda,
                focusedHeroMediaNonce = focusedHeroMediaNonce,
                onFocusedHeroMediaNonceChange = onFocusedHeroMediaNonceChangeLambda,
                onExpansionInteractionNonceChange = onExpansionInteractionNonceChangeLambda,
                blockLeftOnFirstExpandedItem = blockLeftOnFirstExpandedItem,
                isVerticalRowsScrollingState = isVerticalRowsScrollingState,
                modifier = Modifier.sidebarPageContent()
                    .menuOpenRowEdge(rowHorizontalPadding)
                    .align(Alignment.BottomStart)
                    .onFocusChanged { contentHasFocus.value = it.hasFocus }
            )
    }

    val selectedOptionsItem = optionsItem.value
    if (selectedOptionsItem != null) {
        val comingUp = optionsCard.value?.first == MODERN_COMING_UP_ROW_KEY
        val removable = !comingUp || (selectedOptionsItem as? ContinueWatchingItem.NextUp)?.info?.upcomingOnly == true
        val keepFocusAfterRemoval = {
            val (rowKey, index) = optionsCard.value
                ?: (MODERN_CONTINUE_WATCHING_ROW_KEY to lastFocusedContinueWatchingIndex.intValue)
            val rowSize = carouselRows.list.firstOrNull { it.key == rowKey }?.items?.list?.size ?: 0
            val targetIndex = removedCardFocusTarget(index, rowSize)
            pendingRowFocusKey.value = if (targetIndex != null) rowKey else null
            pendingRowFocusIndex.value = targetIndex
            pendingRowFocusNonce.intValue++
        }
        ContinueWatchingOptionsDialog(
            item = selectedOptionsItem,
            onDismiss = { optionsItem.value = null },
            onRemove = {
                keepFocusAfterRemoval()
                val removed = if (comingUp) {
                    uiState.upcomingItems.firstOrNull { it.contentId() == selectedOptionsItem.contentId() } ?: selectedOptionsItem
                } else selectedOptionsItem
                onRemoveContinueWatching(
                    removed.contentId(),
                    removed.season(),
                    removed.episode(),
                    removed is ContinueWatchingItem.NextUp
                )
                optionsItem.value = null
            },
            onDetails = {
                onNavigateToDetail(selectedOptionsItem.contentId(), selectedOptionsItem.contentType(), "")
                optionsItem.value = null
            },
            onStartFromBeginning = {
                onContinueWatchingStartFromBeginning(selectedOptionsItem)
                optionsItem.value = null
            },
            showPlayManually = showContinueWatchingManualPlayOption && !comingUp,
            showRemove = removable,
            onPlayManually = {
                onContinueWatchingPlayManually(selectedOptionsItem)
                optionsItem.value = null
            },
            onHideFromComingUp = if (comingUp) {
                {
                    keepFocusAfterRemoval()
                    onHideFromComingUp(selectedOptionsItem)
                    optionsItem.value = null
                }
            } else null,
            onMarkWatched = if (!comingUp) {
                {
                    keepFocusAfterRemoval()
                    onMarkContinueWatchingWatched(selectedOptionsItem)
                    optionsItem.value = null
                }
            } else null
        )
    }
}
/** While the V2 menu is open, a card scrolled into the row margin would peek out beside it. */
@Composable
private fun Modifier.menuOpenRowEdge(rowStartPadding: androidx.compose.ui.unit.Dp): Modifier {
    val menuLayout = com.nuvio.tv.ui.v2.components.LocalSidebarPageLayout.current ?: return this
    return drawWithContent {
        if (menuLayout.expansion() <= 0f) {
            drawContent()
            return@drawWithContent
        }
        val edge = (rowStartPadding - 2.dp).toPx().coerceAtLeast(0f)
        val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
        clipRect(
            left = if (rtl) 0f else edge,
            right = if (rtl) size.width - edge else size.width
        ) { this@drawWithContent.drawContent() }
    }
}

@Composable
private fun ModernHeroSection(
    heroSceneState: () -> ModernHeroSceneState,
    isFullScreen: () -> Boolean,
    heroMediaWidthPx: Int,
    heroMediaHeightPx: Int,
    trailerBottomLimit: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
    onTrailerEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit
) {
    val highlighterEnabled = LocalRecompositionHighlighterEnabled.current
    val bgColor = NuvioTheme.colors.Background
    ModernHeroScene(
        state = heroSceneState,
        isFullScreen = isFullScreen,
        bgColor = bgColor,
        trailerBottomLimit = trailerBottomLimit,
        modifier = modifier.then(if (highlighterEnabled) Modifier.recompositionHighlighter() else Modifier),
        requestWidthPx = heroMediaWidthPx,
        requestHeightPx = heroMediaHeightPx,
        onTrailerEnded = onTrailerEnded,
        onFirstFrameRendered = onFirstFrameRendered,
    )
}
