@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.HomeRowKind
import com.nuvio.tv.core.iptv.HomeRows
import com.nuvio.tv.core.iptv.VodArt
import com.nuvio.tv.core.iptv.VodKind
import com.nuvio.tv.data.iptv.IptvHomeChannel
import com.nuvio.tv.data.iptv.IptvHomeRowsLoader
import com.nuvio.tv.data.iptv.IptvRecording
import com.nuvio.tv.data.iptv.IptvVodTitle
import com.nuvio.tv.domain.model.CardDepthSurface
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.ContentCard
import com.nuvio.tv.ui.components.LocalCardDepthStyle
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.components.nuvioCardDepth
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Immutable
data class IptvHomeStyle(val poster: PosterCardStyle, val wideWidth: Dp, val wideHeight: Dp, val showLabels: Boolean, val compactTitle: Boolean,
    val headerPadding: PaddingValues, val rowStart: Dp, val rowEnd: Dp, val spacing: Dp, val rowWidth: Dp = Dp.Unspecified)

fun LazyListScope.iptvHomeRows(host: IptvHomeHost?, rows: List<IptvHomeRow>, style: IptvHomeStyle,
    rowFocus: MutableMap<String, FocusRequester>? = null, onFocused: (String, Int) -> Unit = { _, _ -> }) {
    if (host == null) return
    rows.forEach { row ->
        item(key = row.key, contentType = "iptv_home_row") {
            IptvHomeRowSection(host, row, style, rowFocus?.getOrPut(row.key) { FocusRequester() }, onFocused)
        }
    }
}

fun LazyGridScope.iptvHomeRows(host: IptvHomeHost?, rows: List<IptvHomeRow>, style: IptvHomeStyle, onFocused: (String, Int) -> Unit = { _, _ -> }) {
    if (host == null) return
    rows.forEach { row ->
        item(key = row.key, span = { GridItemSpan(maxLineSpan) }, contentType = "iptv_home_row") {
            IptvHomeRowSection(host, row, style, null, onFocused)
        }
    }
}

@Composable
private fun IptvHomeRowSection(host: IptvHomeHost, row: IptvHomeRow, style: IptvHomeStyle, rowFocus: FocusRequester?, onFocused: (String, Int) -> Unit) {
    val listState = rememberLazyListState()
    var lastFocused by rememberSaveable(row.key) { mutableIntStateOf(0) }
    val requesters = remember(row.key) { mutableMapOf<Int, FocusRequester>() }
    val scope = rememberCoroutineScope()
    val count = when (row) {
        is IptvHomeRow.Channels -> row.items.size
        is IptvHomeRow.Titles -> row.items.size
        is IptvHomeRow.Recordings -> row.items.size
        is IptvHomeRow.LiveSport -> row.items.size
        is IptvHomeRow.Teams -> row.items.size + 1
    }
    fun focused(index: Int) { lastFocused = index; onFocused(row.key, index) }
    fun requester(index: Int) = requesters.getOrPut(index) { FocusRequester() }
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(when (row) {
                is IptvHomeRow.LiveSport -> R.string.iptv_sport5_nuvio_live_sport
                is IptvHomeRow.Teams -> R.string.iptv_sport5_nuvio_your_teams
                else -> iptvHomeRowTitle(row.kind)
            }), color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = if (style.compactTitle) MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold) else MaterialTheme.typography.headlineMedium,
            modifier = Modifier.fillMaxWidth().padding(style.headerPadding))
        val density = LocalDensity.current
        val defaultSpec = LocalBringIntoViewSpec.current
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val horizontalSpec = remember(density, defaultSpec, rtl, style.rowStart) {
            val startPx = with(density) { style.rowStart.roundToPx() }.toFloat()
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            object : BringIntoViewSpec {
                override val scrollAnimationSpec: AnimationSpec<Float> = defaultSpec.scrollAnimationSpec
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                    val childSize = kotlin.math.abs(size)
                    return if (rtl) {
                        val target = containerSize - startPx
                        (offset + size) - (if (childSize <= containerSize && target < childSize) childSize else target)
                    } else {
                        val leading = if (childSize <= containerSize && containerSize - startPx < childSize) containerSize - childSize else startPx
                        offset - leading
                    }
                }
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalSpec) {
            LazyRow(
                state = listState,
                modifier = Modifier
                    .then(if (style.rowWidth != Dp.Unspecified) Modifier.requiredWidth(style.rowWidth) else Modifier.fillMaxWidth())
                    .then(if (rowFocus != null) Modifier.focusRequester(rowFocus) else Modifier)
                    .focusRestorer {
                        val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
                        val index = lastFocused.takeIf { it in visible } ?: visible.firstOrNull()
                        index?.let { requesters[it] } ?: FocusRequester.Default
                    }
                    .focusGroup()
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.keyCode != AndroidKeyEvent.KEYCODE_BACK || lastFocused <= 0 || lastFocused >= count) return@onPreviewKeyEvent false
                        if (native.action == AndroidKeyEvent.ACTION_UP) scope.launch {
                            listState.scrollToItem(0)
                            withFrameNanos { }
                            runCatching { requester(0).requestFocus() }
                        }
                        true
                    },
                contentPadding = PaddingValues(start = style.rowStart, end = style.rowEnd),
                horizontalArrangement = Arrangement.spacedBy(style.spacing)
            ) {
                when (row) {
                    is IptvHomeRow.Channels -> itemsIndexed(row.items, key = { _, item -> IptvHomeRowsLoader.identity(item.channel) }) { index, item ->
                        ChannelCard(item, row.kind == HomeRowKind.SPORT, host.now.value, style, Modifier.focusRequester(requester(index)),
                            onFocus = { focused(index) }, onClick = { host.openChannel(row, item) })
                    }
                    is IptvHomeRow.Titles -> itemsIndexed(row.items, key = { _, item -> item.ref.format() }) { index, item ->
                        val preview = remember(item, row.art[item.ref]) { item.preview(row.art[item.ref]) }
                        ContentCard(item = preview, focusRequester = requester(index), posterCardStyle = style.poster, showLabels = style.showLabels,
                            showImdbRatings = false, onFocus = { focused(index) }, onClick = { host.openTitle(row, item) })
                    }
                    is IptvHomeRow.Recordings -> itemsIndexed(row.items, key = { _, item -> item.id }) { index, item ->
                        RecordingCard(item, row.logos[item.sourceId to item.channelId], style, Modifier.focusRequester(requester(index)),
                            onFocus = { focused(index) }, onClick = host::openRecordings)
                    }
                    is IptvHomeRow.LiveSport -> itemsIndexed(row.items, key = { _, item -> item.fixture.key }) { index, item ->
                        SportCard(item, style, Modifier.focusRequester(requester(index)), onFocus = { focused(index) },
                            onClick = { host.openSport(item) })
                    }
                    is IptvHomeRow.Teams -> {
                        itemsIndexed(row.items, key = { _, item -> item.key }) { index, item ->
                            TeamCard(item, row.days, style, Modifier.focusRequester(requester(index)), onFocus = { focused(index) },
                                onClick = { host.openTeam(item) })
                        }
                        item(key = "follow") {
                            val index = row.items.size
                            HomeCardFrame(style, Modifier.focusRequester(requester(index)), onFocus = { focused(index) }, onClick = host::openLive) {
                                Text(stringResource(R.string.iptv_sport5_nuvio_follow_team), style = MaterialTheme.typography.titleSmall,
                                    color = NuvioTheme.colors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center)
                                        .padding(horizontal = NuvioTheme.spacing.md))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(item: IptvHomeChannel, live: Boolean, now: Long, style: IptvHomeStyle, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val name = channelName(item.channel)
    val programme = item.programme
    val heading = programme?.let { title(it) }?.takeIf { it.isNotBlank() }
    WideCard(heading ?: name, if (heading != null) name else null, programmeArt(programme), logoUrl(item.channel), name,
        programme?.let { HomeRows.progress(it, now) }, live, style, modifier, onFocus, onClick)
}

@Composable
private fun RecordingCard(item: IptvRecording, logo: String?, style: IptvHomeStyle, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val started = remember(item.startMillis) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.startMillis)) }
    val heading = item.title?.takeIf { it.isNotBlank() } ?: item.channelName
    WideCard(heading, stringResource(R.string.iptv_home_recording_detail, item.channelName, started), null, logo, item.channelName,
        null, false, style, modifier, onFocus, onClick)
}

@Composable
private fun SportCard(item: IptvHomeSport, style: IptvHomeStyle, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val card = remember(item) { IptvFixtureItem(item.fixture, listOf(item.link)) }
    HomeCardFrame(style, modifier, onFocus, onClick) {
        Column(Modifier.fillMaxSize()) {
            SportCardContent(card, item.hidden, false, emptySet(), false, false, homeSportCardSize(style.wideHeight),
                badge = if (item.close) stringResource(R.string.iptv_sport5g_close) else null)
        }
    }
}

@Composable
private fun TeamCard(item: IptvHomeTeam, days: Int, style: IptvHomeStyle, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val fixture = item.fixture
    val note = when (item.recording) {
        IptvTeamRecording.RECORDING -> stringResource(R.string.iptv_sport5_nuvio_recording_now)
        IptvTeamRecording.SET -> stringResource(R.string.iptv_sport5_nuvio_recording_set)
        IptvTeamRecording.RULE -> stringResource(R.string.iptv_sport5_nuvio_records_all)
        IptvTeamRecording.NONE -> if (item.reminder) stringResource(R.string.iptv_sport5_nuvio_reminder_set) else null
    }
    if (fixture != null) {
        val card = remember(fixture) { IptvFixtureItem(fixture, emptyList(), favourite = true) }
        val hiddenResult = if (item.hidden && fixture.status == FixtureStatus.FINAL) stringResource(R.string.iptv_sport5_nuvio_result_hidden) else null
        val footer = listOfNotNull(hiddenResult, note, fixture.venue.takeIf { fixture.status == FixtureStatus.SCHEDULED }).joinToString(" · ")
        HomeCardFrame(style, modifier, onFocus, onClick) {
            Column(Modifier.fillMaxSize()) {
                SportCardContent(card, item.hidden, false, emptySet(), item.reminder && fixture.status == FixtureStatus.SCHEDULED, false,
                    homeSportCardSize(style.wideHeight), footer = footer)
            }
        }
        return
    }
    HomeCardFrame(style, modifier, onFocus, onClick) {
        Column(Modifier.fillMaxSize().padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm), verticalArrangement = Arrangement.SpaceEvenly) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.team != null) TeamLogo(item.team, 24.dp)
                Text("${item.name} · ${item.league}", style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Text(stringResource(R.string.iptv_sport5_nuvio_no_game, days), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

private val COMPACT_SPORT_CARD = SportCardSize(20.dp, 18.dp, 24.dp, true)
private val MEDIUM_SPORT_CARD = SportCardSize(22.dp, 20.dp, 28.dp, true)

private fun homeSportCardSize(height: Dp): SportCardSize = when {
    height >= SPORT_CARD_HEIGHT -> FullSportCard
    height >= 116.dp -> MEDIUM_SPORT_CARD
    else -> COMPACT_SPORT_CARD
}

@Composable
private fun HomeCardFrame(style: IptvHomeStyle, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val isV2 = LocalV2Appearance.current != null
    val shape = remember(style.poster.cornerRadius) { RoundedCornerShape(style.poster.cornerRadius) }
    val depth = LocalCardDepthStyle.current
    Card(
        onClick = onClick,
        modifier = modifier
            .width(style.wideWidth)
            .nuvioV2Focus(focused, shape, stationary = true)
            .onFocusChanged { state -> if (state.isFocused != focused) { focused = state.isFocused; if (state.isFocused) onFocus() } },
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        border = if (isV2) CardDefaults.border(focusedBorder = Border.None)
            else CardDefaults.border(focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Box(Modifier.fillMaxWidth().height(style.wideHeight)
            .nuvioCardDepth(shape = shape, surface = CardDepthSurface.CONTINUE_WATCHING, style = depth)
            .clip(shape).background(NuvioTheme.colors.BackgroundCard), content = content)
    }
}

@Composable
private fun WideCard(heading: String, detail: String?, artwork: String?, logo: String?, name: String, progress: Float?, live: Boolean,
    style: IptvHomeStyle, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, tag: String? = null) {
    var focused by remember { mutableStateOf(false) }
    var artFailed by remember(artwork) { mutableStateOf(false) }
    val isV2 = LocalV2Appearance.current != null
    val shape = remember(style.poster.cornerRadius) { RoundedCornerShape(style.poster.cornerRadius) }
    val depth = LocalCardDepthStyle.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthPx = with(density) { style.wideWidth.roundToPx() }.coerceAtLeast(1)
    val heightPx = with(density) { style.wideHeight.roundToPx() }.coerceAtLeast(1)
    val request = remember(artwork, widthPx, heightPx) {
        artwork?.let { ImageRequest.Builder(context).data(it).size(widthPx, heightPx).crossfade(true).build() }
    }
    val showArt = request != null && !artFailed
    val fade = NuvioTheme.colors.Background
    Card(
        onClick = onClick,
        modifier = modifier
            .width(style.wideWidth)
            .nuvioV2Focus(focused, shape, stationary = true)
            .onFocusChanged { state -> if (state.isFocused != focused) { focused = state.isFocused; if (state.isFocused) onFocus() } },
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        border = if (isV2) CardDefaults.border(focusedBorder = Border.None)
            else CardDefaults.border(focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Box(Modifier.fillMaxWidth().height(style.wideHeight)
            .nuvioCardDepth(shape = shape, surface = CardDepthSurface.CONTINUE_WATCHING, style = depth)
            .clip(shape).background(NuvioTheme.colors.BackgroundCard)) {
            if (showArt) AsyncImage(model = request, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                onError = { artFailed = true })
            else ChannelLogo(logo, name, Modifier.align(Alignment.Center).padding(bottom = style.wideHeight * .22f).fillMaxWidth(.42f).aspectRatio(16f / 9f))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .4f to Color.Transparent, 1f to fade.copy(alpha = .95f))))
            if (showArt && logo != null) ChannelLogo(logo, name, Modifier.align(Alignment.TopStart).padding(NuvioTheme.spacing.sm).size(56.dp, 32.dp))
            if (tag != null) Tag(tag, Modifier.align(Alignment.TopEnd).padding(NuvioTheme.spacing.sm), live = live, scrim = true)
            else if (live) Tag(stringResource(R.string.iptv_live_playing), Modifier.align(Alignment.TopEnd).padding(NuvioTheme.spacing.sm), live = true)
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .padding(start = NuvioTheme.spacing.md, end = NuvioTheme.spacing.md, bottom = if (progress != null) NuvioTheme.spacing.lg else NuvioTheme.spacing.md)) {
                Text(heading, style = MaterialTheme.typography.titleSmall, color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) Text(detail, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            if (progress != null) Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = NuvioTheme.spacing.xs).fillMaxWidth()
                .clip(RoundedCornerShape(1.5.dp)).height(3.dp).background(Color.Black.copy(alpha = .3f))) {
                Box(Modifier.fillMaxWidth(progress).height(3.dp).background(NuvioTheme.colors.Secondary))
            }
        }
    }
}

private fun IptvVodTitle.preview(art: VodArt?): MetaPreview = MetaPreview(
    id = ref.format(), type = if (ref.kind == VodKind.SERIES) ContentType.SERIES else ContentType.MOVIE, name = art?.title ?: title,
    poster = art?.poster ?: artwork, posterShape = PosterShape.POSTER, background = art?.backdrop, logo = null, description = art?.overview,
    releaseInfo = (year ?: art?.year)?.toString(), imdbRating = null, genres = emptyList())

internal fun iptvHomeRowTitle(kind: HomeRowKind): Int = when (kind) {
    HomeRowKind.FAVOURITES -> R.string.iptv_home_favourites
    HomeRowKind.SPORT -> R.string.iptv_home_sport
    HomeRowKind.MOVIES -> R.string.iptv_home_movies
    HomeRowKind.SERIES -> R.string.iptv_home_series
    HomeRowKind.RECORDINGS -> R.string.iptv_home_recordings
}
