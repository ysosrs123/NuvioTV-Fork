package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails
import com.nuvio.tv.ui.components.FocusScrollingText
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.nuvioControlSurface
import com.nuvio.tv.ui.v2.player.v2PlayerPanel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** "Chapter 3: Name", or "Chapter 3" when the file only numbers it. */
@Composable
internal fun chapterLabel(chapter: PlayerChapter): String =
    if (chapter.title != null) stringResource(R.string.player_chapter_named, chapter.number, chapter.title)
    else stringResource(R.string.player_chapter_number, chapter.number)

internal fun chapterLabel(context: android.content.Context, chapter: PlayerChapter): String =
    if (chapter.title != null) context.getString(R.string.player_chapter_named, chapter.number, chapter.title)
    else context.getString(R.string.player_chapter_number, chapter.number)

/** The chapter at the shown (or seek preview) position; null without chapters or before the first. */
@Composable
internal fun rememberCurrentChapter(viewModel: PlayerViewModel, chapters: List<PlayerChapter>): PlayerChapter? {
    if (chapters.isEmpty()) return null
    val timeline by viewModel.playbackTimeline.collectAsState()
    val index by remember(chapters) {
        derivedStateOf {
            val position = if (ScrubGlide.active) ScrubGlide.positionMs else timeline.currentPosition
            PlayerChapters.indexAt(chapters, position)
        }
    }
    return chapters.getOrNull(index)
}

/** Thin gaps in the timeline where each chapter starts. */
internal fun Modifier.chapterGaps(fractions: List<Float>): Modifier =
    if (fractions.isEmpty()) this else composed {
        drawWithContent {
            drawContent()
            val gap = 2.dp.toPx()
            for (fraction in fractions) {
                val x = size.width * fraction - gap / 2f
                drawRect(Color.Black.copy(alpha = 0.55f), topLeft = Offset(x, 0f), size = Size(gap, size.height))
            }
        }
    }

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ChaptersSidePanel(
    chapters: List<PlayerChapter>,
    currentPositionMs: Long,
    onChapterSelected: (PlayerChapter) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentIndex = remember(chapters) { PlayerChapters.indexAt(chapters, currentPositionMs).coerceAtLeast(0) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    val v2 = LocalV2Appearance.current != null
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(520.dp)
            .clip(RoundedCornerShape(topStart = NuvioTheme.spacing.lg, bottomStart = NuvioTheme.spacing.lg))
            .then(if (v2) Modifier.v2PlayerPanel() else Modifier.background(NuvioTheme.colors.BackgroundElevated))
    ) {
        Column(modifier = Modifier.padding(NuvioTheme.spacing.xl)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.player_chapters),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary
                )
                DialogButton(
                    text = stringResource(R.string.episodes_panel_close),
                    onClick = onClose,
                    isPrimary = false
                )
            }
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
            // The focused chapter glides to a fixed spot instead of the list jumping by whole rows.
            CompositionLocalProvider(LocalBringIntoViewSpec provides NuvioScrollDefaults.smoothScrollSpec) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                contentPadding = PaddingValues(top = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.xl),
                modifier = Modifier.fillMaxHeight()
            ) {
                itemsIndexed(chapters, key = { _, chapter -> chapter.number }) { index, chapter ->
                    ChapterItem(
                        chapter = chapter,
                        isCurrent = index == currentIndex,
                        modifier = if (index == currentIndex) Modifier.focusRequester(focusRequester) else Modifier,
                        onClick = { onChapterSelected(chapter) }
                    )
                }
            }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChapterItem(
    chapter: PlayerChapter,
    isCurrent: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val v2 = LocalV2Appearance.current != null
    var isFocused by remember { mutableStateOf(false) }
    // Looked up until the picture is in memory, then kept: a loaded row stops following the thumbnail tick.
    var painter by remember(chapter.startMs) { mutableStateOf<Painter?>(null) }
    if (painter == null) {
        val tick by SeekThumbnails.tick
        LaunchedEffect(chapter.startMs, tick) {
            painter = seekThumbnailPainter(SeekThumbnails.tileFor(chapter.startMs))
        }
    }
    val shape = RoundedCornerShape(NuvioTheme.radii.xl)
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .nuvioControlSurface(shape),
        colors = CardDefaults.colors(
            containerColor = if (v2) {
                if (isCurrent) NuvioTheme.colors.Secondary.copy(alpha = .12f) else Color.Transparent
            } else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = if (v2) Color.Transparent else NuvioTheme.colors.FocusBackground
        ),
        border = if (v2) CardDefaults.border(border = Border.None, focusedBorder = Border.None) else CardDefaults.border(
            focusedBorder = Border(border = BorderStroke(NuvioTheme.spacing.xxs, Color.White), shape = shape)
        ),
        scale = CardDefaults.scale(focusedScale = if (v2) 1f else 1.01f),
        shape = CardDefaults.shape(shape = shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(130.dp)
                    .height(73.dp)
                    .clip(RoundedCornerShape(NuvioTheme.radii.md))
                    .background(NuvioTheme.colors.SurfaceVariant)
            ) {
                painter?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize())
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(NuvioTheme.spacing.xs)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = NuvioTheme.spacing.sm, vertical = 2.dp)
                ) {
                    Text(
                        text = formatSeekTime(chapter.startMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                // Fixed-height lines (number line + two title lines) keep every row as tall as its picture,
                // so each step down the list scrolls the same distance.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (chapter.title != null) stringResource(R.string.player_chapter_number, chapter.number) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.extendedColors.textSecondary,
                        maxLines = 1
                    )
                    if (isCurrent) {
                        Text(
                            text = (if (chapter.title != null) " • " else "") + stringResource(R.string.player_chapter_now_playing),
                            style = MaterialTheme.typography.labelMedium,
                            color = NuvioTheme.colors.Secondary,
                            maxLines = 1
                        )
                    }
                }
                FocusScrollingText(
                    text = chapter.title ?: stringResource(R.string.player_chapter_number, chapter.number),
                    focused = isFocused,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    color = NuvioTheme.colors.TextPrimary,
                    startDelayMillis = 800L
                )
            }
        }
    }
}

/** Chapter starts as shares of the timeline; empty without chapters. */
@Composable
internal fun rememberChapterFractions(viewModel: PlayerViewModel, durationMs: Long): List<Float> {
    val chapters = rememberChapters(viewModel)
    return remember(chapters, durationMs) { PlayerChapters.timelineFractions(chapters, durationMs) }
}

/** The chapter under the seek cursor while seeking with the controls hidden. */
@Composable
internal fun rememberSeekChapter(viewModel: PlayerViewModel): PlayerChapter? =
    rememberCurrentChapter(viewModel, rememberChapters(viewModel))

@Composable
private fun rememberChapters(viewModel: PlayerViewModel): List<PlayerChapter> {
    val flow = remember(viewModel) { viewModel.uiState.map { it.chapters }.distinctUntilChanged() }
    return flow.collectAsState(initial = viewModel.uiState.value.chapters).value
}
