@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Replay
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.VodDetailTarget
import com.nuvio.tv.core.iptv.VodKind
import com.nuvio.tv.core.iptv.VodResume
import com.nuvio.tv.core.iptv.VodStreams
import com.nuvio.tv.data.iptv.IptvVodEpisode
import com.nuvio.tv.data.iptv.IptvVodResume
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.detail.PlayButton
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.appearance.V2Atmosphere
import com.nuvio.tv.ui.v2.components.NuvioActionPill
import java.util.Locale

@Composable
fun IptvVodTitleScreen(onPlay: (IptvVodPlay) -> Unit, onDetail: (VodDetailTarget) -> Unit, viewModel: IptvVodTitleViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var started = false
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { if (started) viewModel.refreshResume(); started = true } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is IptvVodTitleEvent.Play -> onPlay(event.play)
                is IptvVodTitleEvent.Detail -> onDetail(event.target)
            }
        }
    }
    val ready = state.title != null && (state.ref?.kind != VodKind.SERIES || !state.episodesLoading)
    LaunchedEffect(ready) { if (ready && !focused) { withFrameNanos { }; focused = true; runCatching { first.requestFocus() } } }
    val title = state.title
    val art = state.art
    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        val backdrop = art?.backdrop
        val background = NuvioTheme.colors.Background
        if (backdrop != null) {
            val context = LocalContext.current
            val request = remember(backdrop) { ImageRequest.Builder(context).data(backdrop).size(1280, 720).build() }
            AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to background, .42f to background.copy(alpha = .86f), 1f to background.copy(alpha = .18f))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .5f to Color.Transparent, 1f to background)))
        } else if (!LocalIptvAppearance.current.plainBackground) LocalV2Appearance.current?.let { V2Atmosphere(rich = false, background = it.settingsBackground) }
        when {
            !state.loaded -> LoadingIndicator(Modifier.align(Alignment.Center).size(36.dp))
            title == null -> Text(stringResource(R.string.iptv_vod_browse_missing), style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.align(Alignment.Center).padding(24.dp))
            else -> {
                val series = title.ref.kind == VodKind.SERIES
                Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 40.dp, bottom = if (series) 20.dp else 56.dp),
                    verticalArrangement = if (series) Arrangement.spacedBy(18.dp) else Arrangement.spacedBy(18.dp, Alignment.Bottom)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.Bottom) {
                        if (backdrop == null) VodPosterImage(art?.poster ?: title.artwork, title.title, Modifier.width(200.dp).aspectRatio(2f / 3f))
                        Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(art?.title ?: title.title, style = MaterialTheme.typography.displayMedium, color = NuvioTheme.colors.TextPrimary,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val facts = listOfNotNull((title.year ?: art?.year)?.toString(), (art?.rating ?: title.rating)?.let(::ratingText),
                                state.durationSeconds?.let { stringResource(R.string.iptv_vod_browse_runtime, (it + 59) / 60) }, state.source).joinToString(" · ")
                            if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
                            (art?.overview ?: state.plot)?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextSecondary, maxLines = 3,
                                overflow = TextOverflow.Ellipsis) }
                            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.Error) }
                            Spacer(Modifier.height(4.dp))
                            Actions(state, first, onPlay = viewModel::play, onDetails = viewModel::openDetails)
                        }
                    }
                    if (series) Episodes(state, Modifier.fillMaxWidth().weight(1f).iptvPanel().padding(16.dp), onSeason = viewModel::selectSeason,
                        onEpisode = { episode -> viewModel.play(episode.ref, fromStart = state.resumes[episode.ref] == null) }, onRetry = viewModel::loadEpisodes)
                }
            }
        }
    }
}

@Composable
private fun Actions(state: IptvVodTitleState, first: FocusRequester, onPlay: (com.nuvio.tv.core.iptv.VodRef, Boolean) -> Unit, onDetails: () -> Unit) {
    val ref = state.ref ?: return
    val series = ref.kind == VodKind.SERIES
    val latest = if (series) state.latest?.takeIf { resume -> state.episodes.any { it.ref == resume.ref } } else state.resume
    val firstEpisode = state.episodes.firstOrNull { it.season > 0 } ?: state.episodes.firstOrNull()
    val target = latest?.ref ?: if (series) firstEpisode?.ref else ref
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (target != null) PlayButton(text = when {
                latest == null -> stringResource(R.string.iptv_vod_browse_play)
                series -> stringResource(R.string.iptv_vod_browse_resume_episode, state.episodes.firstOrNull { it.ref == latest.ref }?.let { VodStreams.episodeCode(it.season, it.episode) }.orEmpty())
                else -> stringResource(R.string.iptv_vod_browse_resume, clockText(latest.positionMillis))
            }, onClick = { onPlay(target, latest == null) }, focusRequester = first)
        if (latest != null) NuvioActionPill({ onPlay(latest.ref, true) }) {
            Icon(Icons.Filled.Replay, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.iptv_vod_browse_from_start))
        }
        if (state.tmdbId != null || state.imdbId != null) NuvioActionPill(onDetails, if (target == null) Modifier.focusRequester(first) else Modifier) {
            Icon(Icons.Filled.Info, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.iptv_vod_browse_full_details))
        }
        if (state.checking != null || state.opening) LoadingIndicator(Modifier.size(24.dp))
    }
}

@Composable
private fun Episodes(state: IptvVodTitleState, modifier: Modifier, onSeason: (Int) -> Unit, onEpisode: (IptvVodEpisode) -> Unit, onRetry: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            state.episodesLoading -> LoadingIndicator(Modifier.size(28.dp))
            state.episodesFailed -> {
                Text(stringResource(R.string.iptv_vod_browse_episodes_failed), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.Error)
                NuvioActionPill(onRetry) { Text(stringResource(R.string.iptv_vod_browse_retry)) }
            }
            state.episodes.isEmpty() -> Text(stringResource(R.string.iptv_vod_browse_no_episodes), style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary)
            else -> {
                if (state.seasons.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(state.seasons, key = { it }) { season ->
                        SeasonChip(if (season == 0) stringResource(R.string.iptv_vod_browse_specials) else stringResource(R.string.iptv_vod_browse_season, season),
                            season == state.season) { onSeason(season) }
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.episodes.filter { it.season == state.season }, key = { it.ref.format() }) { episode ->
                        EpisodeRow(episode, state.resumes[episode.ref], state.checking == episode.ref) { onEpisode(episode) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeasonChip(text: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (focused || selected) itemContent(focused) else NuvioTheme.colors.TextSecondary,
        modifier = Modifier.onFocusChanged { focused = it.isFocused }.iptvItem(focused, selected)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode)) { onClick(); true } else false
            }
            .focusable().padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun EpisodeRow(episode: IptvVodEpisode, resume: IptvVodResume?, checking: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.iptvItem(focused)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode)) { onClick(); true } else false
        }
        .focusable().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(VodStreams.episodeCode(episode.season, episode.episode), style = iptvMetaStyle(), fontWeight = FontWeight.SemiBold,
            color = if (focused) itemContent(true) else NuvioTheme.colors.TextTertiary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(episode.title ?: VodStreams.episodeCode(episode.season, episode.episode), style = iptvItemStyle(focused), maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = itemContent(focused))
            if (focused) episode.plot?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary, maxLines = 2,
                overflow = TextOverflow.Ellipsis) }
            resume?.let { saved -> VodResume.fraction(saved.positionMillis, saved.durationMillis)?.let { ProgressLine(it, Modifier.width(160.dp)) } }
        }
        episode.durationSeconds?.let { Text(stringResource(R.string.iptv_vod_browse_runtime, (it + 59) / 60), style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextTertiary) }
        if (checking) LoadingIndicator(Modifier.size(20.dp))
    }
}

private fun clockText(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds) else String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}
