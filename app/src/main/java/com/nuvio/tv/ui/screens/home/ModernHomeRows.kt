@file:OptIn(ExperimentalFoundationApi::class, kotlinx.coroutines.FlowPreview::class, coil3.annotation.ExperimentalCoilApi::class)

package com.nuvio.tv.ui.screens.home

import androidx.compose.ui.unit.Constraints
import com.nuvio.tv.ui.v2.components.v2FocusDrawsOwnEdge
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.SideEffect
import androidx.compose.animation.core.LinearEasing
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Stable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import com.nuvio.tv.ui.theme.NuvioTheme

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.layout.LazyLayoutPrefetchState
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.memory.MemoryCache
import com.nuvio.tv.core.image.homePosterPriority
import com.nuvio.tv.core.image.homePosterAttempt
import com.nuvio.tv.core.image.homePosterCacheKey
import com.nuvio.tv.core.poster.failedWithoutFallback
import com.nuvio.tv.core.poster.posterFallbackUrl
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.request.transformations
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.CardDepthSurface
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.domain.model.isPlaceholder
import com.nuvio.tv.ui.components.ContinueWatchingCard
import com.nuvio.tv.ui.components.continueWatchingArtworkWidth
import com.nuvio.tv.ui.components.continueWatchingImageCacheKey
import com.nuvio.tv.ui.components.continueWatchingImageModel
import com.nuvio.tv.ui.components.continueWatchingShouldBlur
import com.nuvio.tv.ui.components.continueWatchingUsesEpisodeThumbnails
import com.nuvio.tv.ui.components.LocalCardDepthStyle
import com.nuvio.tv.ui.components.FittedPortraitArtwork
import com.nuvio.tv.ui.components.LocalLogoOverCardTrailer
import com.nuvio.tv.ui.components.cardTitleOverlayVisible
import com.nuvio.tv.ui.components.MonochromePosterPlaceholder
import com.nuvio.tv.ui.components.NuvioCard
import com.nuvio.tv.ui.components.portraitArtworkBackdrop
import com.nuvio.tv.ui.components.portraitArtworkBackdropFilter
import com.nuvio.tv.ui.components.rememberPortraitArtworkState
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.components.LocalDimWatchedPosters
import com.nuvio.tv.ui.components.WatchedMarker
import com.nuvio.tv.ui.components.WatchedPosterVeil
import com.nuvio.tv.ui.components.watchedPosterDim
import com.nuvio.tv.ui.components.watchedPosterFade
import com.nuvio.tv.ui.components.watchedPosterFilter
import com.nuvio.tv.ui.components.placeholderCardShimmer
import com.nuvio.tv.ui.components.nuvioCardDepth
import com.nuvio.tv.ui.components.rememberArtworkBackedFocusGlow
import com.nuvio.tv.ui.components.rememberPlaceholderShimmerOffsetState
import com.nuvio.tv.ui.components.wideFolderCoverNeedsCrop
import com.nuvio.tv.LocalSidebarExpanded
import kotlin.math.abs
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import com.nuvio.tv.ui.util.recompositionHighlighter
import com.nuvio.tv.ui.util.StableMap
import com.nuvio.tv.ui.util.StableRef
import com.nuvio.tv.ui.util.asStable
import com.nuvio.tv.ui.util.contentTextDirection
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce

private const val MODERN_HORIZONTAL_FOCUS_DEBOUNCE_MS = 250L
private const val POSTER_PREFETCH_DISTANCE = 5
private const val NESTED_PREFETCH_COUNT = 2
private const val NESTED_PREFETCH_MAX = 12
private const val POSTER_TRAVEL_PREFETCH = 3
private const val ROW_FOCUS_TAG = "HomeRowFocus"

/** How many cards of a row are on screen at once, whole or cut off, kept between [minimum] and [maximum]. */
internal fun nestedPrefetchCountFor(
    viewportWidthPx: Int,
    cardWidthPx: Int,
    spacingPx: Int,
    minimum: Int,
    maximum: Int
): Int {
    val step = cardWidthPx + spacingPx.coerceAtLeast(0)
    if (viewportWidthPx <= 0 || cardWidthPx <= 0 || maximum <= minimum) return minimum
    val onScreen = (viewportWidthPx + step - 1) / step
    return onScreen.coerceIn(minimum, maximum)
}

/** A focused poster only grows when its trailer plays inside it, or when no trailer plays at all. */
internal fun focusedPosterGrows(
    expandEnabled: Boolean,
    autoplayEnabled: Boolean,
    trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget
): Boolean = expandEnabled &&
    (!autoplayEnabled || trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD)

/**
 * Keeps a few posters ready past the edge a row last moved towards, so a step along the row finds
 * the next poster already built instead of building it on the press. Rows nobody has moved along
 * prepare nothing beyond the nested prefetch.
 */
private class RowTravelPrefetchStrategy(
    // Cards to prepare for a row that has not been on screen yet, so they are not all built when it arrives.
    var unseenRowCards: Int = NESTED_PREFETCH_COUNT
) : LazyListPrefetchStrategy {
    private val handles = HashMap<Int, LazyLayoutPrefetchState.PrefetchHandle>()
    private var forward = true
    private var moved = false
    private var itemCount = -1

    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) {
        if (delta == 0f) return
        val nowForward = delta < 0f
        if (moved && nowForward != forward) cancelAll()
        forward = nowForward
        moved = true
        schedule(this, layoutInfo)
        val visible = layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return
        val distance = if (forward) {
            visible.last().let { it.offset + it.size } + layoutInfo.mainAxisItemSpacing - layoutInfo.viewportEndOffset
        } else {
            layoutInfo.viewportStartOffset - visible.first().offset + layoutInfo.mainAxisItemSpacing
        }
        if (distance < kotlin.math.abs(delta)) {
            val next = if (forward) visible.last().index + 1 else visible.first().index - 1
            handles[next]?.markAsUrgent()
        }
    }

    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) {
        if (moved) schedule(this, layoutInfo)
    }

    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {
        val count = nestedPrefetchItemCount.takeIf { it != -1 } ?: unseenRowCards
        repeat(count) { schedulePrecomposition(firstVisibleItemIndex + it) }
    }

    private fun schedule(scope: LazyListPrefetchScope, layoutInfo: LazyListLayoutInfo) {
        val visible = layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return
        // Fewer items: the prepared posters may belong to what was there before. More items
        // (the next page) leave the prepared ones where they are.
        if (layoutInfo.totalItemsCount < itemCount) cancelAll()
        itemCount = layoutInfo.totalItemsCount
        val first: Int
        val last: Int
        if (forward) {
            first = visible.last().index + 1
            last = minOf(visible.last().index + POSTER_TRAVEL_PREFETCH, layoutInfo.totalItemsCount - 1)
        } else {
            first = maxOf(visible.first().index - POSTER_TRAVEL_PREFETCH, 0)
            last = visible.first().index - 1
        }
        // Handles of posters now on screen or behind are spent; cancelling a used one does nothing.
        val stale = handles.keys.filter { it < first || it > last }
        stale.forEach { handles.remove(it)?.cancel() }
        for (index in first..last) {
            if (index !in handles) handles[index] = scope.schedulePrefetch(index)
        }
    }

    private fun cancelAll() {
        handles.values.forEach { it.cancel() }
        handles.clear()
    }

    /** The row left the screen and its posters went with it: start over when it comes back. */
    fun reset() {
        cancelAll()
        moved = false
        itemCount = -1
    }
}

// Main thread only. The list states outlive their rows, so the strategies are found through them.
private val rowTravelPrefetch = java.util.WeakHashMap<LazyListState, RowTravelPrefetchStrategy>()

internal val LocalVerticalRowsScrolling = compositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/**
 * True while the user is actively "fast-scrolling" — i.e. holding DPAD_LEFT/RIGHT or
 * DPAD_UP/DOWN and the LazyColumn-level key handler has taken over to drag the list
 * programmatically instead of letting [androidx.compose.ui.focus.FocusManager.moveFocus]
 * pull focus card-by-card. Cards use this to suppress their focus chrome (border / glow /
 * GIF) during the drag; the chrome snaps back onto whichever card focus lands on when
 * the user releases the key. Defaults to `false`, so any card used outside a modern
 * home row keeps its normal focus visuals.
 */
internal val LocalFastScrollActive = compositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/**
 * One focus ring per row, drawn by the row over all of its cards. When focus moves, the ring glides from
 * where it is to where the newly focused card will rest once the row has scrolled, and waits there until
 * the card arrives, so a card that is still sliding in, or not on screen yet, never takes the ring with it.
 */
@Stable
internal class RowFocusGlide {
    // Ring position in the row's item coordinates (the same as LazyListItemInfo.offset).
    private val ringX = Animatable(0f)
    private var gliding by mutableStateOf(false)
    var showing by mutableStateOf(false)
        private set
    var ringSize by mutableStateOf(IntSize.Zero)
    var ringShape by mutableStateOf<Shape>(RectangleShape)
    private var listState: LazyListState? = null
    private var focusedIndex = -1
    // The focused card by key too: its index changes when items are added or moved in front of it.
    private var focusedKey: Any? = null
    private var lastMoveAt = 0L
    private var moves = 0

    /** Call from the card's focus callback, before the row starts scrolling to it. */
    fun moveTo(index: Int, listState: LazyListState, scope: CoroutineScope) {
        this.listState = listState
        val previous = focusedIndex
        val from = if (previous >= 0 && showing) ringPosition() else null
        focusedIndex = index
        val info = listState.layoutInfo
        focusedKey = info.visibleItemsInfo.firstOrNull { it.index == index }?.key
        val card = cardOffset(index, info) ?: return
        showing = true
        val move = ++moves
        // A held key moves focus faster than the glide: take exactly the time between steps, at an even
        // speed, so the ring lands on each spot before the next step instead of trailing behind.
        val now = SystemClock.uptimeMillis()
        val gap = now - lastMoveAt
        lastMoveAt = now
        if (from == null || previous == index) {
            gliding = false
            scope.launch(start = CoroutineStart.UNDISPATCHED) { ringX.snapTo(card.first.toFloat()) }
            return
        }
        val to = restingOffset(card.first, card.second, info)
        val glide = if (gap < ROW_GLIDE_MS) {
            tween<Float>(gap.toInt().coerceAtLeast(MIN_HELD_GLIDE_MS), easing = LinearEasing)
        } else {
            tween(ROW_GLIDE_MS, easing = LinearOutSlowInEasing)
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ringX.snapTo(from)
            gliding = true
            ringX.animateTo(to, glide)
            // Stay where the card will rest until the row has finished sliding it there.
            snapshotFlow { listState.isScrollInProgress }.first { !it }
            if (move != moves) return@launch
            // Rows with cards of different widths can rest a little elsewhere: glide the rest of the way.
            val rest = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset?.toFloat()
            if (rest != null && abs(rest - ringX.value) > 1f) {
                ringX.animateTo(rest, tween(SETTLE_GLIDE_MS, easing = LinearOutSlowInEasing))
            }
            if (move == moves) gliding = false
        }
    }

    fun clear() {
        focusedIndex = -1
        focusedKey = null
        moves++
        gliding = false
        showing = false
    }

    /** Where the ring is drawn, in item coordinates; read while placing the ring only. */
    fun ringPosition(): Float {
        val info = listState?.layoutInfo
        if (gliding || info == null) return ringX.value
        val key = focusedKey
        val card = info.visibleItemsInfo.firstOrNull { if (key != null) it.key == key else it.index == focusedIndex }
        if (card != null) {
            focusedIndex = card.index
            focusedKey = card.key
            return card.offset.toFloat()
        }
        // Scrolled off screen without a focus move: keep to where it would be, not over another card.
        return cardOffset(focusedIndex, info)?.first?.toFloat() ?: ringX.value
    }

    // Offset and size of the card at [index], worked out from its neighbours when it is not on screen yet.
    private fun cardOffset(index: Int, info: LazyListLayoutInfo): Pair<Int, Int>? {
        val items = info.visibleItemsInfo
        items.firstOrNull { it.index == index }?.let { return it.offset to it.size }
        val ref = items.minByOrNull { abs(it.index - index) } ?: return null
        return ref.offset + (index - ref.index) * (ref.size + info.mainAxisItemSpacing) to ref.size
    }

    // The row scrolls only to keep the focused card on screen, as far as it can still scroll.
    private fun restingOffset(offset: Int, size: Int, info: LazyListLayoutInfo): Float {
        val items = info.visibleItemsInfo
        val span = size + info.mainAxisItemSpacing
        val end = info.viewportEndOffset - info.afterContentPadding
        val wanted = when {
            offset < 0 -> offset
            offset + size > end -> offset + size - end
            else -> 0
        }
        val scroll = if (wanted >= 0) {
            val last = items.last()
            val contentEnd = last.offset + last.size + (info.totalItemsCount - 1 - last.index) * span
            minOf(wanted, maxOf(0, contentEnd - end))
        } else {
            val first = items.first()
            maxOf(wanted, minOf(0, first.offset - first.index * span))
        }
        return (offset - scroll).toFloat()
    }
}

internal const val ROW_GLIDE_MS = 180
private const val SETTLE_GLIDE_MS = 120
private val ROW_END_PADDING = 52.dp
private const val MIN_HELD_GLIDE_MS = 32

internal val LocalRowFocusGlide = staticCompositionLocalOf<RowFocusGlide?> { null }

/** A card's V2 focus ring: drawn by its row when it sits in one, otherwise by the card itself. */
@Composable
internal fun Modifier.rowFocusRing(focused: Boolean, shape: Shape): Modifier {
    val glide = LocalRowFocusGlide.current
    if (glide == null || v2FocusDrawsOwnEdge()) return nuvioV2Focus(focused, shape, stationary = true)
    if (!focused) return this
    SideEffect { glide.ringShape = shape }
    val reportSize = remember(glide) { { size: IntSize -> glide.ringSize = size } }
    return onSizeChanged(reportSize)
}

/** The row's focus ring, over all of its cards. */
@Composable
private fun RowFocusRing(glide: RowFocusGlide, startPaddingPx: Int) {
    if (!glide.showing || v2FocusDrawsOwnEdge()) return
    Box(
        Modifier
            .offset { IntOffset(startPaddingPx + glide.ringPosition().roundToInt(), 0) }
            // Sized while laying out, in the same pass that measures the focused card.
            .layout { measurable, _ ->
                val size = glide.ringSize
                val placeable = measurable.measure(Constraints.fixed(size.width, size.height))
                layout(size.width, size.height) { if (size != IntSize.Zero) placeable.place(0, 0) }
            }
            .nuvioV2Focus(focused = true, shape = glide.ringShape, stationary = true)
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ModernContinueWatchingRowItem(
    payload: ModernPayload.ContinueWatching,
    requester: FocusRequester,
    isTargetItem: Boolean = false,
    mayClaimFocus: () -> Boolean = { true },
    cardWidth: Dp,
    imageHeight: Dp,
    blurUnwatchedEpisodes: Boolean,
    useEpisodeThumbnails: Boolean,
    continueWatchingCardStyle: ContinueWatchingCardStyle,
    continueWatchingCornerRadius: Dp,
    onFocused: () -> Unit,
    onFocusGained: () -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onShowOptions: (ContinueWatchingItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val item = payload.item
    val onClick = remember(item) { { onContinueWatchingClick(item) } }
    val onLongPress = remember(item) { { onShowOptions(item) } }
    var focusEventId by remember { mutableIntStateOf(0) }
    var isCardFocused by remember { mutableStateOf(false) }
    val latestOnFocused by rememberUpdatedState(onFocused)

    // Only the focused card runs this, so cards scrolled past start no job of their own.
    if (isCardFocused && focusEventId != 0) {
        LaunchedEffect(focusEventId) {
            // Update global focus state immediately so "self-claiming" logic in other items
            // knows this item is now the one in charge.
            latestOnFocused()
        }
    }

    if (isTargetItem) {
        LaunchedEffect(Unit) {
            if (!isCardFocused && mayClaimFocus()) runCatching { requester.requestFocus() }
        }
    }

    ContinueWatchingCard(
        item = item,
        onClick = onClick,
        onLongPress = onLongPress,
        cardWidth = cardWidth,
        imageHeight = imageHeight,
        blurUnwatchedEpisodes = blurUnwatchedEpisodes,
        useEpisodeThumbnails = useEpisodeThumbnails,
        cardStyle = continueWatchingCardStyle,
        cornerRadius = continueWatchingCornerRadius,
        isFocused = isCardFocused,
        modifier = modifier
            // The focused card draws last, so its gliding ring passes over its neighbours.
            .zIndex(if (isCardFocused) 1f else 0f)
            .focusRequester(requester)
            .onFocusChanged {
                isCardFocused = it.isFocused
                if (it.isFocused) {
                    focusEventId += 1
                    onFocusGained()
                }
            }
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ModernCatalogRowItem(
    item: ModernCarouselItem,
    payload: ModernPayload,
    requester: FocusRequester,
    isTargetItem: Boolean = false,
    mayClaimFocus: () -> Boolean = { true },
    useLandscapePosters: Boolean,
    alwaysShowLandscapeClearlogo: Boolean = false,
    addonLandscapeBackdropLogo: Boolean = false,
    showLabels: Boolean,
    placeholderShimmerOffsetState: State<Float>?,
    posterCardCornerRadius: Dp,
    portraitCatalogCardWidth: Dp,
    portraitCatalogCardHeight: Dp,
    landscapeCatalogCardWidth: Dp,
    landscapeCatalogCardHeight: Dp,
    focusedPosterBackdropTrailerMuted: Boolean,
    effectiveExpandEnabled: Boolean,
    effectiveAutoplayEnabled: Boolean,
    trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
    isBackdropExpanded: () -> Boolean,
    expandedTrailerPreviewUrl: () -> String?,
    expandedTrailerPreviewAudioUrl: () -> String?,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    isFocusTarget: Boolean = false,
    onFocused: () -> Unit,
    onFocusGained: () -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onPreloadAdjacentItem: () -> Unit,
    onCatalogSelectionFocused: (FocusedCatalogSelection) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit,
    onLongPress: () -> Unit,
    onBackdropInteraction: () -> Unit,
    onExpandedCatalogFocusKeyChange: (String?) -> Unit,
    enrichedPreviews: State<StableMap<String, MetaPreview>>,
    modifier: Modifier = Modifier
) {
    val focusKey = when (payload) {
        is ModernPayload.Catalog -> payload.focusKey
        is ModernPayload.CollectionFolder -> payload.focusKey
        is ModernPayload.ContinueWatching -> error("Unsupported payload for ModernCatalogRowItem")
    }

    val metaPreview = item.metaPreview
    val isWatched = metaPreview?.let { isCatalogItemWatched(it) } ?: false
    val enrichedMeta by remember((payload as? ModernPayload.Catalog)?.itemId) {
        derivedStateOf { (payload as? ModernPayload.Catalog)?.itemId?.let { enrichedPreviews.value.map[it] } }
    }
    val enrichedLogoUrl = enrichedMeta?.logo
    val enrichedBackdropUrl = enrichedMeta?.backdropUrl

    var focusEventId by remember { mutableIntStateOf(0) }
    var isCardFocused by remember { mutableStateOf(false) }
    val latestOnFocused by rememberUpdatedState(onFocused)
    val latestOnItemFocus by rememberUpdatedState(onItemFocus)
    val latestOnPreloadAdjacentItem by rememberUpdatedState(onPreloadAdjacentItem)
    val latestOnCatalogSelectionFocused by rememberUpdatedState(onCatalogSelectionFocused)

    // Bump focusEventId to re-trigger selection reporting.
    //LaunchedEffect(focusKey, isTargetItem) {
    //    if (isTargetItem && isCardFocused) {
    //        focusEventId++
    //    }
    //}

    // Only the focused poster runs this, so posters scrolled past start no job of their own.
    if (isCardFocused && focusEventId != 0) LaunchedEffect(focusEventId, focusKey) {
        // Update global focus state immediately so "self-claiming" logic in other items
        // knows this item is now the one in charge.
        latestOnFocused()

        delay(MODERN_HORIZONTAL_FOCUS_DEBOUNCE_MS)

        // Heavy "settled" work (trailers, enrichment) remains debounced.
        item.metaPreview?.let { latestOnItemFocus(it) }
        latestOnPreloadAdjacentItem()
        when (payload) {
            is ModernPayload.Catalog -> {
                if (!payload.itemId.startsWith("__placeholder_")) {
                    latestOnCatalogSelectionFocused(
                        FocusedCatalogSelection(
                            focusKey = focusKey,
                            payload = payload
                        )
                    )
                }
            }
            is ModernPayload.CollectionFolder -> {
                latestOnCatalogSelectionFocused(
                    FocusedCatalogSelection(
                        focusKey = focusKey,
                        payload = payload
                    )
                )
            }
            is ModernPayload.ContinueWatching -> Unit
        }
    }

    if (isTargetItem) {
        LaunchedEffect(Unit) {
            if (!isCardFocused && mayClaimFocus()) runCatching { requester.requestFocus() }
        }
    }

    val posterGrows = focusedPosterGrows(effectiveExpandEnabled, effectiveAutoplayEnabled, trailerPlaybackTarget)
    // Expansion is armed from a parent-level focusKey timer that can outlive real
    // card focus (e.g. user moves left into the sidebar). Never show the expanded
    // backdrop on a card that is not actually focused (#2815).
    val effectiveBackdropExpanded by remember(isBackdropExpanded, posterGrows) {
        derivedStateOf {
            isCardFocused && isBackdropExpanded() && posterGrows
        }
    }

    // Read by the one card that could play, so opening the menu does not rebuild every poster.
    val mayPlayTrailerInExpandedCard =
        effectiveAutoplayEnabled &&
            isCardFocused &&
            trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD &&
            effectiveBackdropExpanded
    val playTrailerInExpandedCard = mayPlayTrailerInExpandedCard && !LocalSidebarExpanded.current
    // Only the card playing the trailer reads its address, so a new trailer does not rebuild every poster.
    val trailerPreviewUrl = if (playTrailerInExpandedCard) expandedTrailerPreviewUrl() else null
    val trailerPreviewAudioUrl = if (playTrailerInExpandedCard) {
        expandedTrailerPreviewAudioUrl()
    } else {
        null
    }
    val cardMetrics = remember(
        item,
        useLandscapePosters,
        portraitCatalogCardWidth,
        portraitCatalogCardHeight,
        landscapeCatalogCardWidth,
        landscapeCatalogCardHeight
    ) {
        item.catalogCardMetrics(
            useLandscapePosters = useLandscapePosters,
            portraitCardWidth = portraitCatalogCardWidth,
            portraitCardHeight = portraitCatalogCardHeight,
            landscapeCardWidth = landscapeCatalogCardWidth,
            landscapeCardHeight = landscapeCatalogCardHeight
        )
    }

    ModernCarouselCard(
        item = item,
        useLandscapeOverlayTreatment = item.usesLandscapeCardTreatment(useLandscapePosters, addonLandscapeBackdropLogo),
        alwaysShowLandscapeClearlogo = alwaysShowLandscapeClearlogo,
        showLabels = showLabels,
        placeholderShimmerOffsetState = placeholderShimmerOffsetState,
        cardCornerRadius = posterCardCornerRadius,
        cardWidth = cardMetrics.width,
        cardHeight = cardMetrics.height,
        modifier = modifier,
        focusedPosterBackdropExpandEnabled = effectiveExpandEnabled,
        isBackdropExpanded = effectiveBackdropExpanded,
        playTrailerInExpandedCard = playTrailerInExpandedCard,
        focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
        trailerPreviewUrl = trailerPreviewUrl,
        trailerPreviewAudioUrl = trailerPreviewAudioUrl,
        isWatched = isWatched,
        enrichedLogoUrl = enrichedLogoUrl,
        enrichedBackdropUrl = enrichedBackdropUrl,
        focusRequester = requester,
        onFocused = {
            focusEventId += 1
            onFocusGained()
        },
        onFocusStateChanged = { focused ->
            isCardFocused = focused
        },
        onClick = {
            latestOnFocused()
            item.metaPreview?.let { latestOnItemFocus(it) }
            when (payload) {
                is ModernPayload.Catalog -> {
                    if (!payload.itemId.startsWith("__placeholder_")) {
                        // Capture the clicked title, not the deliberately delayed hero image.
                        HeroBackdropState.updateForTitle(
                            payload.itemId, payload.itemType,
                            enrichedBackdropUrl ?: item.metaPreview?.backdropUrl,
                            enrichedLogoUrl ?: item.metaPreview?.logo
                        )
                        onNavigateToDetail(
                            payload.itemId,
                            payload.itemType,
                            payload.addonBaseUrl
                        )
                    }
                }
                is ModernPayload.CollectionFolder -> {
                    onNavigateToFolderDetail(
                        payload.collectionId,
                        payload.folderId
                    )
                }
                is ModernPayload.ContinueWatching -> Unit
            }
        },
        onLongPress = onLongPress,
        onBackdropInteraction = onBackdropInteraction,
        onTrailerEnded = { onExpandedCatalogFocusKeyChange(null) }
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ModernRowSection(
    row: HeroCarouselRow,
    isActiveRow: () -> Boolean,
    rowFocusRequester: FocusRequester,
    isVerticalRowsScrollingState: State<Boolean>,
    rowTitleBottom: Dp,
    defaultBringIntoViewSpec: BringIntoViewSpec,
    focusStateCatalogRowScrollIndex: Int,
    focusStateCatalogRowScrollAnchor: String?,
    focusStateCatalogRowScrollOffset: Int = 0,
    focusedItemByRow: StableRef<MutableMap<String, Int>>,
    rowListStates: StableRef<MutableMap<String, LazyListState>>,
    loadMoreRequestedTotals: StableRef<MutableMap<String, Int>>,
    pendingRowFocusKey: State<String?>,
    pendingRowFocusIndex: State<Int?>,
    pendingRowFocusNonce: State<Int>,
    onPendingRowFocusCleared: () -> Unit,
    onRowItemFocused: (String, Int, Boolean) -> Unit,
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
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingOptions: (ContinueWatchingItem) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onPreloadAdjacentItem: (MetaPreview) -> Unit,
    enrichedPreviews: State<StableMap<String, MetaPreview>> = androidx.compose.runtime.mutableStateOf(StableMap()),
    onCatalogSelectionFocused: (FocusedCatalogSelection) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onBackdropInteraction: () -> Unit,
    onExpandedCatalogFocusKeyChange: (String?) -> Unit,
    sharedPlaceholderShimmerOffsetState: State<Float>?,
    itemFocusRequesters: StableRef<MutableMap<Int, FocusRequester>> = StableRef(mutableMapOf())
) {
    // Unwrap StableRef wrappers
    @Suppress("NAME_SHADOWING") val focusedItemByRow = focusedItemByRow.value
    @Suppress("NAME_SHADOWING") val rowListStates = rowListStates.value
    @Suppress("NAME_SHADOWING") val loadMoreRequestedTotals = loadMoreRequestedTotals.value
    @Suppress("NAME_SHADOWING") val itemFocusRequesters = itemFocusRequesters.value
    val rowKey = row.key

    // Per-row derived state: only invalidates when THIS row's focused index
    // changes, not when any other row's index changes in the shared map.
    val rowFocusedIndex = remember(rowKey) {
        derivedStateOf { focusedItemByRow[rowKey] ?: 0 }
    }

    // Blocks vertical focus exit during placeholder→data transition.
    val blockingFocusExit = remember { mutableStateOf(false) }

    // Item keys carry item identity, which the placeholder the ring sits on loses when real data
    // arrives: its key changes and Compose tears the focused node down. Lend that one card a
    // positional key for as long as the ring can be on it, so the node is reused instead.
    //
    // Armed from the item id, not a loading flag: a lazily loaded catalog composes its row
    // before loading starts and would never see the flag go up.
    val firstCardKey = remember(rowKey) { "${rowKey}__first" }
    val firstIsPlaceholder = (row.items.list.firstOrNull()?.payload as? ModernPayload.Catalog)
        ?.itemId?.startsWith("__placeholder_") == true
    val pinFirstCard = remember(rowKey) { mutableStateOf(firstIsPlaceholder) }
    val pinSpent = remember(rowKey) { mutableStateOf(false) }
    // Lent to the card, not the slot: slot 0 would hand the key to whatever lands there.
    val pinnedItemKey = remember(rowKey) { mutableStateOf<String?>(null) }
    if (firstIsPlaceholder && !pinSpent.value) pinFirstCard.value = true
    if (pinFirstCard.value && !firstIsPlaceholder && pinnedItemKey.value == null) {
        pinnedItemKey.value = row.items.list.firstOrNull()?.key
    }
    // Released once the ring has left the row: swapping the node earlier is visible for nothing.
    if (pinFirstCard.value && !firstIsPlaceholder && !isActiveRow()) {
        pinFirstCard.value = false
        pinnedItemKey.value = null
        pinSpent.value = true
    }
    Column(
        modifier = Modifier.then(
            if (blockingFocusExit.value) {
                Modifier.focusProperties {
                    up = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                }
            } else Modifier
        )
    ) {
        val rowTitle = row.title
        val rowLeadingInset = if (LocalV2Appearance.current != null) 24.dp else 52.dp
        val railHeaderModifier = remember(rowTitleBottom, rowLeadingInset) {
            Modifier
                .padding(start = rowLeadingInset, end = 52.dp, bottom = rowTitleBottom)
                .fillMaxWidth()
        }
        Text(
            text = rowTitle,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, textDirection = rowTitle.contentTextDirection()),
            color = NuvioTheme.colors.TextPrimary,
            modifier = railHeaderModifier
        )

        val firstRowItem = row.items.list.firstOrNull()
        val firstCardWidth = when {
            firstRowItem == null -> portraitCatalogCardWidth
            firstRowItem.payload is ModernPayload.ContinueWatching -> continueWatchingCardWidth
            else -> firstRowItem.catalogCardMetrics(
                useLandscapePosters = useLandscapePosters,
                portraitCardWidth = portraitCatalogCardWidth,
                portraitCardHeight = portraitCatalogCardHeight,
                landscapeCardWidth = landscapeCatalogCardWidth,
                landscapeCardHeight = landscapeCatalogCardHeight
            ).width
        }
        val screenWidthPx = LocalContext.current.resources.displayMetrics.widthPixels
        val cardSpacing = NuvioTheme.spacing.md
        val unseenRowCards = with(LocalDensity.current) {
            nestedPrefetchCountFor(
                viewportWidthPx = screenWidthPx - rowLeadingInset.roundToPx(),
                cardWidthPx = firstCardWidth.roundToPx(),
                spacingPx = cardSpacing.roundToPx(),
                minimum = NESTED_PREFETCH_COUNT,
                maximum = NESTED_PREFETCH_MAX
            )
        }.coerceAtMost(maxOf(row.items.list.size, NESTED_PREFETCH_COUNT))

        val rowListState = rowListStates.getOrPut(row.key) {
            // Resolved when the row is built, so a refresh that already moved the card is seen.
            val restoredIndex = focusStateCatalogRowScrollAnchor
                ?.let { anchor -> row.items.list.indexOfFirst { it.key == anchor } }
                ?.takeIf { it >= 0 }
                ?: focusStateCatalogRowScrollIndex
            val prefetch = RowTravelPrefetchStrategy(unseenRowCards)
            LazyListState(
                firstVisibleItemIndex = restoredIndex,
                firstVisibleItemScrollOffset = focusStateCatalogRowScrollOffset,
                prefetchStrategy = prefetch
            ).also { rowTravelPrefetch[it] = prefetch }
        }
        SideEffect { rowTravelPrefetch[rowListState]?.unseenRowCards = unseenRowCards }
        DisposableEffect(rowListState) {
            onDispose { rowTravelPrefetch[rowListState]?.reset() }
        }

        val firstItemKey = row.items.list.firstOrNull()?.key

        // When placeholder items are replaced by real data and this row
        // is the active row, re-request focus on the first real item.
        val firstItemImageUrl = row.items.list.firstOrNull()?.imageUrl
        val wasPlaceholderRef = remember { mutableStateOf(row.isLoading && firstItemImageUrl.isPlaceholder()) }
        val needsFocusRestore = remember { mutableStateOf(false) }
        
        LaunchedEffect(row.isLoading, firstItemImageUrl, isActiveRow) {
            val wasPlaceholder = wasPlaceholderRef.value
            val isNowReal = !row.isLoading || !firstItemImageUrl.isPlaceholder()
            if (wasPlaceholder && isNowReal && isActiveRow()) {
                needsFocusRestore.value = true
                blockingFocusExit.value = true
            }
            wasPlaceholderRef.value = row.isLoading && firstItemImageUrl.isPlaceholder()
        }

        // Restore focus after placeholder→data transition.
        LaunchedEffect(needsFocusRestore.value, row.key) {
            if (!needsFocusRestore.value) return@LaunchedEffect
            needsFocusRestore.value = false
            blockingFocusExit.value = false
        }

        val isRowScrollingState = remember(rowListState) {
            derivedStateOf { rowListState.isScrollInProgress }
        }
        val currentRowState = rememberUpdatedState(row)
        val loadMoreCatalogId = row.catalogId
        val loadMoreAddonId = row.addonId
        val loadMoreApiType = row.apiType
        val canObserveLoadMore = row.supportsSkip &&
            row.hasMore &&
            !loadMoreCatalogId.isNullOrBlank() &&
            !loadMoreAddonId.isNullOrBlank() &&
            !loadMoreApiType.isNullOrBlank()

        val keepFocusedCardInView = LocalV2Appearance.current != null
        LaunchedEffect(row.key, pendingRowFocusNonce.value) {
            if (pendingRowFocusKey.value != row.key) return@LaunchedEffect
            val targetIndex = (pendingRowFocusIndex.value ?: 0)
                .coerceIn(0, (row.items.list.size - 1).coerceAtLeast(0))
            if (rowListState.isScrollInProgress) return@LaunchedEffect
            if (keepFocusedCardInView) {
                // These rows only scroll to keep focus on screen: a restored row already showing the card stays as it was.
                val visible = snapshotFlow { rowListState.layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() }
                val info = rowListState.layoutInfo
                val end = info.viewportEndOffset - info.afterContentPadding
                if (visible.any { it.index == targetIndex && it.offset >= 0 && it.offset + it.size <= end }) {
                    return@LaunchedEffect
                }
            }
            runCatching { rowListState.scrollToItem(targetIndex) }
        }

        if (canObserveLoadMore) {
            LaunchedEffect(
                row.key,
                rowListState,
                canObserveLoadMore
            ) {
                snapshotFlow {
                    val layoutInfo = rowListState.layoutInfo
                    val total = layoutInfo.totalItemsCount
                    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    lastVisible to total
                }
                    .distinctUntilChanged()
                    .collect { (lastVisible, total) ->
                        if (total <= 0) return@collect
                        val rowState = currentRowState.value
                        val isNearEnd = lastVisible >= total - 4
                        if (!isNearEnd) {
                            loadMoreRequestedTotals.remove(rowState.key)
                            return@collect
                        }
                        val lastRequestedTotal = loadMoreRequestedTotals[rowState.key]
                        if (rowState.hasMore &&
                            !rowState.isLoading &&
                            lastRequestedTotal != total
                        ) {
                            loadMoreRequestedTotals[rowState.key] = total
                            onLoadMoreCatalog(
                                loadMoreCatalogId,
                                loadMoreAddonId,
                                loadMoreApiType
                            )
                        }
                    }
            }
        }

        val density = LocalDensity.current
        val rowStartPadding = if (LocalV2Appearance.current != null) 24.dp else 52.dp
        val context = LocalContext.current
        val imageLoader = context.imageLoader
        val homeImagePriority = com.nuvio.tv.core.image.LocalHomePosterPriority.current

        val rowItemCount = row.items.list.size
        LaunchedEffect(
            row.key,
            rowListState,
            homeImagePriority,
            useLandscapePosters,
            alwaysShowLandscapeClearlogo,
            addonLandscapeBackdropLogo,
            rowItemCount,
            effectiveExpandEnabled,
            portraitCatalogCardWidth,
            portraitCatalogCardHeight,
            landscapeCatalogCardWidth,
            landscapeCatalogCardHeight,
            continueWatchingCardWidth,
            continueWatchingCardHeight,
            useEpisodeThumbnails,
            blurUnwatchedEpisodes
        ) {
            // Read here, not as effect keys: the row does not rebuild when it becomes active or scrolling starts and stops.
            snapshotFlow { isActiveRow() && !isVerticalRowsScrollingState.value }
                .distinctUntilChanged()
                .collectLatest { ready ->
                    if (!ready) return@collectLatest
                    delay(150) // Wait before spamming image requests to survive rapid vertical D-pad scrolls!
                    val cwWidthPx = with(density) {
                        continueWatchingArtworkWidth(
                            continueWatchingCardStyle, continueWatchingCardWidth, continueWatchingCardHeight
                        ).roundToPx()
                    }
                    val cwHeightPx = with(density) { continueWatchingCardHeight.roundToPx() }
                    fun requestSizePx(item: ModernCarouselItem): Pair<Int, Int> {
                        val metrics = item.catalogCardRequestMetrics(
                            useLandscapePosters = useLandscapePosters,
                            portraitCardWidth = portraitCatalogCardWidth,
                            portraitCardHeight = portraitCatalogCardHeight,
                            landscapeCardWidth = landscapeCatalogCardWidth,
                            landscapeCardHeight = landscapeCatalogCardHeight,
                            expandEnabled = false
                        )
                        return with(density) { metrics.width.roundToPx() } to with(density) { metrics.height.roundToPx() }
                    }
                    suspend fun enqueueIfNeeded(item: ModernCarouselItem) {
                        val payload = item.payload
                        val model: String
                        val cacheKey: String
                        val widthPx: Int
                        val heightPx: Int
                        var blur = false
                        var extras = emptyMap<String, String>()
                        if (payload is ModernPayload.ContinueWatching) {
                            // Compute the same model and cache key ContinueWatchingCard reads
                            // (style-aware artwork source, artwork dimensions and the blur suffix)
                            // so this prefetch warms the entry the card will actually request.
                            val usesThumbs = continueWatchingUsesEpisodeThumbnails(continueWatchingCardStyle, useEpisodeThumbnails)
                            val nonCardStyle = continueWatchingCardStyle != ContinueWatchingCardStyle.CARD
                            model = continueWatchingImageModel(payload.item, usesThumbs, nonCardStyle) ?: return
                            blur = continueWatchingShouldBlur(payload.item, blurUnwatchedEpisodes, usesThumbs, nonCardStyle)
                            widthPx = cwWidthPx
                            heightPx = cwHeightPx
                            if (widthPx <= 0 || heightPx <= 0) return
                            cacheKey = continueWatchingImageCacheKey(model, widthPx, heightPx, blur)
                            extras = com.nuvio.tv.ui.components.continueWatchingFallbackExtras(payload.item, model)
                        } else {
                            val landscape = item.usesLandscapeCardTreatment(useLandscapePosters, addonLandscapeBackdropLogo)
                            val url = item.collapsedArtworkUrl(
                                landscape,
                                alwaysShowLandscapeClearlogo = alwaysShowLandscapeClearlogo
                            ) ?: return
                            val (w, h) = requestSizePx(item)
                            widthPx = w
                            heightPx = h
                            if (widthPx <= 0 || heightPx <= 0) return
                            // Warm the collapsed poster. Expanded backdrops have their own
                            // URL and target when focus settles; they must not size every poster.
                            model = url
                            cacheKey = homePosterCacheKey(url, widthPx, heightPx)
                            extras = item.customPosterCacheExtras(url, landscape)
                        }
                        if (imageLoader.memoryCache?.get(MemoryCache.Key(cacheKey, extras)) != null) return
                        // The loader runs its request steps on the caller's thread: keep them off the main thread.
                        withContext(Dispatchers.IO) {
                            imageLoader.execute(
                                ImageRequest.Builder(context)
                                    .data(model)
                                    .homePosterPriority(homeImagePriority?.copy(prefetch = true))
                                    .memoryCacheKey(cacheKey)
                                    .memoryCacheKeyExtras(extras)
                                    .scale(coil3.size.Scale.FILL)
                                    .size(width = widthPx, height = heightPx)
                                    .apply {
                                        if (blur) transformations(com.nuvio.tv.ui.util.BlurTransformation())
                                    }
                                    .build()
                            )
                        }
                    }
                    var previousFirst = -1
                    snapshotFlow {
                        val info = rowListState.layoutInfo
                        val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
                        val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                        first to last
                    }
                        .distinctUntilChanged()
                        .conflate()
                        .collect { (firstVisibleIndex, lastVisibleIndex) ->
                            val currentItems = currentRowState.value.items.list
                            val isCw = currentItems.firstOrNull()?.payload is ModernPayload.ContinueWatching
                            val indices = if (isCw) {
                                ((lastVisibleIndex + 1)..minOf(currentItems.lastIndex, lastVisibleIndex + POSTER_PREFETCH_DISTANCE)).toList() +
                                    (maxOf(0, firstVisibleIndex - POSTER_PREFETCH_DISTANCE) until firstVisibleIndex).toList()
                            } else com.nuvio.tv.core.image.homePosterWarmWindow(
                                currentItems.size, firstVisibleIndex, lastVisibleIndex, previousFirst
                            )
                            previousFirst = firstVisibleIndex
                            for (batch in indices.chunked(2)) {
                                val visible = rowListState.layoutInfo.visibleItemsInfo
                                if (visible.firstOrNull()?.index != firstVisibleIndex ||
                                    visible.lastOrNull()?.index != lastVisibleIndex) break
                                // Finish this small batch before following the latest viewport.
                                // collectLatest would cancel every download on every horizontal step.
                                coroutineScope {
                                    batch.forEach { i -> currentItems.getOrNull(i)?.let { launch { enqueueIfNeeded(it) } } }
                                }
                            }
                        }
                }
        }

        val layoutDirection = LocalLayoutDirection.current
        val isRtl = layoutDirection == LayoutDirection.Rtl
        val stationaryPosterFocus = LocalV2Appearance.current != null
        val horizontalBringIntoViewSpec = remember(density, defaultBringIntoViewSpec, rowStartPadding, isRtl, stationaryPosterFocus) {
            val parentStartOffsetPx = with(density) { rowStartPadding.roundToPx() }
            val parentEndOffsetPx = with(density) { ROW_END_PADDING.roundToPx() }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            object : BringIntoViewSpec {
                override val scrollAnimationSpec: AnimationSpec<Float> =
                    if (stationaryPosterFocus) tween(durationMillis = ROW_GLIDE_MS, easing = LinearOutSlowInEasing)
                    else defaultBringIntoViewSpec.scrollAnimationSpec

                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float
                ): Float {
                    val childSize = abs(size)
                    if (stationaryPosterFocus) {
                        // Move only as far as needed to keep the focused card on screen: the ring travels
                        // to the edge you are heading for, then stays there while the row slides.
                        val low = (if (isRtl) parentEndOffsetPx else parentStartOffsetPx).toFloat()
                        val high = containerSize - (if (isRtl) parentStartOffsetPx else parentEndOffsetPx)
                        return when {
                            childSize > high - low -> if (isRtl) offset + size - high else offset - low
                            offset < low -> offset - low
                            offset + size > high -> offset + size - high
                            else -> 0f
                        }
                    }
                    if (isRtl) {
                        val childSmallerThanParent = childSize <= containerSize
                        val initialTarget = containerSize - parentStartOffsetPx.toFloat()
                        val targetForTrailingEdge =
                            if (childSmallerThanParent && initialTarget < childSize) {
                                childSize
                            } else {
                                initialTarget
                            }
                        return (offset + size) - targetForTrailingEdge
                    } else {
                        val childSmallerThanParent = childSize <= containerSize
                        val initialTarget = parentStartOffsetPx.toFloat()
                        val spaceAvailable = containerSize - initialTarget

                        val targetForLeadingEdge =
                            if (childSmallerThanParent && spaceAvailable < childSize) {
                                containerSize - childSize
                            } else {
                                initialTarget
                            }

                        return offset - targetForLeadingEdge
                    }
                }
            }
        }

        // When a poster in this row expands, ensure it scrolls fully into view.
        var isExpansionScrollActive by remember { mutableStateOf(false) }
        val landscapeExpandedWidthPx = with(density) { landscapeCatalogCardWidth.roundToPx() }
        val portraitExpandedWidthPx = with(density) { (portraitCatalogCardHeight * (16f / 9f)).roundToPx() }
        // A poster held small for a trailer in the hero must not push the row along either.
        val posterGrows = focusedPosterGrows(effectiveExpandEnabled, effectiveAutoplayEnabled, trailerPlaybackTarget)
        LaunchedEffect(row.key, posterGrows, rowItemCount) {
            if (!posterGrows) return@LaunchedEffect
            snapshotFlow { expandedCatalogFocusKey.value }
                .collect { expandedKey ->
                    if (expandedKey == null) return@collect
                    // Find the index of the expanded item in this row
                    val expandedIndex = row.items.list.indexOfFirst { item ->
                        when (val p = item.payload) {
                            is ModernPayload.Catalog -> p.focusKey == expandedKey
                            is ModernPayload.CollectionFolder -> p.focusKey == expandedKey
                            else -> false
                        }
                    }
                    if (expandedIndex < 0) return@collect
                    // Small delay so the item is still in visible layout info
                    delay(50)
                    // Calculate overshoot using the known final expanded width rather than
                    // the mid-animation layout size which underestimates the trailing edge.
                    val layoutInfo = rowListState.layoutInfo
                    val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == expandedIndex }
                        ?: return@collect
                    val viewportEnd = layoutInfo.viewportEndOffset
                    val expandedItem = row.items.list[expandedIndex]
                    val expandedCardWidthPx = if (expandedItem.isAddonLandscapeCard() ||
                        expandedItem.usesLandscapeCardTreatment(useLandscapePosters, addonLandscapeBackdropLogo)
                    ) landscapeExpandedWidthPx else portraitExpandedWidthPx
                    val itemEndExpanded = itemInfo.offset + expandedCardWidthPx
                    if (itemEndExpanded > viewportEnd) {
                        // Scroll just enough to reveal the trailing edge plus a small margin.
                        // Flag prevents isBackdropExpandedLambda from collapsing during this scroll.
                        val overshoot = itemEndExpanded - viewportEnd + with(density) { 15.dp.roundToPx() }
                        isExpansionScrollActive = true
                        try {
                            rowListState.animateScrollBy(overshoot.toFloat())
                        } finally {
                            isExpansionScrollActive = false
                        }
                    }
                }
        }

        val rowHasFocus = remember(rowKey) { mutableStateOf(false) }
        val focusGlide = remember { RowFocusGlide() }
        val focusGlideScope = rememberCoroutineScope()
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides horizontalBringIntoViewSpec,
            LocalRowFocusGlide provides focusGlide
        ) {
            val usesPlaceholderShimmer = row.showsPlaceholderShimmer()
            val placeholderShimmerOffsetState = if (usesPlaceholderShimmer) {
                sharedPlaceholderShimmerOffsetState
            } else {
                null
            }

            Box {
            LazyRow(
                state = rowListState,
                modifier = Modifier
                    .recompositionHighlighter()
                    .focusRequester(rowFocusRequester)
                    // Entering the row goes to the card it is pointed at, also when that card asked for
                    // focus itself; the restorer alone would hand it to the card the row last had.
                    .focusProperties {
                        val restore = onEnter
                        onEnter = {
                            val entry = rowEntry(
                                pendingIndex = if (pendingRowFocusKey.value == rowKey) pendingRowFocusIndex.value ?: 0 else null,
                                pointedAt = focusedItemByRow[rowKey],
                                itemCount = currentRowState.value.items.list.size,
                                cardsAlwaysFocusable = rowKey == MODERN_CONTINUE_WATCHING_ROW_KEY ||
                                    rowKey == MODERN_UPCOMING_ROW_KEY || rowKey == MODERN_COMING_UP_ROW_KEY
                            )
                            val card = entry?.let { itemFocusRequesters[it.index] }
                            when {
                                card != null && card.requestFocus() -> Unit
                                entry?.waitForCard == true -> cancelFocusChange()
                                else -> restore()
                            }
                        }
                    }
                    .focusRestorer {
                        val savedIdx = rowFocusedIndex.value
                        itemFocusRequesters[savedIdx]
                            ?: itemFocusRequesters[0]
                            ?: FocusRequester.Default
                    }
                    .onFocusChanged {
                        rowHasFocus.value = it.hasFocus
                        if (!it.hasFocus) focusGlide.clear()
                    }
                    .focusGroup(),
                contentPadding = PaddingValues(start = rowStartPadding, end = ROW_END_PADDING),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                itemsIndexed(
                    items = row.items.list,
                    key = { index, item ->
                        val pinned = pinnedItemKey.value
                        when {
                            !pinFirstCard.value -> item.key
                            pinned != null -> if (item.key == pinned) firstCardKey else item.key
                            index == 0 -> firstCardKey
                            else -> item.key
                        }
                    },
                    contentType = { _, item ->
                        when (val payload = item.payload) {
                            is ModernPayload.ContinueWatching -> "modern_cw_card"
                            is ModernPayload.Catalog -> if (payload.itemId.startsWith("__placeholder_")) "placeholder" else payload.itemType
                            is ModernPayload.CollectionFolder -> "modern_collection_folder_card"
                        }
                    }
                ) { index, item ->
                    val requester = itemFocusRequesters.getOrPut(index) { FocusRequester() }
                    val isContinueWatchingRow = row.key == MODERN_CONTINUE_WATCHING_ROW_KEY || row.key == MODERN_UPCOMING_ROW_KEY ||
                        row.key == MODERN_COMING_UP_ROW_KEY
                    // Straight from the card's focus callback: the ring must start gliding before the row scrolls.
                    val onFocusGained = remember(row.key, index) {
                        { focusGlide.moveTo(index, rowListState, focusGlideScope) }
                    }
                    val onFocused = remember(row.key, index, isContinueWatchingRow) {
                        {
                            onRowItemFocused(row.key, index, isContinueWatchingRow)
                            if (pendingRowFocusKey.value != null &&
                                pendingAfterFocus(pendingRowFocusKey.value, pendingRowFocusIndex.value, row.key) == null
                            ) {
                                onPendingRowFocusCleared()
                            }
                        }
                    }
                    val mayClaimFocus = remember(row.key, index) {
                        {
                            val pending = if (pendingRowFocusKey.value == row.key) pendingRowFocusIndex.value ?: 0 else null
                            val hasFocus = rowHasFocus.value
                            Log.d(ROW_FOCUS_TAG, "claim row=${row.key} index=$index pending=$pending rowHasFocus=$hasFocus")
                            shouldClaimFocus(
                                isTarget = true,
                                cardFocused = false,
                                rowHasFocus = hasFocus,
                                isPendingTarget = pending == index
                            )
                        }
                    }

                    // Use derivedStateOf so only the ONE item that becomes/loses
                    // target status recomposes — not all items in all visible rows.
                    val isTargetItem by remember(row.key, index) {
                        derivedStateOf {
                            val isPending = pendingRowFocusKey.value == row.key &&
                                (pendingRowFocusIndex.value ?: 0) == index
                            val isCurrent = isActiveRow() &&
                                rowFocusedIndex.value == index
                            isPending || isCurrent
                        }
                    }

                    when (val payload = item.payload) {
                        is ModernPayload.ContinueWatching -> {
                            val comingUp = row.key == MODERN_COMING_UP_ROW_KEY
                            val openComingUp = remember(onNavigateToDetail) {
                                { cw: ContinueWatchingItem ->
                                    (cw as? ContinueWatchingItem.NextUp)?.info?.let { onNavigateToDetail(it.contentId, it.contentType, "") }
                                    Unit
                                }
                            }
                            ModernContinueWatchingRowItem(
                                payload = payload,
                                requester = requester,
                                isTargetItem = isTargetItem,
                                mayClaimFocus = mayClaimFocus,
                                cardWidth = continueWatchingCardWidth,
                                imageHeight = continueWatchingCardHeight,
                                blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                                useEpisodeThumbnails = useEpisodeThumbnails,
                                continueWatchingCardStyle = continueWatchingCardStyle,
                                continueWatchingCornerRadius = continueWatchingCornerRadius,
                                onFocused = onFocused,
                                onFocusGained = onFocusGained,
                                onContinueWatchingClick = if (comingUp) openComingUp else onContinueWatchingClick,
                                onShowOptions = onContinueWatchingOptions
                            )
                        }

                        is ModernPayload.Catalog,
                        is ModernPayload.CollectionFolder -> {
                            val nextCatalogItem = row.items.list.getOrNull(index + 1)?.metaPreview
                            val prevCatalogItem = row.items.list.getOrNull(index - 1)?.metaPreview
                            val metaPreview = item.metaPreview
                            val isPlaceholder = payload is ModernPayload.Catalog &&
                                payload.itemId.startsWith("__placeholder_")
                            val onLongPress: () -> Unit = when {
                                payload is ModernPayload.Catalog && metaPreview != null -> remember(metaPreview, payload.addonBaseUrl) {
                                    {
                                        onCatalogItemLongPress(metaPreview, payload.addonBaseUrl)
                                        Unit
                                    }
                                }
                                else -> remember(Unit) { {} }
                            }
                            val expandedFocusKey = when (payload) {
                                is ModernPayload.Catalog -> payload.focusKey
                                is ModernPayload.CollectionFolder -> payload.focusKey
                            }
                            val isBackdropExpandedState = remember(
                                effectiveExpandEnabled,
                                isRowScrollingState,
                                expandedCatalogFocusKey,
                                expandedFocusKey
                            ) {
                                derivedStateOf {
                                    effectiveExpandEnabled &&
                                        (!isRowScrollingState.value || isExpansionScrollActive) &&
                                        expandedCatalogFocusKey.value == expandedFocusKey
                                }
                            }
                            val isBackdropExpandedLambda = remember(isBackdropExpandedState) {
                                { isBackdropExpandedState.value }
                            }
                            val placeholderFocusBlock = isPlaceholder && index > 0
                            ModernCatalogRowItem(
                                item = item,
                                payload = payload,
                                requester = requester,
                                isTargetItem = isTargetItem,
                                mayClaimFocus = mayClaimFocus,
                                useLandscapePosters = useLandscapePosters,
                                alwaysShowLandscapeClearlogo = alwaysShowLandscapeClearlogo,
                                addonLandscapeBackdropLogo = addonLandscapeBackdropLogo,
                                showLabels = showLabels,
                                placeholderShimmerOffsetState = placeholderShimmerOffsetState,
                                posterCardCornerRadius = posterCardCornerRadius,
                                focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
                                effectiveExpandEnabled = effectiveExpandEnabled,
                                effectiveAutoplayEnabled = effectiveAutoplayEnabled,
                                trailerPlaybackTarget = trailerPlaybackTarget,
                                isBackdropExpanded = isBackdropExpandedLambda,
                                expandedTrailerPreviewUrl = expandedTrailerPreviewUrl,
                                expandedTrailerPreviewAudioUrl = expandedTrailerPreviewAudioUrl,
                                portraitCatalogCardWidth = portraitCatalogCardWidth,
                                portraitCatalogCardHeight = portraitCatalogCardHeight,
                                landscapeCatalogCardWidth = landscapeCatalogCardWidth,
                                landscapeCatalogCardHeight = landscapeCatalogCardHeight,
                                isCatalogItemWatched = isCatalogItemWatched,
                                onFocused = onFocused,
                                onFocusGained = onFocusGained,
                                onItemFocus = onItemFocus,
                                onPreloadAdjacentItem = remember(nextCatalogItem, prevCatalogItem, onPreloadAdjacentItem) {
                                     {
                                         nextCatalogItem?.let(onPreloadAdjacentItem)
                                         prevCatalogItem?.let(onPreloadAdjacentItem)
                                     }
                                },
                                onCatalogSelectionFocused = onCatalogSelectionFocused,
                                onNavigateToDetail = onNavigateToDetail,
                                onNavigateToFolderDetail = onNavigateToFolderDetail,
                                onLongPress = onLongPress,
                                onBackdropInteraction = onBackdropInteraction,
                                onExpandedCatalogFocusKeyChange = onExpandedCatalogFocusKeyChange,
                                enrichedPreviews = enrichedPreviews,
                                // The card is the row item itself, so its zIndex puts the focused one over its neighbours.
                                modifier = if (placeholderFocusBlock) {
                                    Modifier.focusProperties { canFocus = false }
                                } else Modifier
                            )
                        }
                    }
                }
            }
            RowFocusRing(focusGlide, with(density) { rowStartPadding.roundToPx() })
            }
        }
    }
}


/** Reads the watched fade here, so each step of it rebuilds the artwork alone and not the whole card. */
@Composable
private fun WatchedPosterTint(dim: State<Float>, content: @Composable (ColorFilter?) -> Unit) {
    content(watchedPosterFilter(dim.value))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ModernCarouselCard(
    item: ModernCarouselItem,
    useLandscapeOverlayTreatment: Boolean,
    alwaysShowLandscapeClearlogo: Boolean = false,
    showLabels: Boolean,
    placeholderShimmerOffsetState: State<Float>? = null,
    cardCornerRadius: Dp,
    cardWidth: Dp,
    cardHeight: Dp,
    focusedPosterBackdropExpandEnabled: Boolean,
    isBackdropExpanded: Boolean,
    playTrailerInExpandedCard: Boolean,
    focusedPosterBackdropTrailerMuted: Boolean,
    trailerPreviewUrl: String?,
    trailerPreviewAudioUrl: String?,
    isWatched: Boolean,
    enrichedLogoUrl: String? = null,
    enrichedBackdropUrl: String? = null,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onFocusStateChanged: (Boolean) -> Unit = {},
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onBackdropInteraction: () -> Unit,
    onTrailerEnded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isV2 = LocalV2Appearance.current != null
    val cardShape = remember(cardCornerRadius) { RoundedCornerShape(cardCornerRadius) }
    val cardDepthStyle = LocalCardDepthStyle.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val expandedCardWidth = if (useLandscapeOverlayTreatment) {
        cardWidth
    } else {
        cardHeight * (16f / 9f)
    }
    val targetCardWidth = if (focusedPosterBackdropExpandEnabled && isBackdropExpanded) {
        expandedCardWidth
    } else {
        cardWidth
    }
    // Only a card whose width is on the move carries an animation; the others keep a plain width.
    var restingCardWidth by remember { mutableStateOf(targetCardWidth) }
    var cardWidthMoving by remember { mutableStateOf(false) }
    if (!focusedPosterBackdropExpandEnabled) {
        restingCardWidth = cardWidth
        cardWidthMoving = false
    } else if (targetCardWidth != restingCardWidth) {
        cardWidthMoving = true
    }
    val animatedCardWidthState = if (focusedPosterBackdropExpandEnabled && cardWidthMoving) {
        val movingCardWidth = remember {
            Animatable(restingCardWidth, Dp.VectorConverter, Dp.VisibilityThreshold, label = "modernCardWidth")
        }
        LaunchedEffect(targetCardWidth) {
            movingCardWidth.animateTo(targetCardWidth, spring(visibilityThreshold = Dp.VisibilityThreshold))
            restingCardWidth = targetCardWidth
            cardWidthMoving = false
        }
        movingCardWidth.asState()
    } else {
        rememberUpdatedState(targetCardWidth)
    }
    // Freeze the logo URL for row cards - enrichment updates must not cause flickering.
    // The first non-blank value wins and is never replaced.
    // Primary source of truth is the data-layer frozen value (survives navigation);
    // the remember-state acts as a secondary guard within the same composition.
    val dataFrozenLogo = item.heroPreview.frozenLogoUrl?.takeIf { !it.isPlaceholder() }
    val frozenLogoUrl = remember(item.key) { mutableStateOf(dataFrozenLogo ?: item.heroPreview.logo?.takeIf { !it.isPlaceholder() }) }
    if ((frozenLogoUrl.value.isNullOrBlank() || frozenLogoUrl.value.isPlaceholder()) &&
        !item.heroPreview.logo.isNullOrBlank() && !item.heroPreview.logo.isPlaceholder()) {
        frozenLogoUrl.value = item.heroPreview.logo
    }
    // A frozen logo that failed to load gives way to the enriched one, so the card shows the logo the hero shows.
    var landscapeLogoLoadFailed by remember(frozenLogoUrl.value) { mutableStateOf(false) }
    if (!enrichedLogoUrl.isNullOrBlank() && frozenLogoUrl.value != enrichedLogoUrl) {
        // Outside landscape we always pick up the enriched URL so manual artwork
        // updates land instantly. Inside landscape we still adopt the enriched
        // URL when there was no logo to begin with — otherwise the card would
        // permanently fall back to the title text whenever the addon manifest
        // ships items without a logo even
        // though TMDB has one. Once we have any non-blank value we keep it
        // frozen to avoid mid-scroll flicker on enrichment refresh.
        if (!useLandscapeOverlayTreatment || frozenLogoUrl.value.isNullOrBlank() || landscapeLogoLoadFailed) {
            frozenLogoUrl.value = enrichedLogoUrl
        }
    }
    val effectiveLogoUrl = frozenLogoUrl.value?.takeIf { !it.isPlaceholder() }
    // Freeze the backdrop URL for landscape cards - prevents image reload when enrichment updates backdrop.
    val dataFrozenBackdrop = item.heroPreview.frozenBackdropUrl?.takeIf { !it.isPlaceholder() }
    val frozenBackdropUrl = remember(item.key) { mutableStateOf(dataFrozenBackdrop ?: item.heroPreview.backdrop?.takeIf { !it.isPlaceholder() }) }
    frozenBackdropUrl.value = item.cardBackdropUrl(
        current = frozenBackdropUrl.value,
        enrichedBackdrop = enrichedBackdropUrl,
        landscape = useLandscapeOverlayTreatment
    )
    val effectiveBackdropUrl = frozenBackdropUrl.value?.takeIf { !it.isPlaceholder() }
    var isFocused by remember { mutableStateOf(false) }
    val payload = item.payload as? ModernPayload.CollectionFolder
    val isCollectionFolder = item.payload is ModernPayload.CollectionFolder
    val effectiveIgnoreLandscapePoster = alwaysShowLandscapeClearlogo
    val useLandscapeAsExpanded = focusedPosterBackdropExpandEnabled && isBackdropExpanded &&
        !alwaysShowLandscapeClearlogo &&
        !item.metaPreview?.landscapePoster.isNullOrBlank()
    val baseImageUrl = if (focusedPosterBackdropExpandEnabled && isBackdropExpanded) {
        if (useLandscapeOverlayTreatment) {
            if (effectiveIgnoreLandscapePoster) {
                effectiveBackdropUrl ?: item.heroPreview.backdrop ?: item.imageUrl ?: item.heroPreview.poster
            } else {
                item.metaPreview?.landscapePoster ?: effectiveBackdropUrl ?: item.heroPreview.backdrop ?: item.imageUrl ?: item.heroPreview.poster
            }
        } else if (useLandscapeAsExpanded) {
            item.metaPreview?.landscapePoster
        } else {
            item.heroPreview.backdrop ?: item.imageUrl ?: item.heroPreview.poster
        }
    } else {
        item.collapsedArtworkUrl(useLandscapeOverlayTreatment, effectiveBackdropUrl, alwaysShowLandscapeClearlogo)
    }
    val mixRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val imageUrl = when {
        payload?.scoutMix != null -> (if (mixRtl) payload.scoutMix.deckMirrored else payload.scoutMix.deck) ?: baseImageUrl
        payload == null -> baseImageUrl
        !payload.focusGifEnabled -> baseImageUrl
        else -> baseImageUrl
    }
    // GIF overlay: shown on top of the base image only when focused and loaded
    val focusGifUrl = when {
        payload == null -> null
        !payload.focusGifEnabled -> null
        isFocused -> payload.focusGifUrl
        else -> null
    }
    var cropFolderCover by remember(imageUrl) { mutableStateOf(false) }
    val imageContentScale = when (item.payload) {
        is ModernPayload.CollectionFolder -> if (cropFolderCover) ContentScale.Crop else ContentScale.FillBounds
        else -> ContentScale.Crop
    }
    // Use the selected artwork's target, not the largest possible card. A portrait
    // decoded to the expanded landscape width uses ~7x the pixels it displays.
    // The target changes only at expansion boundaries, never on animation frames.
    val maxRequestCardWidth = if (focusedPosterBackdropExpandEnabled && isBackdropExpanded) {
        maxOf(cardWidth, expandedCardWidth)
    } else {
        cardWidth
    }
    val requestWidthPx = remember(maxRequestCardWidth, density) {
        with(density) { maxRequestCardWidth.roundToPx() }.coerceAtLeast(1)
    }
    val requestHeightPx = remember(cardHeight, density) {
        with(density) { cardHeight.roundToPx() }.coerceAtLeast(1)
    }

    val homeImagePriority = com.nuvio.tv.core.image.LocalHomePosterPriority.current
    val revalidationKey = com.nuvio.tv.core.image.rememberImageRevalidationKey(imageUrl)
    val showsLandscapeArtwork = useLandscapeOverlayTreatment ||
        (focusedPosterBackdropExpandEnabled && isBackdropExpanded)
    var failedWithoutFallback by remember(imageUrl) { mutableStateOf(false) }
    val lateFallbackUrl = if (failedWithoutFallback) {
        item.metaPreview?.posterFallbackUrl(imageUrl, landscapeCard = showsLandscapeArtwork)
    } else {
        null
    }
    var posterAttempt by remember(imageUrl, lateFallbackUrl) { mutableIntStateOf(0) }
    var posterRetryPending by remember(imageUrl, lateFallbackUrl) { mutableStateOf(false) }
    var posterFailed by remember(imageUrl, lateFallbackUrl) { mutableStateOf(false) }
    var customPosterLoadFailed by remember(imageUrl) { mutableStateOf(false) }
    val portraitArtwork = rememberPortraitArtworkState(imageUrl)
    val canFitPortraitArtwork = showsLandscapeArtwork && !isCollectionFolder
    val fitPortraitArtwork = canFitPortraitArtwork && portraitArtwork.isPortrait
    if (posterRetryPending) {
        LaunchedEffect(imageUrl, posterAttempt) {
            delay(com.nuvio.tv.core.image.HOME_POSTER_RETRY_DELAY_MS)
            posterAttempt++
            posterRetryPending = false
        }
    }
    val imageModel = remember(
        context, imageUrl, requestWidthPx, requestHeightPx, revalidationKey, homeImagePriority, posterAttempt,
        showsLandscapeArtwork, lateFallbackUrl
    ) {
        imageUrl?.let {
            val builder = ImageRequest.Builder(context)
                .data(it)
                .crossfade(false)
                .homePosterPriority(homeImagePriority)
                .homePosterAttempt(posterAttempt)
                .memoryCacheKey(homePosterCacheKey(it, requestWidthPx, requestHeightPx, revalidationKey))
                .placeholderMemoryCacheKey(homePosterCacheKey(it, requestWidthPx, requestHeightPx, revalidationKey))
                .scale(coil3.size.Scale.FILL)
                .size(width = requestWidthPx, height = requestHeightPx)
            if (revalidationKey > 0) {
                builder.placeholderMemoryCacheKey(homePosterCacheKey(it, requestWidthPx, requestHeightPx, revalidationKey - 1))
            }
            val fallbackExtras = item.customPosterCacheExtras(it, showsLandscapeArtwork)
            if (fallbackExtras.isNotEmpty()) builder.memoryCacheKeyExtras(fallbackExtras)
            builder.build()
        }
    }
    if (posterFailed && imageModel != null) {
        LaunchedEffect(imageModel) {
            com.nuvio.tv.core.image.recoverHomePoster(context, imageModel, homeImagePriority)
            posterFailed = false
        }
    }
    val logoHeight = cardHeight * 0.34f
    val logoHeightPx = remember(logoHeight, density) {
        with(density) { logoHeight.roundToPx() }.coerceAtLeast(1)
    }
    val maxLogoWidthPx = remember(maxRequestCardWidth, density) {
        with(density) { (maxRequestCardWidth * 0.62f).roundToPx() }.coerceAtLeast(1)
    }

    val shouldPlayTrailerInCard = playTrailerInExpandedCard && !trailerPreviewUrl.isNullOrBlank()
    var trailerFirstFrameRendered by remember(trailerPreviewUrl) { mutableStateOf(false) }

    // Use the image model directly — Coil's memory cache handles repeated
    // requests efficiently without needing scroll-aware request swapping.
    val hasImage = !imageUrl.isNullOrBlank()
    val folderNameTile = isCollectionFolder && item.imageUrl.isNullOrBlank() && payload?.coverEmoji.isNullOrBlank() &&
        item.title.isNotBlank()
    val logoOverTrailer = LocalLogoOverCardTrailer.current
    val showsLandscapePoster = !customPosterLoadFailed &&
        (useLandscapeAsExpanded ||
            (useLandscapeOverlayTreatment && !effectiveIgnoreLandscapePoster &&
                !item.metaPreview?.landscapePoster.isNullOrBlank()))
    val showTitleOverlay =
        (useLandscapeOverlayTreatment || isBackdropExpanded) &&
            !isCollectionFolder &&
            cardTitleOverlayVisible(
                artworkCarriesTitle = fitPortraitArtwork || showsLandscapePoster,
                trailerShowing = shouldPlayTrailerInCard && trailerFirstFrameRendered,
                logoOverTrailer = logoOverTrailer
            )
    val hasLandscapeLogo = showTitleOverlay && !effectiveLogoUrl.isNullOrBlank() && !landscapeLogoLoadFailed
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()
    val backgroundCardColor = NuvioTheme.colors.BackgroundCard
    val titleMedium = MaterialTheme.typography.titleMedium
    val backgroundPainter = remember(backgroundCardColor) { ColorPainter(backgroundCardColor) }
    // The V2 look has one ring per row, so its cards carry no border of their own.
    val focusedBorder = if (isV2) {
        Border.None
    } else {
        val focusRingBorder = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs)
        remember(cardShape, focusRingBorder) {
            Border(
                border = focusRingBorder,
                shape = cardShape
            )
        }
    }
    // While the user is dragging the list via held DPAD (see LazyColumn-level fast
    // scroll takeover in ModernHomeContent), hide focus chrome entirely — the list is
    // sliding like a touch swipe and showing a border / glow jittering across every
    // card the drag passes over would break that illusion. The chrome reappears the
    // moment the user releases the key, when requestFocus lands focus on whichever
    // card is visible at the leading edge.
    val isFastScrollingState = LocalFastScrollActive.current
    // Only the focused card shows the chrome, so only it follows the fast-scroll flag.
    val isFastScrolling = isFocused && isFastScrollingState.value
    val effectiveFocusedBorder = if (isFastScrolling && !isV2) {
        remember(cardShape) {
            Border(
                border = BorderStroke(NuvioTheme.spacing.none, Color.Transparent),
                shape = cardShape
            )
        }
    } else {
        focusedBorder
    }
    val cardFocusGlow = when {
        isV2 -> Glow.None
        payload != null -> rememberArtworkBackedFocusGlow(
            imageUrl = imageUrl,
            fallbackSeed = "${item.title}:${payload.collectionTitle}",
            enabled = payload.focusGlowEnabled
        )
        else -> Glow.None
    }
    val effectiveFocusGlow = if (isFastScrolling) Glow.None else cardFocusGlow
    val titleStyle = remember(titleMedium) {
        titleMedium.copy(fontWeight = FontWeight.Medium)
    }
    val cardDescription = remember(item.title, item.cornerLabel) {
        listOfNotNull(item.title.takeIf { it.isNotBlank() }, item.cornerLabel?.takeIf { it.isNotBlank() }).joinToString(", ")
    }

    Column(
        modifier = modifier
            // The focused card draws last, so its gliding ring passes over its neighbours.
            .zIndex(if (isFocused) 1f else 0f)
            .layout { measurable, constraints ->
                // Width still remeasures the row, but does not rebuild the card, artwork
                // requests, focus effects and trailer subtree on every animation frame.
                val width = animatedCardWidthState.value.roundToPx()
                    .coerceIn(constraints.minWidth, constraints.maxWidth)
                val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
            }
            .recompositionHighlighter(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        NuvioCard(
            onClick = {
                if (longPressTriggered) {
                    longPressTriggered = false
                } else {
                    onClick()
                }
            },
            shape = cardShape,
            light = isV2,
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                // One description per card: the artwork, badges and overlays inside are not read one by one.
                .semantics { contentDescription = cardDescription }
                .rowFocusRing(isFocused, cardShape)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    isFocused = it.isFocused
                    onFocusStateChanged(it.isFocused)
                    if (it.isFocused) {
                        onFocused()
                    }
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                        if (focusedPosterBackdropExpandEnabled && shouldResetBackdropTimer(event.key)) {
                            onBackdropInteraction()
                        }
                        if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                            longPressTriggered = true
                            onLongPress()
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (longPressKeyTracker.handle(native, ::isSelectKey) {
                            longPressTriggered = true
                            onLongPress()
                        }
                    ) {
                        if (native.action == AndroidKeyEvent.ACTION_UP) {
                            longPressTriggered = false
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (native.action == AndroidKeyEvent.ACTION_UP &&
                        longPressTriggered &&
                        (isSelectKey(native.keyCode) || native.keyCode == AndroidKeyEvent.KEYCODE_MENU)
                    ) {
                        longPressTriggered = false
                        return@onPreviewKeyEvent true
                    }
                    false
                },
            // For content posters the AsyncImage is an opaque, full-bleed
            // JPEG (Crop) that already paints backgroundCardColor as its own
            // placeholder/fallback, so a solid container is a redundant
            // full-card overdraw pass beneath every poster: make it
            // transparent. BUT collection-folder covers are often logos /
            // graphics with transparency (or don't fill the card), so they
            // need the solid backing (grey), matching the official app;
            // emoji / no-image cards likewise keep the solid colour.
            containerColor = if (hasImage && !isCollectionFolder) Color.Transparent else backgroundCardColor,
            focusedBorder = if (isV2) Border.None else effectiveFocusedBorder,
            focusedGlow = effectiveFocusGlow
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {}
                    .nuvioCardDepth(
                        shape = cardShape,
                        surface = CardDepthSurface.POSTERS,
                        style = cardDepthStyle
                    )
            ) {
                val mediaLayerModifier = remember(hasLandscapeLogo) {
                    if (hasLandscapeLogo) {
                        Modifier
                            .fillMaxSize()
                            .drawWithCache {
                                onDrawWithContent {
                                    drawContent()
                                    drawRect(brush = MODERN_LANDSCAPE_LOGO_GRADIENT, size = size)
                                }
                            }
                    } else {
                        Modifier.fillMaxSize()
                    }
                }

                Box(modifier = mediaLayerModifier) {
                    val isPlaceholderItem = item.imageUrl.isPlaceholder()
                    if (isPlaceholderItem) {
                        // Horizontal sweeping shimmer for placeholder cards
                        val effectivePlaceholderShimmerOffsetState =
                            placeholderShimmerOffsetState ?: rememberPlaceholderShimmerOffsetState(
                                label = "placeholderShimmer"
                            )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .placeholderCardShimmer(effectivePlaceholderShimmerOffsetState)
                        )
                    } else if (folderNameTile) {
                        ModernFolderNameTile(
                            title = item.title,
                            pictureUrl = payload?.heroBackdropUrl,
                            requestWidthPx = requestWidthPx,
                            requestHeightPx = requestHeightPx
                        )
                    } else if (hasImage && !posterFailed) {
                        val watchedDim = watchedPosterDim(isWatched, isFocused)
                        WatchedPosterTint(watchedDim) { watchedFilter ->
                            AsyncImage(
                                model = imageModel,
                                contentDescription = item.title,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .portraitArtworkBackdrop(fitPortraitArtwork)
                                    .watchedPosterFade(watchedDim),
                                placeholder = backgroundPainter,
                                error = backgroundPainter,
                                fallback = backgroundPainter,
                                colorFilter = portraitArtworkBackdropFilter(fitPortraitArtwork) ?: watchedFilter,
                                onSuccess = {
                                    customPosterLoadFailed = it.result.request.data != imageUrl
                                    if (canFitPortraitArtwork) {
                                        portraitArtwork.onLoaded(it.result.image.width, it.result.image.height)
                                    }
                                    if (payload != null) {
                                        cropFolderCover = wideFolderCoverNeedsCrop(
                                            payload.posterShape,
                                            it.result.image.width,
                                            it.result.image.height
                                        )
                                    }
                                },
                                onError = {
                                    if (!item.metaPreview?.landscapePoster.isNullOrBlank() || !item.metaPreview?.rawPosterUrl.isNullOrBlank()) {
                                        customPosterLoadFailed = true
                                    }
                                    if (it.result.failedWithoutFallback(imageUrl)) failedWithoutFallback = true
                                    portraitArtwork.onFailed()
                                    if (com.nuvio.tv.core.image.homePosterRetryAfterError(posterAttempt)) {
                                        posterRetryPending = true
                                    } else {
                                        posterFailed = true
                                    }
                                },
                                contentScale = imageContentScale
                            )
                            if (fitPortraitArtwork) FittedPortraitArtwork(
                                imageModel,
                                contentDescription = null,
                                modifier = Modifier.watchedPosterFade(watchedDim),
                                colorFilter = watchedFilter
                            )
                        }
                    } else if (isCollectionFolder && !payload?.coverEmoji.isNullOrBlank()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = payload!!.coverEmoji!!,
                                fontSize = 48.sp
                            )
                        }
                    } else {
                        MonochromePosterPlaceholder()
                    }

                    // GIF overlay: renders on top of image or emoji, visible only once loaded
                    if (!focusGifUrl.isNullOrBlank()) {
                        val gifModel = remember(context, focusGifUrl, requestWidthPx, requestHeightPx) {
                            ImageRequest.Builder(context)
                                .data(focusGifUrl)
                                .memoryCacheKey("${focusGifUrl}_${requestWidthPx}x${requestHeightPx}")
                                .size(width = requestWidthPx, height = requestHeightPx)
                                .build()
                        }
                        var gifLoaded by remember(focusGifUrl) { mutableStateOf(false) }
                        val gifAlpha by animateFloatAsState(
                            targetValue = if (gifLoaded) 1f else 0f,
                            animationSpec = tween(durationMillis = 200),
                            label = "gifFadeIn"
                        )
                        AsyncImage(
                            model = gifModel,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = gifAlpha },
                            contentScale = imageContentScale,
                            onSuccess = { gifLoaded = true }
                        )
                    }

                    if (shouldPlayTrailerInCard) {
                        key(trailerPreviewUrl) {
                            TrailerPlayer(
                                trailerUrl = trailerPreviewUrl,
                                trailerAudioUrl = trailerPreviewAudioUrl,
                                isPlaying = true,
                                onEnded = onTrailerEnded,
                                onFirstFrameRendered = { trailerFirstFrameRendered = true },
                                muted = focusedPosterBackdropTrailerMuted,
                                cropToFill = true,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                payload?.scoutMix?.takeIf { !folderNameTile && hasImage }?.let { mix ->
                    ScoutMixCardWords(name = item.title, shows = mix.shows, rtl = mixRtl)
                }

                if (hasLandscapeLogo) {
                    val logoModel = remember(context, effectiveLogoUrl, maxLogoWidthPx, logoHeightPx) {
                        effectiveLogoUrl?.let {
                            ImageRequest.Builder(context)
                                .data(it)
                                .crossfade(false)
                                .memoryCacheKey("${it}_${maxLogoWidthPx}x${logoHeightPx}")
                                .size(width = maxLogoWidthPx, height = logoHeightPx)
                                .build()
                        }
                    }
                    AsyncImage(
                        model = logoModel,
                        contentDescription = item.title,
                        onError = { landscapeLogoLoadFailed = true },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(0.62f)
                            .height(cardHeight * 0.34f)
                            .padding(start = 10.dp, end = 10.dp, bottom = NuvioTheme.spacing.sm),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart
                    )
                } else if (showTitleOverlay) {
                    Text(
                        text = item.title,
                        style = titleStyle.copy(
                            textDirection = item.title.contentTextDirection()
                        ),
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(0.62f)
                            .padding(start = 10.dp, end = 10.dp, bottom = NuvioTheme.spacing.md)
                    )
                }

                item.cornerLabel?.takeIf { it.isNotBlank() }?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = NuvioTheme.spacing.sm, top = NuvioTheme.spacing.sm)
                            .zIndex(2f)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }

                item.progressFraction?.takeIf { it > 0f }?.let { fraction ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .zIndex(2f)
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(NuvioTheme.colors.Secondary)
                    )
                }

                if (isWatched && LocalDimWatchedPosters.current) {
                    WatchedPosterVeil(
                        focused = isFocused,
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(1.9f)
                    )
                }
                if (isWatched) {
                    WatchedMarker(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = NuvioTheme.spacing.sm, top = NuvioTheme.spacing.sm)
                            .zIndex(2f)
                    )
                }
            }
        }

        if (showLabels && !isBackdropExpanded && item.title.isNotBlank() && payload?.scoutMix == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NuvioTheme.spacing.xs)
            ) {
                Text(
                    text = item.title,
                    style = titleStyle.copy(
                        textDirection = item.title.contentTextDirection()
                    ),
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium.copy(
                            textDirection = subtitle.contentTextDirection()
                        ),
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}


/** A mix card's name and size, on a fade of the page colour that leaves the dealt posters clear. */
@Composable
private fun BoxScope.ScoutMixCardWords(name: String, shows: String, rtl: Boolean) {
    val page = NuvioTheme.colors.Background
    Box(
        Modifier
            .matchParentSize()
            .drawWithCache {
                val stops = arrayOf(
                    0f to page.copy(alpha = .86f),
                    .56f to page.copy(alpha = .5f),
                    .8f to page.copy(alpha = .05f),
                    1f to Color.Transparent
                )
                val brush = if (rtl) Brush.horizontalGradient(*stops, startX = size.width, endX = 0f)
                else Brush.horizontalGradient(*stops, startX = 0f, endX = size.width)
                onDrawBehind { drawRect(brush) }
            }
    )
    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth(.62f)
            .padding(start = NuvioTheme.spacing.md, end = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                textDirection = name.contentTextDirection()
            ),
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            androidx.tv.material3.Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = null,
                tint = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.size(NuvioTheme.spacing.md + NuvioTheme.spacing.xxs)
            )
            Text(
                text = shows,
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Keys that should collapse an expanded poster so navigation can re-arm expand.
 *
 * Select keys (Center / Enter) are intentionally excluded: holding them opens the
 * action menu / long-press options. Resetting the expand timer on those keys made
 * the Expanded Card collapse and re-expand (and restart trailers) while the menu
 * was opening (#2574).
 */
private fun shouldResetBackdropTimer(key: Key): Boolean {
    return when (key) {
        Key.DirectionUp,
        Key.DirectionDown,
        Key.DirectionLeft,
        Key.DirectionRight -> true
        else -> false
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}
