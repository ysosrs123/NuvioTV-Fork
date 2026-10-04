package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.catalogRowLegacyKey
import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.compose.ui.ExperimentalComposeUiApi
import coil3.imageLoader
import coil3.memory.MemoryCache
import com.nuvio.tv.core.image.homePosterPriority
import com.nuvio.tv.core.image.homePosterCacheKey
import coil3.request.ImageRequest
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.isPlaceholder
import com.nuvio.tv.ui.util.StableList
import com.nuvio.tv.ui.util.StableMap
import com.nuvio.tv.ui.util.StableRef
import com.nuvio.tv.ui.util.dpadVerticalFastScroll
import com.nuvio.tv.ui.util.recompositionHighlighter
import com.nuvio.tv.ui.components.rememberPlaceholderShimmerOffsetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

// Vertical poster prefetch: rows just outside the viewport on BOTH flanks get their
// leading posters decoded ahead of arrival (symmetric, direction-agnostic -- the return
// leg of a vertical traversal gets the same treatment as the outbound leg). 2 rows x
// 8 items is roughly one viewport-width of posters per warmed row on a 1080p UI.
private const val VERTICAL_POSTER_PREFETCH_AHEAD_ROWS = 2
private const val VERTICAL_POSTER_PREFETCH_ITEMS_PER_ROW = 8
private const val VERTICAL_POSTER_PREFETCH_MAX_IN_FLIGHT = 32

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalTvMaterial3Api::class,
    ExperimentalComposeUiApi::class,
    kotlinx.coroutines.FlowPreview::class
)
@Composable
internal fun ModernHomeRowsList(
    isVerticalRowsScrollingState: State<Boolean>,
    carouselRows: StableList<HeroCarouselRow>,
    verticalRowListState: LazyListState,
    focusedItemByRow: StableRef<MutableMap<String, Int>>,
    rowListStates: StableRef<MutableMap<String, LazyListState>>,
    loadMoreRequestedTotals: StableRef<MutableMap<String, Int>>,
    focusState: HomeScreenFocusState,
    activeRowKey: State<String?>,
    activeItemIndex: State<Int>,
    isFastScrolling: State<Boolean>,
    onFastScrollingChanged: (Boolean) -> Unit,
    contentFocusRequester: FocusRequester,
    rowsViewportHeight: Dp,
    catalogBottomPadding: Dp,
    trailerContentAlpha: () -> Float,
    verticalRowBringIntoViewSpec: BringIntoViewSpec,
    onRowItemFocusedInternal: (String, Int, Boolean) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingOptions: (ContinueWatchingItem) -> Unit,
    onRequestLazyCatalogLoad: (String) -> Unit,
    onBackdropInteraction: () -> Unit,
    onExpandedCatalogFocusKeyChange: (String?) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onPreloadAdjacentItem: (MetaPreview) -> Unit,
    enrichedPreviews: State<StableMap<String, MetaPreview>>,
    trailerPreviewUrls: StableMap<String, String>,
    trailerPreviewAudioUrls: StableMap<String, String>,
    useLandscapePosters: Boolean,
    alwaysShowLandscapeClearlogo: Boolean = false,
    showLabels: Boolean,
    posterCardCornerRadius: Dp,
    focusedPosterBackdropTrailerMuted: Boolean,
    effectiveExpandEnabled: Boolean,
    effectiveAutoplayEnabled: Boolean,
    trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
    expandedCatalogFocusKey: State<String?>,
    expandedTrailerPreviewUrl: () -> String?,
    expandedTrailerPreviewAudioUrl: () -> String?,
    portraitCatalogCardWidth: Dp,
    portraitCatalogCardHeight: Dp,
    landscapeCatalogCardWidth: Dp,
    landscapeCatalogCardHeight: Dp,
    continueWatchingCardWidth: Dp,
    continueWatchingCardHeight: Dp,
    blurUnwatchedEpisodes: Boolean,
    useEpisodeThumbnails: Boolean,
    continueWatchingCardStyle: ContinueWatchingCardStyle,
    continueWatchingCornerRadius: Dp,
    pendingRowFocusKey: State<String?>,
    pendingRowFocusIndex: State<Int?>,
    pendingRowFocusNonce: State<Int>,
    onPendingRowFocusCleared: () -> Unit,
    onActiveRowKeyChange: (String?) -> Unit,
    onActiveItemIndexChange: (Int) -> Unit,
    lastHeroNavigationAtMs: State<Long>,
    onLastHeroNavigationAtMsChange: (Long) -> Unit,
    onHeroFocusSettleDelayChange: (Long) -> Unit,
    lastFocusedContinueWatchingIndex: State<Int>,
    onLastFocusedContinueWatchingIndexChange: (Int) -> Unit,
    focusedCatalogSelection: State<FocusedCatalogSelection?>,
    onFocusedCatalogSelectionChange: (FocusedCatalogSelection?) -> Unit,
    focusedHeroMediaNonce: State<Int>,
    onFocusedHeroMediaNonceChange: (Int) -> Unit,
    onExpansionInteractionNonceChange: (Int) -> Unit,
    blockLeftOnFirstExpandedItem: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Unwrap StableRef wrappers for internal use (not passed to child composables)
    val focusedItemByRowMap = focusedItemByRow.value
    val rowListStatesMap = rowListStates.value
    val loadMoreRequestedTotalsMap = loadMoreRequestedTotals.value

    val latestOnActiveRowKeyChange = rememberUpdatedState(onActiveRowKeyChange)
    val latestOnActiveItemIndexChange = rememberUpdatedState(onActiveItemIndexChange)

    val rowFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val isV2 = com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current != null
    val navigationScope = rememberCoroutineScope()
    var verticalFocusJob by remember { mutableStateOf<Job?>(null) }
    val stableItemFocusRequestersByRow = remember { mutableMapOf<String, StableRef<MutableMap<Int, FocusRequester>>>() }

    val density = LocalDensity.current
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val verticalPrefetchImageLoader = context.imageLoader
    val imageViewport = remember { com.nuvio.tv.core.image.HomePosterViewport() }
    LaunchedEffect(verticalRowListState) {
        snapshotFlow { verticalRowListState.layoutInfo.visibleItemsInfo.map { it.index } to verticalRowListState.isScrollInProgress }.collect { (visible, scrolling) ->
            imageViewport.scrolling = scrolling
            imageViewport.firstRow = visible.firstOrNull() ?: 0
            imageViewport.lastRow = visible.lastOrNull() ?: 1
        }
    }
    val latestCarouselRowsForImagePrefetch = rememberUpdatedState(carouselRows)
    ProgressiveHomePosterWarmup(
        rows = latestCarouselRowsForImagePrefetch,
        viewport = imageViewport,
        rowStates = rowListStatesMap,
        verticalScrolling = isVerticalRowsScrollingState,
        useLandscapePosters = useLandscapePosters,
        alwaysShowLandscapeClearlogo = alwaysShowLandscapeClearlogo,
        portraitWidth = portraitCatalogCardWidth,
        portraitHeight = portraitCatalogCardHeight,
        landscapeWidth = landscapeCatalogCardWidth,
        landscapeHeight = landscapeCatalogCardHeight
    )

    LaunchedEffect(
        verticalPrefetchImageLoader,
        verticalRowListState,
        density,
        useLandscapePosters,
        effectiveExpandEnabled,
        portraitCatalogCardWidth,
        portraitCatalogCardHeight,
        landscapeCatalogCardWidth,
        landscapeCatalogCardHeight
    ) {
        var previousFirstRow = -1
        val inFlight = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Job>>()
        snapshotFlow {
            val visible = verticalRowListState.layoutInfo.visibleItemsInfo
            val first = visible.firstOrNull()?.index ?: -1
            val last = visible.lastOrNull()?.index ?: -1
            // Retry warming when nearby catalogue placeholders become real items,
            // even if the viewport itself has not moved.
            val nearby = latestCarouselRowsForImagePrefetch.value.list
            Triple(first, last, ((first - VERTICAL_POSTER_PREFETCH_AHEAD_ROWS)..(last + VERTICAL_POSTER_PREFETCH_AHEAD_ROWS)).map {
                nearby.getOrNull(it)?.items?.list?.firstOrNull()?.imageUrl
            })
        }
            .distinctUntilChanged()
            .debounce(120L) // VERTICAL_PREFETCH_DEBOUNCE_MS
            .collect { (firstVisibleRowIndex, lastVisibleRowIndex, _) ->
                val warmRows = com.nuvio.tv.core.image.homePosterPrefetchRows(
                    firstVisibleRowIndex, lastVisibleRowIndex, previousFirstRow, VERTICAL_POSTER_PREFETCH_AHEAD_ROWS
                )
                previousFirstRow = firstVisibleRowIndex
                // Started requests survive viewport changes during a held scroll; only rows
                // the user has already left behind are dropped.
                val keepRows = com.nuvio.tv.core.image.homePosterAheadKeepRows(
                    firstVisibleRowIndex, lastVisibleRowIndex, VERTICAL_POSTER_PREFETCH_AHEAD_ROWS
                )
                inFlight.forEach { (key, entry) ->
                    if (entry.first !in keepRows && inFlight.remove(key, entry)) entry.second.cancel()
                }
                val rows = latestCarouselRowsForImagePrefetch.value.list
                val viewportWidth = verticalRowListState.layoutInfo.viewportSize.width
                // Visible cards own their requests. Warm upcoming rows in order,
                // then the preceding row, without launching a second copy of visible artwork.
                for (index in warmRows) {
                    val row = rows.getOrNull(index) ?: continue
                    val leadingItem = row.items.list.firstOrNull() ?: continue
                    if (leadingItem.payload is ModernPayload.ContinueWatching) continue
                    val leadingMetrics = leadingItem.catalogCardRequestMetrics(
                        useLandscapePosters = useLandscapePosters,
                        portraitCardWidth = portraitCatalogCardWidth,
                        portraitCardHeight = portraitCatalogCardHeight,
                        landscapeCardWidth = landscapeCatalogCardWidth,
                        landscapeCardHeight = landscapeCatalogCardHeight,
                        expandEnabled = false
                    )
                    val leadingWidth = with(density) { leadingMetrics.width.roundToPx() }.coerceAtLeast(1)
                    val warmCount = if (viewportWidth > 0)
                        (kotlin.math.ceil(viewportWidth.toDouble() / leadingWidth).toInt() + 1).coerceIn(4, 16)
                    else VERTICAL_POSTER_PREFETCH_ITEMS_PER_ROW
                    for (i in 0 until minOf(warmCount, row.items.list.size)) {
                        if (inFlight.size >= VERTICAL_POSTER_PREFETCH_MAX_IN_FLIGHT) break
                        val item = row.items.list[i]
                        // CW request parity is not reproducible here (see ModernHomeRows): skip.
                        if (item.payload is ModernPayload.ContinueWatching) continue
                        val url = item.collapsedArtworkUrl(
                            useLandscapePosters,
                            alwaysShowLandscapeClearlogo = alwaysShowLandscapeClearlogo
                        )?.takeUnless { it.isPlaceholder() } ?: continue
                        val extras = item.customPosterCacheExtras(url, useLandscapePosters)
                        val metrics = item.catalogCardRequestMetrics(
                            useLandscapePosters = useLandscapePosters,
                            portraitCardWidth = portraitCatalogCardWidth,
                            portraitCardHeight = portraitCatalogCardHeight,
                            landscapeCardWidth = landscapeCatalogCardWidth,
                            landscapeCardHeight = landscapeCatalogCardHeight,
                            expandEnabled = false
                        )
                        val wPx = with(density) { metrics.width.roundToPx() }
                        val hPx = with(density) { metrics.height.roundToPx() }
                        if (wPx <= 0 || hPx <= 0) continue
                        val cacheKey = homePosterCacheKey(url, wPx, hPx)
                        if (inFlight.containsKey(cacheKey)) continue
                        if (verticalPrefetchImageLoader.memoryCache?.get(MemoryCache.Key(cacheKey, extras)) != null) continue
                        val job = launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                            verticalPrefetchImageLoader.execute(
                                ImageRequest.Builder(context)
                                    .data(url)
                                    .homePosterPriority(com.nuvio.tv.core.image.HomePosterPriority(imageViewport, index, prefetch = true))
                                    .memoryCacheKey(cacheKey)
                                    .memoryCacheKeyExtras(extras)
                                    .scale(coil3.size.Scale.FILL)
                                    .size(width = wPx, height = hPx)
                                    .build()
                            )
                        }
                        val entry = index to job
                        inFlight[cacheKey] = entry
                        job.invokeOnCompletion { inFlight.remove(cacheKey, entry) }
                        job.start()
                    }
                }
            }
    }

    val latestOnRequestLazyCatalogLoad = rememberUpdatedState(onRequestLazyCatalogLoad)
    val latestCarouselRowsForLazy = rememberUpdatedState(carouselRows)
    LaunchedEffect(verticalRowListState) {
        val prefetchAheadForLazy = 2
        snapshotFlow {
            val info = verticalRowListState.layoutInfo
            val firstVisible = info.visibleItemsInfo.firstOrNull()?.index ?: -1
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            firstVisible to lastVisible
        }.collectLatest { (firstVisible, lastVisible) ->
            if (lastVisible < 0) return@collectLatest
            // Request visible catalogue data immediately. Debouncing this during
            // fast navigation left entire rows empty until the user stopped.
            val rows = latestCarouselRowsForLazy.value
            for (idx in firstVisible.coerceAtLeast(0)..(lastVisible + prefetchAheadForLazy)) {
                val row = rows.list.getOrNull(idx) ?: continue
                if (row.isLoading && row.items.list.firstOrNull()?.imageUrl.isPlaceholder()) {
                    val legacyKey = catalogRowLegacyKey(
                        row.addonId ?: continue,
                        row.apiType ?: continue,
                        row.catalogId ?: continue
                    )
                    latestOnRequestLazyCatalogLoad.value(legacyKey)
                }
            }
        }
    }

    val focusRestorerRequester = remember(activeRowKey) {
        {
            activeRowKey.value?.let { rowFocusRequesters[it] } ?: FocusRequester.Default
        }
    }

    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current

    // Only run the shared shimmer while a row on screen actually draws it: rememberInfiniteTransition
    // keeps waking the Compose frame clock on every frame for as long as it is composed, even when
    // nothing reads its value. Rows below the fold stay placeholders until they are scrolled to, so
    // the check has to be on the visible rows, not on the whole list. Keyed on the list state for
    // the same reason isVerticalRowsScrollingState is in ModernHomeContent: rememberLazyListState
    // is saveable-backed and can hand back a new instance, and a derived state still holding the
    // old one would read a layout that has stopped updating.
    val needsPlaceholderShimmer by remember(verticalRowListState) {
        derivedStateOf {
            val rows = latestCarouselRowsForLazy.value.list
            verticalRowListState.layoutInfo.visibleItemsInfo.any { visibleRow ->
                rows.getOrNull(visibleRow.index)?.showsPlaceholderShimmer() == true
            }
        }
    }
    val sharedPlaceholderShimmerOffsetState = if (needsPlaceholderShimmer) {
        rememberPlaceholderShimmerOffsetState(label = "sharedRowShimmer")
    } else {
        null
    }

    CompositionLocalProvider(
        LocalBringIntoViewSpec provides verticalRowBringIntoViewSpec,
        LocalFastScrollActive provides isFastScrolling,
        LocalVerticalRowsScrolling provides isVerticalRowsScrollingState
    ) {
        LazyColumn(
            state = verticalRowListState,
            modifier = modifier
                .fillMaxWidth()
                .recompositionHighlighter()
                .height(rowsViewportHeight)
                .padding(bottom = catalogBottomPadding)
                .clipToBounds()
                .graphicsLayer { alpha = trailerContentAlpha() }
                .focusRequester(contentFocusRequester)
                .focusRestorer { focusRestorerRequester() }
                .onPreviewKeyEvent { event ->
                    // A lifted/scaled card sits above its row neighbours. Spatial Down can
                    // therefore choose a neighbour in the same row. Cross rows by identity.
                    if (isV2 && event.type == KeyEventType.KeyDown &&
                        event.nativeKeyEvent.repeatCount == 0 &&
                        (event.key == Key.DirectionDown || event.key == Key.DirectionUp)) {
                        val currentIndex = carouselRows.list.indexOfFirst { it.key == activeRowKey.value }
                        val targetIndex = adjacentHomeRowIndex(currentIndex, carouselRows.list.size,
                            event.key == Key.DirectionDown)
                        if (targetIndex != null) {
                            val target = carouselRows.list[targetIndex]
                            verticalFocusJob?.cancel()
                            verticalFocusJob = navigationScope.launch {
                                val saved = (focusedItemByRowMap[target.key] ?: 0)
                                    .coerceIn(0, (target.items.list.size - 1).coerceAtLeast(0))
                                val attached = verticalRowListState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
                                if (!attached) {
                                    verticalRowListState.scrollToItem(targetIndex)
                                    repeat(2) { withFrameNanos { } }
                                }
                                val itemTarget = stableItemFocusRequestersByRow[target.key]?.value?.get(saved)
                                val moved = runCatching { itemTarget?.requestFocus() == true }.getOrDefault(false)
                                if (!moved) runCatching { rowFocusRequesters[target.key]?.requestFocus() }
                            }
                            return@onPreviewKeyEvent true
                        }
                        // Let Up at the first row reach top navigation; Down at the end stays put.
                        if (event.key == Key.DirectionDown) return@onPreviewKeyEvent true
                    }
                    val firstRowKey = carouselRows.list.firstOrNull()?.key
                    val lastRowKey = carouselRows.list.lastOrNull()?.key
                    if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionUp &&
                        effectiveExpandEnabled &&
                        expandedCatalogFocusKey.value != null &&
                        activeRowKey.value == firstRowKey
                    ) return@onPreviewKeyEvent true
                    if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionDown &&
                        effectiveExpandEnabled &&
                        expandedCatalogFocusKey.value != null &&
                        activeRowKey.value == lastRowKey
                    ) return@onPreviewKeyEvent true
                    val blockKey = if (layoutDirection == LayoutDirection.Rtl)
                        Key.DirectionRight else Key.DirectionLeft
                    if (blockLeftOnFirstExpandedItem &&
                        event.type == KeyEventType.KeyDown &&
                        event.key == blockKey &&
                        effectiveExpandEnabled &&
                        expandedCatalogFocusKey.value != null &&
                        activeItemIndex.value == 0
                    ) return@onPreviewKeyEvent true
                    false
                }
                .dpadVerticalFastScroll(
                    scrollableState = verticalRowListState,
                    verticalVelocityDpPerSec = 2000f,
                    onFastScrollingChanged = onFastScrollingChanged,
                    shouldHaltForward = {
                        val info = verticalRowListState.layoutInfo
                        val lastIdx = carouselRows.list.size - 1
                        val lastVisible = info.visibleItemsInfo.lastOrNull { it.index == lastIdx }
                        lastIdx >= 0 && lastVisible != null &&
                            lastVisible.offset + lastVisible.size <= info.viewportEndOffset
                    },
                    resolveVerticalLanding = { sign ->
                        val layoutInfo = verticalRowListState.layoutInfo
                        val visibleItems = layoutInfo.visibleItemsInfo
                        val lastIdx = carouselRows.list.size - 1
                        val viewportEnd = layoutInfo.viewportEndOffset
                        val lastRowAtBottom = lastIdx >= 0 &&
                            visibleItems.lastOrNull { it.index == lastIdx }?.let {
                                it.offset + it.size <= viewportEnd
                            } == true
                        val upwardTopRow: LazyListItemInfo? = if (sign < 0) {
                            visibleItems.firstOrNull()?.takeIf {
                                it.offset > -it.size / 2
                            }
                        } else null
                        val targetRowIndex = when {
                            lastRowAtBottom -> lastIdx
                            upwardTopRow != null -> upwardTopRow.index
                            else ->
                                visibleItems.firstOrNull { it.offset >= 0 }?.index
                                    ?: visibleItems.firstOrNull()?.index
                                    ?: verticalRowListState.firstVisibleItemIndex
                        }
                        val targetRow = carouselRows.list.getOrNull(targetRowIndex)
                        if (targetRow == null) null
                        else {
                            val savedIdx = (focusedItemByRowMap[targetRow.key] ?: 0)
                                .coerceIn(0, (targetRow.items.list.size - 1).coerceAtLeast(0))
                            latestOnActiveRowKeyChange.value(targetRow.key)
                            latestOnActiveItemIndexChange.value(savedIdx)

                            val targetItemKey = targetRow.items.list.getOrNull(savedIdx)?.key
                                ?: "${targetRow.key}_$savedIdx"
                            targetItemKey
                        }
                    },
                ),
            contentPadding = PaddingValues(bottom = rowsViewportHeight),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)
        ) {
            itemsIndexed(
                items = carouselRows.list,
                key = { _, row -> row.key },
                contentType = { _, row -> row.apiType ?: "modern_home_row" }
            ) { rowIndex, row ->
                val stableOnContinueWatchingOptions = remember(onContinueWatchingOptions) {
                    { item: ContinueWatchingItem -> onContinueWatchingOptions(item) }
                }
                val stableOnRowItemFocused = remember {
                    { rowKey: String, index: Int, isContinueWatchingRow: Boolean ->
                        val rowBecameActive = activeRowKey.value != rowKey
                        val itemChanged = activeItemIndex.value != index
                        
                        if (rowBecameActive || itemChanged) {
                            val now = System.currentTimeMillis()
                            val timeSinceLastHeroNav = now - lastHeroNavigationAtMs.value
                            onHeroFocusSettleDelayChange(
                                if (lastHeroNavigationAtMs.value != 0L &&
                                    timeSinceLastHeroNav in 1 until 130L // MODERN_HERO_RAPID_NAV_THRESHOLD_MS
                                ) 400L // MODERN_HERO_RAPID_NAV_SETTLE_MS
                                else 450L // MODERN_HERO_FOCUS_DEBOUNCE_MS
                            )
                            onLastHeroNavigationAtMsChange(now)
                            onActiveRowKeyChange(rowKey)
                            onActiveItemIndexChange(index)
                        }

                        // Always keep the focusedItemByRow map in sync for ALL rows
                        if (focusedItemByRowMap[rowKey] != index) {
                            focusedItemByRowMap[rowKey] = index
                        }

                        if (isContinueWatchingRow) {
                            if (lastFocusedContinueWatchingIndex.value != index) {
                                onLastFocusedContinueWatchingIndexChange(index)
                            }
                            if (focusedCatalogSelection.value != null) {
                                onFocusedCatalogSelectionChange(null)
                            }
                        }
                        onRowItemFocusedInternal(rowKey, index, isContinueWatchingRow)
                    }
                }
                val rowIsActive = remember(row.key, activeRowKey) {
                    derivedStateOf { row.key == activeRowKey.value }
                }
                val isActiveRowLambda = remember(rowIsActive) {
                    { rowIsActive.value }
                }
                val stableOnCatalogSelectionFocused = remember {
                    { selection: FocusedCatalogSelection ->
                        val isCollectionFolder = selection.payload is ModernPayload.CollectionFolder
                        if (focusedCatalogSelection.value != selection || isCollectionFolder) {
                            onFocusedCatalogSelectionChange(selection)
                            if (isCollectionFolder) {
                                onFocusedHeroMediaNonceChange(focusedHeroMediaNonce.value + 1)
                            }
                        }
                    }
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    com.nuvio.tv.core.image.LocalHomePosterPriority provides
                        com.nuvio.tv.core.image.HomePosterPriority(imageViewport, rowIndex)
                ) {
                ModernRowSection(
                    row = row,
                    isActiveRow = isActiveRowLambda,
                    rowFocusRequester = rowFocusRequesters.getOrPut(row.key) { FocusRequester() },
                    rowTitleBottom = 14.dp, // rowTitleBottom
                    defaultBringIntoViewSpec = defaultBringIntoViewSpec,
                    focusStateCatalogRowScrollIndex = focusState.catalogRowScrollStates[row.key] ?: 0,
                    focusStateCatalogRowScrollAnchor = focusState.catalogRowScrollAnchors[row.key],
                    focusedItemByRow = focusedItemByRow,
                    rowListStates = rowListStates,
                    loadMoreRequestedTotals = loadMoreRequestedTotals,
                    pendingRowFocusKey = pendingRowFocusKey,
                    pendingRowFocusIndex = pendingRowFocusIndex,
                    pendingRowFocusNonce = pendingRowFocusNonce,
                    onPendingRowFocusCleared = onPendingRowFocusCleared,
                    onRowItemFocused = stableOnRowItemFocused,
                    useLandscapePosters = useLandscapePosters,
                    alwaysShowLandscapeClearlogo = alwaysShowLandscapeClearlogo,
                    showLabels = showLabels,
                    posterCardCornerRadius = posterCardCornerRadius,
                    focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
                    effectiveExpandEnabled = effectiveExpandEnabled,
                    effectiveAutoplayEnabled = effectiveAutoplayEnabled,
                    trailerPlaybackTarget = trailerPlaybackTarget,
                    expandedCatalogFocusKey = expandedCatalogFocusKey,
                    expandedTrailerPreviewUrl = expandedTrailerPreviewUrl,
                    expandedTrailerPreviewAudioUrl = expandedTrailerPreviewAudioUrl,
                    portraitCatalogCardWidth = portraitCatalogCardWidth,
                    portraitCatalogCardHeight = portraitCatalogCardHeight,
                    landscapeCatalogCardWidth = landscapeCatalogCardWidth,
                    landscapeCatalogCardHeight = landscapeCatalogCardHeight,
                    continueWatchingCardWidth = continueWatchingCardWidth,
                    continueWatchingCardHeight = continueWatchingCardHeight,
                    blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                    useEpisodeThumbnails = useEpisodeThumbnails,
                    continueWatchingCardStyle = continueWatchingCardStyle,
                    continueWatchingCornerRadius = continueWatchingCornerRadius,
                    onContinueWatchingClick = onContinueWatchingClick,
                    onContinueWatchingOptions = stableOnContinueWatchingOptions,
                    isCatalogItemWatched = isCatalogItemWatched,
                    onCatalogItemLongPress = onCatalogItemLongPress,
                    onItemFocus = onItemFocus,
                    onPreloadAdjacentItem = onPreloadAdjacentItem,
                    enrichedPreviews = enrichedPreviews,
                    onCatalogSelectionFocused = stableOnCatalogSelectionFocused,
                    onNavigateToDetail = onNavigateToDetail,
                    onNavigateToFolderDetail = onNavigateToFolderDetail,
                    onLoadMoreCatalog = onLoadMoreCatalog,
                    onBackdropInteraction = onBackdropInteraction,
                    onExpandedCatalogFocusKeyChange = onExpandedCatalogFocusKeyChange,
                    sharedPlaceholderShimmerOffsetState = sharedPlaceholderShimmerOffsetState,
                    isVerticalRowsScrollingState = isVerticalRowsScrollingState,
                    itemFocusRequesters = stableItemFocusRequestersByRow.getOrPut(row.key) {
                        StableRef(mutableMapOf())
                    }
                )
                }
            }
        }
    }
}
