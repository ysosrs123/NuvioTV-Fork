@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.VodArt
import com.nuvio.tv.core.iptv.VodDetailTarget
import com.nuvio.tv.core.iptv.VodKind
import com.nuvio.tv.core.iptv.VodRef
import com.nuvio.tv.data.iptv.IptvVodTitle
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.appearance.V2Atmosphere
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun rememberIptvVodAvailability(viewModel: IptvVodMenuViewModel = hiltViewModel()): IptvVodAvailability {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.reload() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return state
}

@Composable
fun IptvVodBrowseScreen(onBack: () -> Unit, onTitle: (VodRef) -> Unit, onDetail: (VodDetailTarget) -> Unit,
    viewModel: IptvVodBrowseViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    val firstPoster = remember { FocusRequester() }
    val firstRail = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    var searching by remember { mutableStateOf(false) }
    var focusGrid by remember { mutableStateOf(true) }
    var shownRevision by remember { mutableIntStateOf(-1) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var started = false
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { if (started) viewModel.reload(); started = true } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is IptvVodNavigation.Detail -> onDetail(event.target)
                is IptvVodNavigation.Title -> onTitle(event.ref)
            }
        }
    }
    LaunchedEffect(state.revision, state.loading, state.ready) {
        if (!state.ready || state.loading) return@LaunchedEffect
        if (state.revision != shownRevision) { shownRevision = state.revision; runCatching { grid.scrollToItem(0) } }
        if (!focusGrid) return@LaunchedEffect
        focusGrid = false
        withFrameNanos { }
        if (state.items.isNotEmpty()) runCatching { firstPoster.requestFocus() } else if (!searching) runCatching { firstRail.requestFocus() }
    }
    LaunchedEffect(grid, state.items) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.map { it.index } }.distinctUntilChanged().collectLatest { indices ->
            val last = indices.maxOrNull() ?: return@collectLatest
            if (last >= state.items.size - LOAD_AHEAD) viewModel.loadMore()
            delay(ART_DELAY)
            viewModel.visible(indices.mapNotNull { state.items.getOrNull(it)?.ref })
        }
    }
    LaunchedEffect(searching) { if (searching) { withFrameNanos { }; runCatching { searchFocus.requestFocus() } } }
    BackHandler(enabled = searching) {
        searching = false
        viewModel.search("")
        focusGrid = true
    }
    BackHandler(enabled = !searching, onBack = onBack)
    val v2 = LocalV2Appearance.current != null
    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        if (!LocalIptvAppearance.current.plainBackground) LocalV2Appearance.current?.let { V2Atmosphere(rich = false, background = it.settingsBackground) }
        Row(Modifier.fillMaxSize().then(if (v2) Modifier else Modifier.padding(start = 24.dp, top = 20.dp, bottom = 20.dp)), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            VodRail(state, firstRail, Modifier.width(300.dp).fillMaxHeight(),
                onSearch = { searching = true },
                onRecent = { searching = false; focusGrid = true; viewModel.showRecent() },
                onAll = { searching = false; focusGrid = true; viewModel.showAll() },
                onCategory = { searching = false; focusGrid = true; viewModel.showCategory(it) },
                onSource = { searching = false; focusGrid = true; viewModel.showSource(it) })
            Column(Modifier.weight(1f).fillMaxHeight().padding(top = 28.dp, end = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(vodHeading(state), style = MaterialTheme.typography.headlineLarge, color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (searching) IptvSearchField(state.search, stringResource(R.string.iptv_vod_browse_search_hint), searchFocus, onChange = viewModel::search, onDone = {
                    if (state.items.isNotEmpty()) runCatching { firstPoster.requestFocus() }
                })
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        !state.ready || (state.loading && state.items.isEmpty()) -> LoadingIndicator(Modifier.align(Alignment.Center).size(36.dp))
                        state.sources.isEmpty() -> VodMessage(stringResource(R.string.iptv_vod_browse_none), Modifier.align(Alignment.Center))
                        state.items.isEmpty() -> VodMessage(stringResource(if (state.shelf == IptvVodShelf.SEARCH) R.string.iptv_vod_browse_no_results
                            else R.string.iptv_vod_browse_empty), Modifier.align(Alignment.Center))
                        else -> VodGrid(state, grid, firstPoster, onOpen = viewModel::open)
                    }
                }
            }
        }
    }
}

@Composable
private fun vodHeading(state: IptvVodBrowseState): String = when (state.shelf) {
    IptvVodShelf.SEARCH -> stringResource(R.string.iptv_vod_browse_results, state.search.trim())
    IptvVodShelf.RECENT -> stringResource(R.string.iptv_vod_browse_recent)
    IptvVodShelf.ALL -> stringResource(R.string.iptv_vod_browse_all)
    IptvVodShelf.CATEGORY -> state.categories.firstOrNull { it.id == state.category }?.name ?: stringResource(R.string.iptv_vod_browse_all)
}

@Composable
private fun VodRail(state: IptvVodBrowseState, first: FocusRequester, modifier: Modifier, onSearch: () -> Unit, onRecent: () -> Unit, onAll: () -> Unit,
    onCategory: (String) -> Unit, onSource: (com.nuvio.tv.data.iptv.IptvSourceRef) -> Unit) {
    val surface = if (LocalV2Appearance.current == null) Modifier.iptvPanel() else Modifier.iptvPanel(RectangleShape, edge = true)
    Column(modifier.then(surface).padding(vertical = 18.dp, horizontal = 12.dp)) {
        Text(stringResource(if (state.kind == VodKind.SERIES) R.string.iptv_vod_browse_series else R.string.iptv_vod_browse_movies),
            style = iptvTitleStyle(), color = NuvioTheme.colors.TextPrimary, modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 12.dp))
        if (state.sources.isEmpty()) return@Column
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item(key = "search") { IptvRailItem(stringResource(R.string.iptv_vod_browse_search), null, state.shelf == IptvVodShelf.SEARCH, Modifier, onSearch, Icons.Filled.Search) }
            item(key = "recent") { IptvRailItem(stringResource(R.string.iptv_vod_browse_recent), null, state.shelf == IptvVodShelf.RECENT, Modifier.focusRequester(first),
                onRecent, Icons.Filled.NewReleases) }
            item(key = "all") { IptvRailItem(stringResource(R.string.iptv_vod_browse_all), state.categories.sumOf { it.count }.takeIf { it > 0 },
                state.shelf == IptvVodShelf.ALL, Modifier, onAll, Icons.AutoMirrored.Filled.List) }
            if (state.sources.size > 1) {
                item(key = "sources") { SectionLabel(stringResource(R.string.iptv_vod_browse_sources)) }
                items(state.sources, key = { "source-${it.ref.sourceId}" }) { source ->
                    IptvRailItem(source.label, null, source.ref == state.source, Modifier, { onSource(source.ref) }, Icons.Filled.LiveTv)
                }
            }
            if (state.categories.isNotEmpty()) item(key = "categories") { SectionLabel(stringResource(R.string.iptv_vod_browse_categories)) }
            items(state.categories, key = { "category-${it.id}" }) { category ->
                IptvRailItem(category.name, category.count, state.shelf == IptvVodShelf.CATEGORY && state.category == category.id, Modifier, { onCategory(category.id) })
            }
        }
    }
}

@Composable
private fun VodGrid(state: IptvVodBrowseState, grid: androidx.compose.foundation.lazy.grid.LazyGridState, first: FocusRequester, onOpen: (IptvVodTitle) -> Unit) {
    val style = PosterCardDefaults.Style
    LazyVerticalGrid(GridCells.Adaptive(style.width), Modifier.fillMaxSize(), state = grid,
        contentPadding = PaddingValues(top = NuvioTheme.spacing.md, bottom = NuvioTheme.spacing.xxl, start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)) {
        itemsIndexed(state.items, key = { _, title -> title.ref.format() }) { index, title ->
            VodPoster(title, state.kind, state.art[title.ref], state.opening == title.ref, if (index == 0) first else null) { onOpen(title) }
        }
        if (state.loading && state.items.isNotEmpty()) item(key = "more", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().height(120.dp)) { LoadingIndicator(Modifier.align(Alignment.Center).size(28.dp)) } }
    }
}

@Composable
private fun VodPoster(title: IptvVodTitle, kind: VodKind, art: VodArt?, opening: Boolean, focus: FocusRequester?, onClick: () -> Unit) {
    val preview = remember(title, kind, art) {
        MetaPreview(id = title.ref.format(), type = if (kind == VodKind.SERIES) ContentType.SERIES else ContentType.MOVIE, name = art?.title ?: title.title,
            poster = art?.poster ?: title.artwork, posterShape = PosterShape.POSTER, background = art?.backdrop, logo = null, description = null,
            releaseInfo = (title.year ?: art?.year)?.toString(), imdbRating = (art?.rating ?: title.rating)?.toFloat(), genres = emptyList())
    }
    Box {
        GridContentCard(preview, onClick = onClick, focusRequester = focus)
        if (opening) LoadingIndicator(Modifier.align(Alignment.Center).size(28.dp))
    }
}

@Composable
internal fun VodPosterImage(url: String?, name: String, modifier: Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val request = remember(url, density) {
        url?.let { with(density) { ImageRequest.Builder(context).data(it).size(POSTER_WIDTH.roundToPx(), (POSTER_WIDTH * 1.5f).roundToPx()).build() } }
    }
    Box(modifier.clip(ItemShape).background(NuvioTheme.colors.TextPrimary.copy(alpha = .07f)), contentAlignment = Alignment.Center) {
        if (request != null && !failed) AsyncImage(request, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { failed = true })
        else Text(name, style = MaterialTheme.typography.titleSmall, color = NuvioTheme.colors.TextSecondary, textAlign = TextAlign.Center, maxLines = 4,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
    }
}

@Composable
private fun VodMessage(text: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextSecondary, textAlign = TextAlign.Center,
        modifier = modifier.widthIn(max = 520.dp).padding(24.dp))
}

internal fun ratingText(rating: Double): String = "★ " + String.format(Locale.getDefault(), "%.1f", rating)

private val POSTER_WIDTH = 132.dp
private const val LOAD_AHEAD = 18
private const val ART_DELAY = 300L
