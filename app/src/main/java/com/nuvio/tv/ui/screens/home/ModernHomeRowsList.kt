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
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

// Vertical poster prefetch: rows just outside the viewport on BOTH flanks get their
// leading posters decoded ahead of arrival (symmetric, direction-agnostic -- the return
// leg of a vertical traversal gets the same treatment as the outbound leg). 2 rows x
// 8 items is roughly one viewport-width of posters per warmed row on a 1080p UI.
private const val VERTICAL_POSTER_PREFETCH_AHEAD_ROWS = 2
private const val VERTICAL_POSTER_PREFETCH_ITEMS_PER_ROW = 8
private const val VERTICAL_POSTER_PREFETCH_MAX_IN_FLIGHT = 32
private const val VERTICAL_POSTER_PREFETCH_MAX_IN_FLIGHT_SCROLLING = 8

/** Poster requests the vertical pre-load may have running; the rest start when the list stops. */
internal fun verticalPosterPrefetchInFlightLimit(scrolling: Boolean): Int =
    if (scrolling) VERTICAL_POSTER_PREFETCH_MAX_IN_FLIGHT_SCROLLING else VERTICAL_POSTER_PREFETCH_MAX_IN_FLIGHT

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
    onServerArtworkWindow: (List<Pair<String, List<MetaPreview>>>) -> Unit = {},
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
    addonLandscapeBackdropLogo: Boolean = false,
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
    val verticalHeading = remember { VerticalHeading() }
    // Up and Down keep the column, in the item coordinates every Home row shares.
    fun focusedCardCenter(): Int? {
        val rowKey = activeRowKey.value ?: return null
        val index = focusedItemByRowMap[rowKey] ?: activeItemIndex.value
        val info = rowListStatesMap[rowKey]?.layoutInfo ?: return null
        val items = info.visibleItemsInfo
        val first = items.firstOrNull() ?: return null
        val last = items.last()
        val end = info.viewportEndOffset - info.afterContentPadding
        val span = last.size + info.mainAxisItemSpacing
        return focusedColumnCenter(
            index,
            items.map { RowCardSpan(it.index, it.offset, it.size) },
            end,
            keepInView = isV2,
            roomAheadPx = maxOf(0, last.offset + last.size + (info.totalItemsCount - 1 - last.index) * span - end),
            roomBackPx = maxOf(0, first.index * span - first.offset)
        )
    }
    val rowDensity = LocalDensity.current
    // A row's normal card width, from its first card's kind and the poster settings, for rows not laid out yet.
    fun rowCardWidthPx(row: HeroCarouselRow): Int? {
        val item = row.items.list.firstOrNull() ?: return null
        val width = if (item.payload is ModernPayload.ContinueWatching) continueWatchingCardWidth
        else item.catalogCardRequestMetrics(
            useLandscapePosters, portraitCatalogCardWidth, portraitCatalogCardHeight,
            landscapeCatalogCardWidth, landscapeCatalogCardHeight
        ).width
        return with(rowDensity) { width.roundToPx() }
    }
    // The card a vertical move lands on, and why. The remembered card is only used when there is no column to keep.
    fun columnTarget(row: HeroCarouselRow, centerPx: Int?): Pair<Int, String> {
        val last = (row.items.list.size - 1).coerceAtLeast(0)
        val state = rowListStatesMap[row.key]
        val cards = state?.layoutInfo?.visibleItemsInfo.orEmpty().map { RowCardSpan(it.index, it.offset, it.size) }
        if (centerPx != null) {
            sameColumnCardIndex(centerPx, cards)?.let { return it.coerceIn(0, last) to "same-spot" }
            val spacing = rowListStatesMap.values.firstNotNullOfOrNull { other ->
                other.layoutInfo.takeIf { it.visibleItemsInfo.isNotEmpty() }?.mainAxisItemSpacing
            } ?: 0
            val span = rowCardWidthPx(row)?.plus(spacing)
            if (span != null && span > 0) {
                val index = estimatedColumnIndex(centerPx, state?.firstVisibleItemIndex ?: 0, state?.firstVisibleItemScrollOffset ?: 0, span)
                return index.coerceIn(0, last) to "estimated"
            }
        }
        return (focusedItemByRowMap[row.key] ?: 0).coerceIn(0, last) to "remembered"
    }
    fun logColumnMove(from: String?, centerPx: Int?, to: String, index: Int, reason: String) {
        android.util.Log.d("HomeColumn", "move from=$from col=${centerPx ?: "none"}px to=$to index=$index reason=$reason")
    }

    val density = LocalDensity.current
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val verticalPrefetchImageLoader = context.imageLoader
    val imageViewport = remember { com.nuvio.tv.core.image.HomePosterViewport() }
    com.nuvio.tv.core.image.HomePosterVisit(imageViewport)
    // First and last visible row, -1 when nothing is laid out. The collectors below read it, so only one of them
    // runs on every scroll frame.
    val visibleRowRange = remember(verticalRowListState) { mutableStateOf(-1 to -1) }
    LaunchedEffect(verticalRowListState) {
        snapshotFlow {
            val visible = verticalRowListState.layoutInfo.visibleItemsInfo
            Triple(
                visible.firstOrNull()?.index ?: -1,
                visible.lastOrNull()?.index ?: -1,
                verticalRowListState.isScrollInProgress
            )
        }.collect { (first, last, scrolling) ->
            imageViewport.scrolling = scrolling
            imageViewport.firstRow = if (first >= 0) first else 0
            imageViewport.lastRow = if (last >= 0) last else 1
            visibleRowRange.value = first to last
        }
    }
    LaunchedEffect(verticalRowListState) {
        snapshotFlow {
            verticalRowListState.isScrollInProgress || rowListStatesMap.values.any { it.isScrollInProgress }
        }.collectLatest { scrolling ->
            if (scrolling) {
                while (true) {
                    com.nuvio.tv.core.image.HomePosterScroll.reportScrolling()
                    delay(com.nuvio.tv.core.image.HOME_SCROLL_REPORT_EVERY_MS)
                }
            } else {
                delay(com.nuvio.tv.core.image.HOME_SCROLL_LANES_RESTORE_MS)
                com.nuvio.tv.core.image.HomePosterScroll.reportStopped()
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { com.nuvio.tv.core.image.HomePosterScroll.reportStopped() }
    }
    val latestCarouselRowsForImagePrefetch = rememberUpdatedState(carouselRows)
    val latestOnServerArtworkWindow = rememberUpdatedState(onServerArtworkWindow)
    LaunchedEffect(verticalRowListState) {
        snapshotFlow {
            val visible = verticalRowListState.layoutInfo.visibleItemsInfo
            val rows = visible.firstOrNull()?.index?.let { it..visible.last().index } ?: IntRange.EMPTY
            serverArtworkWindow(latestCarouselRowsForImagePrefetch.value.list, rows) { key ->
                rowListStatesMap[key]?.layoutInfo?.visibleItemsInfo?.takeIf { it.isNotEmpty() }
                    ?.let { it.first().index..it.last().index }
            }
        }
            .distinctUntilChanged()
            .debounce(150L)
            .collect { window -> if (window.isNotEmpty()) latestOnServerArtworkWindow.value(window) }
    }
    ProgressiveHomePosterWarmup(
        rows = latestCarouselRowsForImagePrefetch,
        viewport = imageViewport,
        rowStates = rowListStatesMap,
        verticalScrolling = isVerticalRowsScrollingState,
        useLandscapePosters = useLandscapePosters,
        alwaysShowLandscapeClearlogo = alwaysShowLandscapeClearlogo,
        addonLandscapeBackdropLogo = addonLandscapeBackdropLogo,
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
        alwaysShowLandscapeClearlogo,
        addonLandscapeBackdropLogo,
        effectiveExpandEnabled,
        portraitCatalogCardWidth,
        portraitCatalogCardHeight,
        landscapeCatalogCardWidth,
        landscapeCatalogCardHeight
    ) {
        var previousFirstRow = -1
        val inFlight = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Job>>()
        val prefetchScope = this
        snapshotFlow {
            val (first, last) = visibleRowRange.value
            // Retry warming when nearby catalogue placeholders become real items,
            // even if the viewport itself has not moved.
            val nearby = latestCarouselRowsForImagePrefetch.value.list
            Triple(first, last, ((first - VERTICAL_POSTER_PREFETCH_AHEAD_ROWS)..(last + VERTICAL_POSTER_PREFETCH_AHEAD_ROWS)).map {
                nearby.getOrNull(it)?.items?.list?.firstOrNull()?.imageUrl
            })
        }
            .distinctUntilChanged()
            .debounce(120L) // VERTICAL_PREFETCH_DEBOUNCE_MS
            .collectLatest { (firstVisibleRowIndex, lastVisibleRowIndex, _) ->
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
                val candidates = withContext(Dispatchers.Default) {
                    val found = ArrayList<VerticalPosterPrefetch>()
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
                            if (inFlight.size + found.size >= VERTICAL_POSTER_PREFETCH_MAX_IN_FLIGHT) break
                            val item = row.items.list[i]
                            // CW request parity is not reproducible here (see ModernHomeRows): skip.
                            if (item.payload is ModernPayload.ContinueWatching) continue
                            val landscape =
                                item.usesLandscapeCardTreatment(useLandscapePosters, addonLandscapeBackdropLogo)
                            val url = item.collapsedArtworkUrl(
                                landscape,
                                alwaysShowLandscapeClearlogo = alwaysShowLandscapeClearlogo
                            )?.takeUnless { it.isPlaceholder() } ?: continue
                            val extras = item.customPosterCacheExtras(url, landscape)
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
                            if (inFlight.containsKey(cacheKey) || found.any { it.cacheKey == cacheKey }) continue
                            val memoryKey = MemoryCache.Key(cacheKey, extras)
                            if (verticalPrefetchImageLoader.memoryCache?.get(memoryKey) != null) continue
                            found += VerticalPosterPrefetch(index, url, extras, wPx, hPx, cacheKey)
                        }
                    }
                    found
                }
                for (candidate in candidates) {
                    if (inFlight.containsKey(candidate.cacheKey)) continue
                    if (inFlight.size >= verticalPosterPrefetchInFlightLimit(verticalRowListState.isScrollInProgress)) {
                        snapshotFlow { verticalRowListState.isScrollInProgress }.first { !it }
                        if (inFlight.size >= verticalPosterPrefetchInFlightLimit(scrolling = false)) break
                    }
                    val priority =
                        com.nuvio.tv.core.image.HomePosterPriority(imageViewport, candidate.row, prefetch = true)
                    val job = prefetchScope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                        verticalPrefetchImageLoader.execute(
                            ImageRequest.Builder(context)
                                .data(candidate.url)
                                .homePosterPriority(priority)
                                .memoryCacheKey(candidate.cacheKey)
                                .memoryCacheKeyExtras(candidate.extras)
                                .scale(coil3.size.Scale.FILL)
                                .size(width = candidate.width, height = candidate.height)
                                .build()
                        )
                    }
                    val entry = candidate.row to job
                    inFlight[candidate.cacheKey] = entry
                    job.invokeOnCompletion { inFlight.remove(candidate.cacheKey, entry) }
                    job.start()
                }
            }
    }

    val latestOnRequestLazyCatalogLoad = rememberUpdatedState(onRequestLazyCatalogLoad)
    val latestCarouselRowsForLazy = rememberUpdatedState(carouselRows)
    LaunchedEffect(verticalRowListState) {
        val prefetchAheadForLazy = 2
        snapshotFlow { visibleRowRange.value }.collectLatest { (firstVisible, lastVisible) ->
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
                    if (event.type == KeyEventType.KeyDown &&
                        event.nativeKeyEvent.repeatCount == 0 &&
                        (event.key == Key.DirectionDown || event.key == Key.DirectionUp)) {
                        // A press while the last move is still on its way continues from the row it is heading to.
                        val fromRow = verticalMoveSource(
                            activeRowKey.value, verticalHeading.rowKey,
                            android.os.SystemClock.uptimeMillis() - verticalHeading.atMs
                        )
                        val heading = fromRow != activeRowKey.value
                        val currentIndex = carouselRows.list.indexOfFirst { it.key == fromRow }
                        val targetIndex = adjacentHomeRowIndex(currentIndex, carouselRows.list.size,
                            event.key == Key.DirectionDown)
                        if (targetIndex != null) {
                            val target = carouselRows.list[targetIndex]
                            val column = if (heading) verticalHeading.column else focusedCardCenter()
                            verticalHeading.rowKey = target.key
                            verticalHeading.column = column
                            verticalHeading.atMs = android.os.SystemClock.uptimeMillis()
                            verticalFocusJob?.cancel()
                            verticalFocusJob = navigationScope.launch {
                                val attached = verticalRowListState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
                                if (!attached) {
                                    verticalRowListState.scrollToItem(targetIndex)
                                    withFrameNanos { }
                                }
                                // Wait for the row to be laid out, so its own scroll decides which card is in the spot.
                                for (frame in 0 until 6) {
                                    if (rowListStatesMap[target.key]?.layoutInfo?.visibleItemsInfo?.isNotEmpty() == true) break
                                    withFrameNanos { }
                                }
                                val (saved, reason) = columnTarget(target, column)
                                // The row remembers this card too, so nothing that restores a row's card takes focus elsewhere.
                                focusedItemByRowMap[target.key] = saved
                                var itemTarget = stableItemFocusRequestersByRow[target.key]?.value?.get(saved)
                                if (itemTarget == null || rowListStatesMap[target.key]?.layoutInfo?.visibleItemsInfo?.none { it.index == saved } == true) {
                                    // Off screen: bring it in under the column rather than to the row's start.
                                    val start = column?.let { it - (rowCardWidthPx(target) ?: 0) / 2 }?.coerceAtLeast(0) ?: 0
                                    rowListStatesMap[target.key]?.scrollToItem(saved, -start)
                                    repeat(2) { withFrameNanos { } }
                                    itemTarget = stableItemFocusRequestersByRow[target.key]?.value?.get(saved)
                                }
                                val moved = runCatching { itemTarget?.requestFocus() == true }.getOrDefault(false)
                                logColumnMove(fromRow, column, target.key, saved, if (moved) reason else "$reason+row")
                                if (!moved) runCatching { rowFocusRequesters[target.key]?.requestFocus() }
                            }
                            return@onPreviewKeyEvent true
                        }
                        // Let Up at the first row reach top navigation; Down at the end stays put.
                        if (isV2 && event.key == Key.DirectionDown) return@onPreviewKeyEvent true
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
                            val column = focusedCardCenter()
                            val (savedIdx, reason) = columnTarget(targetRow, column)
                            logColumnMove(activeRowKey.value, column, targetRow.key, savedIdx, "hold-$reason")
                            verticalHeading.rowKey = null
                            // The landing card claims focus from the row's remembered card: point that at the column.
                            focusedItemByRowMap[targetRow.key] = savedIdx
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
                            onHeroFocusSettleDelayChange(heroSettleDelayMs(now - lastHeroNavigationAtMs.value))
                            imageViewport.lastFocusMoveAtMs = android.os.SystemClock.uptimeMillis()
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
                    focusStateCatalogRowScrollOffset = focusState.catalogRowScrollOffsets[row.key] ?: 0,
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
                    addonLandscapeBackdropLogo = addonLandscapeBackdropLogo,
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

private class VerticalPosterPrefetch(
    val row: Int,
    val url: String,
    val extras: Map<String, String>,
    val width: Int,
    val height: Int,
    val cacheKey: String
)

/** Where the last Up or Down is heading while focus has not arrived there yet. */
private class VerticalHeading {
    var rowKey: String? = null
    var column: Int? = null
    var atMs = 0L
}
