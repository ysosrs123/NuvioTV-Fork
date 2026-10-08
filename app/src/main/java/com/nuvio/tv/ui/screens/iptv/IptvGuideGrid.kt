@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.GuideGridRow
import com.nuvio.tv.core.iptv.GuideGridWindow
import com.nuvio.tv.core.iptv.GuideDensity
import com.nuvio.tv.core.iptv.GuideProgrammeCell
import com.nuvio.tv.core.iptv.ListMove
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.LocalizedGuideText
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsGuide
import com.nuvio.tv.core.iptv.SportsGuideCells
import com.nuvio.tv.core.iptv.guideStickyOffsetMillis
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.ui.components.placeholderCardShimmer
import com.nuvio.tv.ui.components.rememberPlaceholderShimmerOffsetState
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.accentBrush
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import java.util.Calendar
import kotlinx.coroutines.launch

private val MinuteWidth = 6.dp
private val CellShape = RoundedCornerShape(10.dp)
internal const val SLOT = GuideGridWindow.SLOT_MILLIS
private const val MIN_TITLE_MILLIS = 15 * MINUTE_MILLIS

private class GuideSpec(val row: Dp, val logo: DpSize, val column: Dp, val number: Dp, val detail: Boolean, val pageRows: Int)

private fun guideSpec(density: GuideDensity) = when (density) {
    GuideDensity.COMFORTABLE -> GuideSpec(60.dp, DpSize(46.dp, 28.dp), 330.dp, 30.dp, true, 8)
    GuideDensity.COMPACT -> GuideSpec(42.dp, DpSize(36.dp, 22.dp), 290.dp, 28.dp, false, 12)
}

@Composable
internal fun GuideGrid(state: IptvLiveState, listState: LazyListState, now: Long, cursor: Long, viewStart: Long, rowFocus: MutableMap<String, FocusRequester>,
    heading: String, modifier: Modifier, onCursor: (Long, Long) -> Unit, onRail: () -> Unit, onFocus: (IptvListedChannel) -> Unit,
    onSelect: (IptvListedChannel) -> Unit, onMenu: (IptvListedChannel) -> Unit, onNearEnd: () -> Unit,
    moving: String? = null, onMove: (IptvListedChannel, ListMove) -> Unit = { _, _ -> }, onMoveDone: () -> Unit = {},
    sport: IptvSportsGuide = NoSportsGuide, sportOnly: Boolean? = null, onSportOnly: () -> Unit = {}, onSpan: (Long) -> Unit = {},
    sportOnlyFocus: FocusRequester? = null) {
    val spec = guideSpec(state.density)
    BoxWithConstraints(modifier.iptvPanel().padding(horizontal = 6.dp, vertical = 8.dp)) {
        val stripWidth = maxWidth - spec.column
        val visibleMillis = (stripWidth.value / MinuteWidth.value * MINUTE_MILLIS).toLong().coerceAtLeast(SLOT)
        LaunchedEffect(visibleMillis) { onSpan(visibleMillis) }
        val scope = rememberCoroutineScope()
        fun page(index: Int, delta: Int) {
            val target = (index + delta).coerceIn(0, state.channels.lastIndex)
            if (target == index) return
            val id = state.channels[target].item.channel.id
            scope.launch {
                listState.scrollToItem((target - 2).coerceAtLeast(0))
                withFrameNanos { }
                rowFocus[id]?.let { runCatching { it.requestFocus() } }
            }
        }
        val movingIndex = moving?.let { id -> state.channels.indexOfFirst { it.item.channel.id == id } } ?: -1
        LaunchedEffect(moving, movingIndex) {
            if (movingIndex < 0) return@LaunchedEffect
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty() || movingIndex <= visible.first().index || movingIndex >= visible.last().index) {
                listState.scrollToItem((movingIndex - 2).coerceAtLeast(0))
                withFrameNanos { }
                moving?.let { rowFocus[it] }?.let { runCatching { it.requestFocus() } }
            }
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sportOnly != null) Row(Modifier.width(spec.column).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SportOnlyToggle(sportOnly, onSportOnly, onRail, sportOnlyFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
                    Text(heading, style = iptvHeadingStyle(), color = NuvioTheme.colors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                }
                else Text(heading, style = iptvHeadingStyle(), color = NuvioTheme.colors.TextSecondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.width(spec.column).padding(start = 10.dp, end = 12.dp))
                TimeBar(viewStart, visibleMillis, now, Modifier.weight(1f).fillMaxHeight())
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxSize().clipToBounds()) {
                if (state.channels.isEmpty() && sportOnly == true) Text(stringResource(R.string.iptv_sport5g_no_sport), style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp))
                else if (state.channels.isEmpty()) SkeletonRows(spec)
                CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(state.channels, key = { _, row -> row.item.channel.id }) { index, row ->
                            if (index >= state.channels.size - 8) LaunchedEffect(state.channels.size) { onNearEnd() }
                            val recordings = state.recordings.filter { it.channelId == row.item.channel.id && it.sourceId == row.item.channel.sourceId }
                            GuideRow(spec, row, state.guide[row.item.channel.id], index, now, cursor, viewStart, visibleMillis,
                                row.item.channel.id == state.playingId,
                                recordings.any { it.status == com.nuvio.tv.core.iptv.RecordingStatus.RECORDING },
                                recordings.filter { it.status == com.nuvio.tv.core.iptv.RecordingStatus.SCHEDULED }.mapNotNull { it.programmeStartMillis }.toSet(), rowFocus.getOrPut(row.item.channel.id) { FocusRequester() },
                                onCursor, onRail, onFocus, onSelect, onMenu, onPage = { delta -> page(index, delta * spec.pageRows) },
                                moving = row.item.channel.id == moving, onMove = { move -> onMove(row, move) }, onMoveDone = onMoveDone, sport = sport)
                        }
                    }
                }
                if (now in viewStart until viewStart + visibleMillis) {
                    Box(Modifier.padding(start = spec.column + minuteOffset(now - viewStart) - 1.dp).width(2.dp).fillMaxHeight()
                        .background(NuvioTheme.palette.accentBrush(), RoundedCornerShape(1.dp)))
                }
            }
        }
    }
}

@Composable
private fun rememberPivotSpec(): BringIntoViewSpec {
    val default = LocalBringIntoViewSpec.current
    return remember(default) {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> = default.scrollAnimationSpec
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val target = (containerSize * .34f - size / 2f).coerceAtLeast(0f)
                return if (size >= containerSize) offset else offset - target
            }
        }
    }
}

@Composable
private fun SkeletonRows(spec: GuideSpec) {
    val shimmer = rememberPlaceholderShimmerOffsetState("iptvGuide")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(if (spec.detail) 7 else 10) {
            Row(Modifier.fillMaxWidth().height(spec.row), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(spec.column).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(spec.logo).clip(RoundedCornerShape(8.dp))
                        .placeholderCardShimmer(shimmer, NuvioTheme.colors.TextPrimary.copy(alpha = .06f)))
                    Box(Modifier.width(140.dp).height(14.dp).clip(RoundedCornerShape(7.dp))
                        .placeholderCardShimmer(shimmer, NuvioTheme.colors.TextPrimary.copy(alpha = .06f)))
                }
                Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 4.dp).clip(CellShape)
                    .placeholderCardShimmer(shimmer, NuvioTheme.colors.TextPrimary.copy(alpha = .04f)))
            }
        }
    }
}

@Composable
private fun TimeBar(start: Long, span: Long, now: Long, modifier: Modifier) {
    val today = remember(now / MINUTE_MILLIS / 60) { Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_YEAR) }
    Box(modifier.clipToBounds()) {
        var slot = start
        while (slot < start + span) {
            val day = Calendar.getInstance().apply { timeInMillis = slot }
            val label = if (day.get(Calendar.DAY_OF_YEAR) != today && day.get(Calendar.HOUR_OF_DAY) == 0 && day.get(Calendar.MINUTE) == 0)
                android.text.format.DateFormat.format(android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEE"), slot).toString() + " " + clock(slot)
                else clock(slot)
            Row(Modifier.padding(start = minuteOffset(slot - start)).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(1.dp).height(12.dp).background(NuvioTheme.colors.TextPrimary.copy(alpha = .18f)))
                Text(label, color = NuvioTheme.colors.TextTertiary, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp))
            }
            slot += SLOT
        }
        if (now in start until start + span) {
            Text(clock(now), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.OnSecondary,
                maxLines = 1, modifier = Modifier.align(Alignment.CenterStart).padding(start = (minuteOffset(now - start) - 22.dp).coerceAtLeast(0.dp))
                    .clip(RoundedCornerShape(6.dp)).background(NuvioTheme.palette.accentBrush()).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun GuideRow(spec: GuideSpec, row: IptvListedChannel, grid: GuideGridRow?, index: Int, now: Long, cursor: Long, viewStart: Long, visibleMillis: Long,
    playing: Boolean, recording: Boolean, scheduled: Set<Long>, focusRequester: FocusRequester, onCursor: (Long, Long) -> Unit, onRail: () -> Unit,
    onFocus: (IptvListedChannel) -> Unit, onSelect: (IptvListedChannel) -> Unit, onMenu: (IptvListedChannel) -> Unit, onPage: (Int) -> Unit,
    moving: Boolean, onMove: (ListMove) -> Unit, onMoveDone: () -> Unit, sport: IptvSportsGuide) {
    var focused by remember { mutableStateOf(false) }
    val name = channelName(row)
    val sportsChannel = remember(name, sport.active) { sport.active && SportsGuide.isSportsChannel(listOf(LocalizedGuideText(name, null))) }
    val longPress = rememberLongPressKeyTracker()
    var longPressed by remember { mutableStateOf(false) }
    val cells = grid?.cells.orEmpty()
    fun move(step: Int): Boolean {
        val programmes = cells.filterIsInstance<GuideProgrammeCell>()
        if (programmes.isEmpty()) return false
        val current = programmes.indexOfFirst { cursor >= it.startMillis && cursor < it.endMillis }
            .takeIf { it >= 0 } ?: programmes.indexOfLast { it.startMillis <= cursor }.coerceAtLeast(0)
        val target = programmes.getOrNull(current + step) ?: return false
        val time = target.startMillis
        val start = when {
            target.startMillis < viewStart -> Math.floorDiv(target.startMillis, SLOT) * SLOT
            target.startMillis >= viewStart + visibleMillis - SLOT -> Math.floorDiv(target.startMillis, SLOT) * SLOT - SLOT
            else -> viewStart
        }
        onCursor(time, start)
        return true
    }
    val rowShape = RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxWidth().height(spec.row).clip(rowShape)
        .background(when {
            moving -> NuvioTheme.colors.Secondary.copy(alpha = .22f)
            focused -> NuvioTheme.colors.TextPrimary.copy(alpha = .06f)
            else -> Color.Transparent
        })
        .then(if (moving) Modifier.border(2.dp, NuvioTheme.colors.Secondary, rowShape) else Modifier)
        .focusRequester(focusRequester)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus(row) }
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (moving) {
                if (native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode)) { onMoveDone(); return@onPreviewKeyEvent true }
                if (isSelect(native.keyCode)) return@onPreviewKeyEvent true
                val move = when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> ListMove.UP
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> ListMove.DOWN
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_PAGE_UP, AndroidKeyEvent.KEYCODE_MOVE_HOME -> ListMove.TOP
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_PAGE_DOWN, AndroidKeyEvent.KEYCODE_MOVE_END -> ListMove.BOTTOM
                    else -> null
                } ?: return@onPreviewKeyEvent native.keyCode == AndroidKeyEvent.KEYCODE_MENU
                if (native.action == AndroidKeyEvent.ACTION_DOWN && (native.repeatCount == 0 || move == ListMove.UP || move == ListMove.DOWN)) onMove(move)
                return@onPreviewKeyEvent true
            }
            if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0 && isSelect(native.keyCode)) longPressed = false
            if (longPress.handle(native, ::isSelect) { longPressed = true; onMenu(row) }) {
                if (native.action == AndroidKeyEvent.ACTION_UP) longPressed = false
                return@onPreviewKeyEvent true
            }
            if (native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode)) {
                if (!longPressed) onSelect(row); longPressed = false
                return@onPreviewKeyEvent true
            }
            if (isSelect(native.keyCode)) return@onPreviewKeyEvent true
            if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
            when (native.keyCode) {
                AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_INFO -> { onMenu(row); true }
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { move(1); true }
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (!move(-1)) onRail(); true }
                AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> { onPage(-1); true }
                AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> { onPage(1); true }
                else -> false
            }
        }
        .focusable(), verticalAlignment = Alignment.CenterVertically) {
        ChannelCell(spec, row, index, playing, recording, focused, moving, Modifier.width(spec.column).fillMaxHeight())
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            if (cells.none { it is GuideProgrammeCell }) {
                Box(Modifier.fillMaxSize().padding(vertical = 4.dp).clip(CellShape).background(NuvioTheme.colors.TextPrimary.copy(alpha = .03f))
                    .then(if (focused) Modifier.cellFocus(CellShape) else Modifier).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart) {
                    Text(stringResource(R.string.iptv_live_no_programme), color = NuvioTheme.colors.TextTertiary,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            val viewEnd = viewStart + visibleMillis
            for (cell in cells) {
                if (cell !is GuideProgrammeCell || cell.endMillis <= viewStart || cell.startMillis >= viewEnd) continue
                val begin = maxOf(cell.startMillis, viewStart - 2 * SLOT)
                val end = minOf(cell.endMillis, viewEnd + SLOT)
                val width = minuteOffset(end - begin) - 3.dp
                val visible = minuteOffset(minOf(cell.endMillis, viewEnd) - maxOf(cell.startMillis, viewStart)) - 3.dp
                if (width <= 0.dp || visible <= 0.dp) continue
                ProgrammeCell(spec, cell, now, selected = focused && cursor >= cell.startMillis && cursor < cell.endMillis, rowFocused = focused,
                    sport = sport, fixture = if (sport.active) sport.index.at(row.item.channel.id, cell.programme.start.epochMillis) else null, sportsChannel = sportsChannel,
                    scheduled = cell.programme.start.epochMillis in scheduled, titleOffset = minuteOffset(guideStickyOffsetMillis(begin, end, viewStart, MIN_TITLE_MILLIS)),
                    continued = cell.startMillis < viewStart, wide = visible >= 96.dp,
                    modifier = Modifier.offset(x = minuteOffset(begin - viewStart)).wrapContentWidth(Alignment.Start, unbounded = true)
                        .padding(top = 4.dp, bottom = 4.dp).width(width).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun Modifier.cellFocus(shape: RoundedCornerShape): Modifier =
    if (LocalV2Appearance.current != null) nuvioV2Focus(true, shape, stationary = true)
    else border(2.dp, NuvioTheme.colors.FocusRing, shape)

@Composable
private fun ChannelCell(spec: GuideSpec, row: IptvListedChannel, index: Int, playing: Boolean, recording: Boolean, focused: Boolean, moving: Boolean, modifier: Modifier) {
    Row(modifier.padding(start = 4.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(3.dp).height(if (spec.detail) 28.dp else 20.dp).clip(RoundedCornerShape(2.dp))
            .then(if (playing) Modifier.background(NuvioTheme.palette.accentBrush()) else Modifier))
        if (moving) Box(Modifier.width(spec.number), contentAlignment = Alignment.Center) { Icon(Icons.Filled.SwapVert, null, Modifier.size(18.dp), tint = NuvioTheme.colors.Secondary) }
        else Text("${index + 1}", color = if (focused) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextTertiary,
            style = if (index < 999) iptvMetaStyle() else MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false,
            textAlign = TextAlign.End, modifier = Modifier.width(spec.number))
        ChannelLogo(logoUrl(row), channelName(row), Modifier.size(spec.logo))
        val favourite = row.item.overlay.favouriteRank != null
        val archive = hasArchive(row)
        Text(channelName(row), color = if (focused || playing) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary, maxLines = 2,
            overflow = TextOverflow.Ellipsis, style = iptvItemStyle(focused || playing, compact = !spec.detail), modifier = Modifier.weight(1f))
        if (favourite || archive || recording) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChannelMarks(recording, favourite, archive)
        }
    }
}

@Composable
private fun ChannelMarks(recording: Boolean, favourite: Boolean, archive: Boolean) {
    if (recording) Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(10.dp), tint = NuvioTheme.colors.Error)
    if (favourite) Icon(Icons.Filled.Star, null, Modifier.size(12.dp), tint = NuvioTheme.colors.Rating)
    if (archive) Icon(Icons.Filled.History, null, Modifier.size(12.dp), tint = NuvioTheme.colors.TextTertiary)
}

@Composable
private fun ProgrammeCell(spec: GuideSpec, cell: GuideProgrammeCell, now: Long, selected: Boolean, rowFocused: Boolean, sport: IptvSportsGuide,
    fixture: SportsFixture?, sportsChannel: Boolean, scheduled: Boolean, titleOffset: Dp, continued: Boolean, wide: Boolean, modifier: Modifier) {
    val airing = now >= cell.startMillis && now < cell.endMillis
    val past = cell.endMillis <= now
    val v2 = LocalV2Appearance.current != null
    val unlinked = remember(cell.programme, sportsChannel, sport.active && fixture == null) {
        if (!sport.active || fixture != null || !SportsGuide.isSportsProgramme(cell.programme.titles, cell.programme.categories, sportsChannel)) null
        else SportsGuideCells.league(cell.programme.titles).orEmpty()
    }
    val hidden = fixture != null && sport.hidden(fixture)
    val live = fixture?.status == FixtureStatus.LIVE && airing
    val close = fixture != null && SportsGuideCells.close(fixture, hidden)
    val chip = fixture?.let { SportsGuideCells.chip(it, hidden) }
    val reminded = fixture != null && sport.reminded(fixture)
    val fill = when {
        selected -> if (v2) NuvioTheme.colors.Secondary.copy(alpha = .30f) else NuvioTheme.colors.FocusBackground
        live -> NuvioTheme.colors.Error.copy(alpha = if (rowFocused) .22f else .16f)
        airing -> NuvioTheme.colors.TextPrimary.copy(alpha = if (rowFocused) .14f else .10f)
        past -> NuvioTheme.colors.TextPrimary.copy(alpha = .03f)
        else -> NuvioTheme.colors.TextPrimary.copy(alpha = if (rowFocused) .08f else .05f)
    }
    Box(modifier.then(if (selected) Modifier.cellFocus(CellShape) else Modifier).clip(CellShape).background(fill)
        .then(if (close && !selected) Modifier.border(2.dp, NuvioTheme.colors.Warning, CellShape) else Modifier)) {
        val content = when {
            selected -> itemContent(true)
            past -> NuvioTheme.colors.TextTertiary
            airing -> NuvioTheme.colors.TextPrimary
            else -> NuvioTheme.colors.TextSecondary
        }
        Row(Modifier.fillMaxSize().padding(start = (if (continued) 4.dp else 10.dp) + titleOffset, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (continued) Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, Modifier.size(14.dp), tint = content.copy(alpha = .7f))
            if (wide && fixture != null) SportGuideBadge(SportsGuideCells.badge(fixture.league), fixture.sport, Modifier.padding(end = 6.dp))
            else if (wide && unlinked != null) SportGuideBadge(unlinked.takeIf(String::isNotEmpty)?.let(SportsGuideCells::badge)
                ?: stringResource(R.string.iptv_sport5g_sport), null, Modifier.padding(end = 6.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(title(cell.programme), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = if (spec.detail) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    fontWeight = if (selected || airing) FontWeight.SemiBold else FontWeight.Normal, color = if (unlinked != null && !selected) content.copy(alpha = .8f) else content)
                if (wide && spec.detail) Text(if (reminded) "${timeRange(cell.programme)} · ${stringResource(R.string.iptv_sport5g_reminder_set)}" else timeRange(cell.programme),
                    maxLines = 1, style = MaterialTheme.typography.labelSmall, overflow = TextOverflow.Ellipsis,
                    color = if (selected) itemContent(true).copy(alpha = .8f) else if (reminded) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextTertiary)
            }
            if (chip != null && wide) SportGuideScore(chip, Modifier.padding(start = 6.dp))
        }
        if (scheduled) Icon(Icons.Filled.FiberManualRecord, null, Modifier.align(Alignment.TopEnd).padding(5.dp).size(8.dp), tint = NuvioTheme.colors.Error)
        else if (reminded && !(wide && spec.detail)) Icon(Icons.Filled.NotificationsActive, null, Modifier.align(Alignment.TopEnd).padding(4.dp).size(10.dp),
            tint = NuvioTheme.colors.Secondary)
        val game = fixture?.takeIf { live }?.let { SportsGuideCells.progress(it, now) }
        if (game != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(game).height(3.dp).background(NuvioTheme.colors.Error))
        else if (airing && !cell.openEnded) {
            val fraction = ((now - cell.programme.start.epochMillis).toFloat() /
                ((cell.programme.stop?.epochMillis ?: cell.endMillis) - cell.programme.start.epochMillis).coerceAtLeast(1)).coerceIn(0f, 1f)
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction).height(2.dp).background(NuvioTheme.palette.accentBrush()))
        }
    }
}

private fun minuteOffset(millis: Long): Dp = MinuteWidth * (millis.toFloat() / MINUTE_MILLIS)
