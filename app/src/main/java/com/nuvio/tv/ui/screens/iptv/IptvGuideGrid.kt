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
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.GuideGridRow
import com.nuvio.tv.core.iptv.GuideGridWindow
import com.nuvio.tv.core.iptv.GuideProgrammeCell
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

internal val ChannelColumn = 300.dp
private val RowHeight = 60.dp
private val MinuteWidth = 6.dp
private val CellShape = RoundedCornerShape(10.dp)
internal const val SLOT = GuideGridWindow.SLOT_MILLIS
private const val PAGE_ROWS = 8

@Composable
internal fun GuideGrid(state: IptvLiveState, listState: LazyListState, now: Long, cursor: Long, viewStart: Long, rowFocus: MutableMap<String, FocusRequester>,
    heading: String, modifier: Modifier, onCursor: (Long, Long) -> Unit, onRail: () -> Unit, onFocus: (IptvListedChannel) -> Unit,
    onSelect: (IptvListedChannel) -> Unit, onMenu: (IptvListedChannel) -> Unit, onNearEnd: () -> Unit) {
    BoxWithConstraints(modifier.iptvPanel().padding(horizontal = 12.dp, vertical = 10.dp)) {
        val stripWidth = maxWidth - ChannelColumn
        val visibleMillis = (stripWidth.value / MinuteWidth.value * MINUTE_MILLIS).toLong().coerceAtLeast(SLOT)
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
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(heading, style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextSecondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.width(ChannelColumn).padding(start = 12.dp, end = 12.dp))
                TimeBar(viewStart, visibleMillis, now, Modifier.weight(1f).fillMaxHeight())
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxSize().clipToBounds()) {
                if (state.channels.isEmpty()) SkeletonRows()
                CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(state.channels, key = { _, row -> row.item.channel.id }) { index, row ->
                            if (index >= state.channels.size - 8) LaunchedEffect(state.channels.size) { onNearEnd() }
                            val recordings = state.recordings.filter { it.channelId == row.item.channel.id && it.sourceId == state.source?.sourceId }
                            GuideRow(row, state.guide[row.item.channel.id], index, now, cursor, viewStart, visibleMillis,
                                row.item.channel.id == state.playingId,
                                recordings.any { it.status == com.nuvio.tv.core.iptv.RecordingStatus.RECORDING },
                                recordings.filter { it.status == com.nuvio.tv.core.iptv.RecordingStatus.SCHEDULED }.mapNotNull { it.programmeStartMillis }.toSet(), rowFocus.getOrPut(row.item.channel.id) { FocusRequester() },
                                onCursor, onRail, onFocus, onSelect, onMenu, onPage = { delta -> page(index, delta) })
                        }
                    }
                }
                if (now in viewStart until viewStart + visibleMillis) {
                    Box(Modifier.padding(start = ChannelColumn + minuteOffset(now - viewStart) - 1.dp).width(2.dp).fillMaxHeight()
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
private fun SkeletonRows() {
    val shimmer = rememberPlaceholderShimmerOffsetState("iptvGuide")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(7) {
            Row(Modifier.fillMaxWidth().height(RowHeight), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(ChannelColumn).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(56.dp, 34.dp).clip(RoundedCornerShape(8.dp))
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
private fun GuideRow(row: IptvListedChannel, grid: GuideGridRow?, index: Int, now: Long, cursor: Long, viewStart: Long, visibleMillis: Long,
    playing: Boolean, recording: Boolean, scheduled: Set<Long>, focusRequester: FocusRequester, onCursor: (Long, Long) -> Unit, onRail: () -> Unit,
    onFocus: (IptvListedChannel) -> Unit, onSelect: (IptvListedChannel) -> Unit, onMenu: (IptvListedChannel) -> Unit, onPage: (Int) -> Unit) {
    var focused by remember { mutableStateOf(false) }
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
    Row(Modifier.fillMaxWidth().height(RowHeight).clip(rowShape)
        .background(if (focused) NuvioTheme.colors.TextPrimary.copy(alpha = .06f) else Color.Transparent)
        .focusRequester(focusRequester)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus(row) }
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0 && isSelect(native.keyCode)) longPressed = false
            if (longPress.handle(native, ::isSelect) { longPressed = true; onMenu(row) }) {
                if (native.action == AndroidKeyEvent.ACTION_UP) longPressed = false
                return@onPreviewKeyEvent true
            }
            if (native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode)) {
                if (!longPressed) onSelect(row); longPressed = false
                return@onPreviewKeyEvent true
            }
            if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
            when (native.keyCode) {
                AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_INFO -> { onMenu(row); true }
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { move(1); true }
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (!move(-1)) onRail(); true }
                AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> { onPage(-PAGE_ROWS); true }
                AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> { onPage(PAGE_ROWS); true }
                else -> false
            }
        }
        .focusable(), verticalAlignment = Alignment.CenterVertically) {
        ChannelCell(row, index, playing, recording, focused, Modifier.width(ChannelColumn).fillMaxHeight())
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
                val start = maxOf(cell.startMillis, viewStart)
                val end = minOf(cell.endMillis, viewEnd)
                val width = minuteOffset(end - start) - 3.dp
                if (width <= 0.dp) continue
                ProgrammeCell(cell, now, selected = focused && cursor >= cell.startMillis && cursor < cell.endMillis, rowFocused = focused,
                    scheduled = cell.programme.start.epochMillis in scheduled,
                    modifier = Modifier.padding(start = minuteOffset(start - viewStart), top = 4.dp, bottom = 4.dp).width(width).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun Modifier.cellFocus(shape: RoundedCornerShape): Modifier =
    if (LocalV2Appearance.current != null) nuvioV2Focus(true, shape, stationary = true)
    else border(2.dp, NuvioTheme.colors.FocusRing, shape)

@Composable
private fun ChannelCell(row: IptvListedChannel, index: Int, playing: Boolean, recording: Boolean, focused: Boolean, modifier: Modifier) {
    Row(modifier.padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(3.dp).height(28.dp).clip(RoundedCornerShape(2.dp))
            .then(if (playing) Modifier.background(NuvioTheme.palette.accentBrush()) else Modifier))
        Text("${index + 1}", color = if (focused) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextTertiary,
            style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.width(40.dp))
        ChannelLogo(logoUrl(row), channelName(row), Modifier.size(56.dp, 34.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(channelName(row), color = if (focused || playing) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (focused || playing) FontWeight.SemiBold else FontWeight.Normal)
            val favourite = row.item.overlay.favouriteRank != null
            val archive = hasArchive(row)
            if (favourite || archive || playing || recording) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (playing) Text(stringResource(R.string.iptv_live_now_playing), style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.Secondary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (recording) Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(10.dp), tint = NuvioTheme.colors.Error)
                if (favourite) Icon(Icons.Filled.Star, null, Modifier.size(12.dp), tint = NuvioTheme.colors.Rating)
                if (archive) Icon(Icons.Filled.History, null, Modifier.size(12.dp), tint = NuvioTheme.colors.TextTertiary)
            }
        }
    }
}

@Composable
private fun ProgrammeCell(cell: GuideProgrammeCell, now: Long, selected: Boolean, rowFocused: Boolean, scheduled: Boolean, modifier: Modifier) {
    val airing = now >= cell.startMillis && now < cell.endMillis
    val past = cell.endMillis <= now
    val v2 = LocalV2Appearance.current != null
    val fill = when {
        selected -> if (v2) NuvioTheme.colors.Secondary.copy(alpha = .30f) else NuvioTheme.colors.FocusBackground
        airing -> NuvioTheme.colors.TextPrimary.copy(alpha = if (rowFocused) .14f else .10f)
        past -> NuvioTheme.colors.TextPrimary.copy(alpha = .03f)
        else -> NuvioTheme.colors.TextPrimary.copy(alpha = if (rowFocused) .08f else .05f)
    }
    BoxWithConstraints(modifier.then(if (selected) Modifier.cellFocus(CellShape) else Modifier).clip(CellShape).background(fill)) {
        val wide = maxWidth >= 96.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalArrangement = Arrangement.Center) {
            Text(title(cell.programme), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected || airing) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    selected -> itemContent(true)
                    past -> NuvioTheme.colors.TextTertiary
                    airing -> NuvioTheme.colors.TextPrimary
                    else -> NuvioTheme.colors.TextSecondary
                })
            if (wide) Text(timeRange(cell.programme), maxLines = 1, style = MaterialTheme.typography.labelSmall,
                color = if (selected) itemContent(true).copy(alpha = .8f) else NuvioTheme.colors.TextTertiary)
        }
        if (scheduled) Icon(Icons.Filled.FiberManualRecord, null, Modifier.align(Alignment.TopEnd).padding(5.dp).size(8.dp), tint = NuvioTheme.colors.Error)
        if (airing && !cell.openEnded) {
            val fraction = ((now - cell.programme.start.epochMillis).toFloat() /
                ((cell.programme.stop?.epochMillis ?: cell.endMillis) - cell.programme.start.epochMillis).coerceAtLeast(1)).coerceIn(0f, 1f)
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction).height(2.dp).background(NuvioTheme.palette.accentBrush()))
        }
    }
}

private fun minuteOffset(millis: Long): Dp = MinuteWidth * (millis.toFloat() / MINUTE_MILLIS)
