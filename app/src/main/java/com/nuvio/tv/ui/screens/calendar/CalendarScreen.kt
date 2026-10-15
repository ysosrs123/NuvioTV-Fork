package com.nuvio.tv.ui.screens.calendar

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.core.calendar.CalendarEpisodeLabel
import com.nuvio.tv.core.calendar.CalendarSectionKind
import com.nuvio.tv.core.calendar.CalendarShowStatus
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.MonochromePosterPlaceholder
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.screens.settings.MemoryBudget
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import java.time.LocalDate

private const val KEY_REPEAT_THROTTLE_MS = 80L

/** Sizes of the list rows and the side panel. */
internal data class CalendarMetrics(
    val rowHeight: Dp,
    val posterWidth: Dp,
    val posterHeight: Dp,
    val timeColumn: Dp,
    val statusSlot: Dp,
    val panelArtHeight: Dp
)

@Composable
internal fun calendarMetrics(): CalendarMetrics =
    CalendarMetrics(52.dp, 28.dp, 42.dp, 112.dp, 36.dp, 150.dp)

@Composable
internal fun rememberCalendarFormats(): CalendarFormats {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0] ?: java.util.Locale.getDefault()
    return remember(context, locale) { CalendarFormats.forDevice(context, locale) }
}

@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onOpenTitle: (CalendarRow) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val focus by viewModel.focus.collectAsState()
    val performance = MemoryBudget.isLowRamTier
    val formats = rememberCalendarFormats()
    var optionsRow by remember { mutableStateOf<CalendarRow?>(null) }
    val modeFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { viewModel.onScreenShown() }

    val open: (CalendarRow) -> Unit = { row ->
        HeroBackdropState.update(viewModel.focus.value?.takeIf { it.row.titleKey == row.titleKey }?.details?.backdrop)
        viewModel.onTitleOpened()
        onOpenTitle(row)
    }

    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, top = NuvioTheme.spacing.xl)
        ) {
            CalendarHeader(
                showBuiltInHeader = showBuiltInHeader,
                uiState = uiState,
                formats = formats,
                viewMode = viewMode,
                modeFocusRequester = modeFocusRequester,
                onViewMode = viewModel::setViewMode
            )
            Spacer(Modifier.height(NuvioTheme.spacing.lg))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (uiState.phase) {
                    CalendarPhase.READY -> when (viewMode) {
                        CalendarViewMode.LIST -> CalendarListView(
                            viewModel = viewModel,
                            uiState = uiState,
                            focus = focus,
                            formats = formats,
                            performance = performance,
                            onOpen = open,
                            onOptions = { optionsRow = it }
                        )
                        CalendarViewMode.MONTH -> CalendarMonthView(
                            viewModel = viewModel,
                            uiState = uiState,
                            formats = formats,
                            performance = performance,
                            onOpen = open,
                            onOptions = { optionsRow = it }
                        )
                    }
                    else -> CalendarStateMessage(uiState, modeFocusRequester)
                }
            }
        }
    }

    optionsRow?.let { row ->
        CalendarOptionsDialog(
            row = row,
            onDismiss = { optionsRow = null },
            onOpen = { optionsRow = null; open(row) },
            onBell = { optionsRow = null; viewModel.toggleBell(row) },
            onHide = { optionsRow = null; viewModel.hide(row) }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarHeader(
    showBuiltInHeader: Boolean,
    uiState: CalendarUiState,
    formats: CalendarFormats,
    viewMode: CalendarViewMode,
    modeFocusRequester: FocusRequester,
    onViewMode: (CalendarViewMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.calendar_title),
                style = MaterialTheme.typography.headlineMedium,
                color = if (showBuiltInHeader) NuvioTheme.colors.TextPrimary else Color.Transparent,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            )
            uiState.zone?.let { zone ->
                val name = remember(zone, uiState.now, formats.locale) {
                    calendarZoneName(zone, uiState.now, formats.locale, androidExemplarCity(formats.locale))
                }
                Text(
                    text = stringResource(
                        R.string.calendar_times_in,
                        name.city?.let { stringResource(R.string.calendar_zone_with_city, it, name.short) } ?: name.short
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            CalendarViewMode.entries.forEach { mode ->
                val selected = mode == viewMode
                val pill = RoundedCornerShape(NuvioTheme.radii.full)
                Button(
                    onClick = { onViewMode(mode) },
                    shape = ButtonDefaults.shape(pill),
                    scale = ButtonDefaults.scale(focusedScale = 1.1f),
                    modifier = if (selected) Modifier.focusRequester(modeFocusRequester) else Modifier,
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) NuvioTheme.colors.Secondary else NuvioTheme.colors.BackgroundCard,
                        contentColor = if (selected) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary,
                        focusedContainerColor = if (selected) NuvioTheme.colors.Secondary else NuvioTheme.colors.FocusBackground,
                        focusedContentColor = if (selected) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = pill)
                    )
                ) {
                    Text(
                        stringResource(
                            if (mode == CalendarViewMode.LIST) R.string.calendar_view_list else R.string.calendar_view_month
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarStateMessage(uiState: CalendarUiState, modeFocusRequester: FocusRequester) {
    LaunchedEffect(uiState.phase) { modeFocusRequester.requestFocusAfterFrames() }
    when (uiState.phase) {
        CalendarPhase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        CalendarPhase.BUILDING -> Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            LoadingIndicator()
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.calendar_building_title),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            if (uiState.remaining > 0) {
                Text(
                    text = pluralStringResource(R.plurals.calendar_building_progress, uiState.remaining, uiState.remaining),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary
                )
            }
        }
        CalendarPhase.NOTHING_FOLLOWED -> EmptyScreenState(
            title = stringResource(R.string.calendar_empty_title),
            subtitle = stringResource(R.string.calendar_empty_subtitle),
            icon = Icons.Default.CalendarMonth,
            height = 320.dp
        )
        CalendarPhase.ALL_QUIET -> EmptyScreenState(
            title = stringResource(R.string.calendar_quiet_title),
            subtitle = pluralStringResource(R.plurals.calendar_quiet_subtitle, uiState.windowDays, uiState.windowDays),
            icon = Icons.Default.CalendarMonth,
            height = 320.dp
        )
        CalendarPhase.READY -> Unit
    }
}

@Composable
private fun CalendarListView(
    viewModel: CalendarViewModel,
    uiState: CalendarUiState,
    focus: CalendarFocus?,
    formats: CalendarFormats,
    performance: Boolean,
    onOpen: (CalendarRow) -> Unit,
    onOptions: (CalendarRow) -> Unit
) {
    val list = uiState.list
    val listState = rememberLazyListState()
    val targetRequester = remember { FocusRequester() }
    val pendingKey by viewModel.pendingListKey.collectAsState()
    var targetKey by remember { mutableStateOf<String?>(null) }
    var placed by remember { mutableStateOf(false) }
    val userMoved = remember { booleanArrayOf(false) }

    LaunchedEffect(list, pendingKey) {
        val opening = calendarOpeningRowKey(list)
        val rebuilt = placed && !userMoved[0] && viewModel.lastFocusedListKey == null && opening != targetKey
        if (placed && pendingKey == null && !rebuilt) return@LaunchedEffect
        val wanted = pendingKey?.takeIf { list.indexOf(it) >= 0 }
            ?: viewModel.lastFocusedListKey?.takeIf { list.indexOf(it) >= 0 && !rebuilt }
            ?: opening
            ?: return@LaunchedEffect
        val index = list.indexOf(wanted)
        withFrameNanos { }
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.scrollToItem((index - 1).coerceAtLeast(0))
        }
        targetKey = wanted
        targetRequester.requestFocusAfterFrames(1)
        placed = true
        viewModel.consumePendingListKey()
    }

    Row(Modifier.fillMaxSize()) {
        CalendarList(
            list = list,
            listState = listState,
            formats = formats,
            performance = performance,
            targetKey = targetKey,
            targetRequester = targetRequester,
            modifier = Modifier.weight(0.62f).fillMaxHeight(),
            onFocused = { viewModel.onRowFocused(it, fromList = userMoved[0]) },
            onUserKey = { userMoved[0] = true },
            onOpen = onOpen,
            onBell = viewModel::toggleBell,
            onOptions = onOptions
        )
        Spacer(Modifier.width(NuvioTheme.spacing.xl))
        CalendarSidePanel(
            focus = focus,
            formats = formats,
            today = uiState.today,
            modifier = Modifier.weight(0.38f).fillMaxHeight().padding(bottom = NuvioTheme.spacing.xxl)
        )
    }
}

@Composable
private fun CalendarList(
    list: CalendarListModel,
    listState: LazyListState,
    formats: CalendarFormats,
    performance: Boolean,
    targetKey: String?,
    targetRequester: FocusRequester,
    modifier: Modifier,
    onFocused: (CalendarRow) -> Unit,
    onUserKey: () -> Unit,
    onOpen: (CalendarRow) -> Unit,
    onBell: (CalendarRow) -> Unit,
    onOptions: (CalendarRow) -> Unit
) {
    val lastKeyRepeat = remember { longArrayOf(0L) }
    LazyColumn(
        state = listState,
        modifier = modifier
            .focusRestorer()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_DOWN) onUserKey()
                if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount > 0) {
                    val now = System.currentTimeMillis()
                    if (now - lastKeyRepeat[0] < KEY_REPEAT_THROTTLE_MS) return@onPreviewKeyEvent true
                    lastKeyRepeat[0] = now
                }
                false
            },
        contentPadding = PaddingValues(top = NuvioTheme.spacing.xxs, bottom = NuvioTheme.spacing.xxxl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
    ) {
        items(list.entries, key = { it.key }, contentType = { if (it is CalendarHeaderEntry) 0 else 1 }) { entry ->
            when (entry) {
                is CalendarHeaderEntry -> CalendarSectionHeading(entry, formats)
                is CalendarRowEntry -> CalendarRowItem(
                    row = entry.row,
                    formats = formats,
                    performance = performance,
                    focusRequester = if (entry.key == targetKey) targetRequester else null,
                    onFocused = onFocused,
                    onOpen = onOpen,
                    onBell = onBell,
                    onOptions = onOptions
                )
            }
        }
    }
}

@Composable
private fun CalendarSectionHeading(entry: CalendarHeaderEntry, formats: CalendarFormats) {
    val (main, hint) = when (entry.kind) {
        CalendarSectionKind.JUST_AIRED ->
            stringResource(R.string.calendar_section_just_aired) to stringResource(R.string.calendar_section_just_aired_hint)
        CalendarSectionKind.TODAY -> stringResource(R.string.calendar_section_today) to entry.date?.let(formats::shortDate)
        CalendarSectionKind.TOMORROW -> stringResource(R.string.calendar_section_tomorrow) to entry.date?.let(formats::shortDate)
        CalendarSectionKind.DAY -> (entry.date?.let(formats::longDate) ?: "") to null
        CalendarSectionKind.LATER ->
            stringResource(R.string.calendar_section_later) to stringResource(R.string.calendar_section_later_hint)
        CalendarSectionKind.NO_DATE ->
            stringResource(R.string.calendar_section_no_date) to stringResource(R.string.calendar_section_no_date_hint)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = NuvioTheme.spacing.xs, top = NuvioTheme.spacing.md, bottom = NuvioTheme.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        Text(
            text = main,
            modifier = Modifier.alignByBaseline(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1
        )
        hint?.let {
            Text(
                text = it,
                modifier = Modifier.alignByBaseline(),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun isSelectKey(keyCode: Int): Boolean =
    keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CalendarRowItem(
    row: CalendarRow,
    formats: CalendarFormats,
    performance: Boolean,
    focusRequester: FocusRequester?,
    onFocused: (CalendarRow) -> Unit,
    onOpen: (CalendarRow) -> Unit,
    onBell: (CalendarRow) -> Unit,
    onOptions: (CalendarRow) -> Unit,
    compact: Boolean = false
) {
    val metrics = calendarMetrics()
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    var focused by remember { mutableStateOf(false) }
    var bellFocused by remember { mutableStateOf(false) }
    var longPressed by remember { mutableStateOf(false) }
    val longPress = rememberLongPressKeyTracker()
    val contentAlpha = if (row.dimmed) 0.6f else 1f
    val notAnnounced = stringResource(R.string.calendar_time_not_announced)
    val (timePrimary, timeSecondary) = calendarTimeTexts(row, formats, showDate = !compact)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Card(
            onClick = { if (longPressed) longPressed = false else onOpen(row) },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = metrics.rowHeight)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused(row)
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                        longPressed = true
                        onOptions(row)
                        return@onPreviewKeyEvent true
                    }
                    if (longPress.handle(native, ::isSelectKey) { longPressed = true; onOptions(row) }) {
                        if (native.action == AndroidKeyEvent.ACTION_UP) longPressed = false
                        return@onPreviewKeyEvent true
                    }
                    if (native.action == AndroidKeyEvent.ACTION_UP && longPressed &&
                        (isSelectKey(native.keyCode) || native.keyCode == AndroidKeyEvent.KEYCODE_MENU)
                    ) {
                        longPressed = false
                        return@onPreviewKeyEvent true
                    }
                    false
                },
            shape = CardDefaults.shape(shape),
            colors = CardDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = NuvioTheme.colors.FocusBackground
            ),
            border = CardDefaults.border(
                border = Border.None,
                focusedBorder = Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
            ),
            scale = CardDefaults.scale(focusedScale = if (performance) 1f else 1.02f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = metrics.rowHeight)
                    .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CalendarPoster(
                    url = row.poster,
                    width = metrics.posterWidth,
                    height = metrics.posterHeight,
                    modifier = Modifier.alpha(contentAlpha)
                )
                Spacer(Modifier.width(NuvioTheme.spacing.md))
                Column(Modifier.weight(1f).alpha(contentAlpha)) {
                    Text(
                        text = row.name ?: stringResource(R.string.calendar_unknown_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    // The label sits beside the episode line when both fit, otherwise on its own line under it.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                        itemVerticalAlignment = Alignment.CenterVertically
                    ) {
                        val line = calendarRowLine(row)
                        if (line.isNotEmpty()) {
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        calendarLabelText(row.label)?.let { label ->
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = NuvioTheme.colors.Secondary,
                                maxLines = 1
                            )
                        }
                    }
                    if (compact && timePrimary != null) {
                        val quiet = timePrimary == notAnnounced
                        Text(
                            text = timePrimary,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (quiet) FontWeight.Normal else FontWeight.SemiBold,
                            color = if (quiet) NuvioTheme.colors.TextTertiary else NuvioTheme.colors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (!compact) CalendarTimeColumn(timePrimary, timeSecondary, notAnnounced, metrics.timeColumn)
            }
        }
        Box(
            Modifier
                .width(metrics.statusSlot + NuvioTheme.spacing.sm)
                .onFocusChanged { bellFocused = it.hasFocus },
            contentAlignment = Alignment.CenterEnd
        ) {
            CalendarStatus(row, metrics.statusSlot, showBellOff = focused || bellFocused, onBell = onBell)
        }
    }
}

/** The time and, where it helps, the date for the right of a row; nothing for shows without a date. */
@Composable
internal fun calendarTimeTexts(row: CalendarRow, formats: CalendarFormats, showDate: Boolean): Pair<String?, String?> {
    val timeText = row.time?.let(formats::time)
    val notAnnounced = stringResource(R.string.calendar_time_not_announced)
    return when {
        row.kind == CalendarRowKind.SHOW -> null to null
        row.section == CalendarSectionKind.LATER -> row.date?.let(formats::yearDate) to (timeText ?: notAnnounced.takeUnless { row.isFilm })
        row.section == CalendarSectionKind.JUST_AIRED && showDate -> (timeText ?: row.date?.let(formats::shortDate)) to
            (if (timeText != null) row.date?.let(formats::shortDate) else null)
        row.isFilm -> null to null
        else -> (timeText ?: notAnnounced) to null
    }
}

@Composable
private fun CalendarTimeColumn(primary: String?, secondary: String?, notAnnounced: String, width: Dp) {
    Column(Modifier.width(width).padding(start = NuvioTheme.spacing.sm), horizontalAlignment = Alignment.End) {
        primary?.let {
            val quiet = it == notAnnounced
            Text(
                text = it,
                style = if (quiet) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall,
                fontWeight = if (quiet) FontWeight.Normal else FontWeight.SemiBold,
                color = if (quiet) NuvioTheme.colors.TextTertiary else NuvioTheme.colors.TextPrimary,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        secondary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The bell shows while it is on; the outline bell to switch it on only on the focused row. Aired episodes get a tick
 * once watched and nothing before that.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarStatus(row: CalendarRow, size: Dp, showBellOff: Boolean, onBell: (CalendarRow) -> Unit) {
    when (row.status) {
        CalendarRowStatus.BELL_ON, CalendarRowStatus.BELL_OFF -> {
            val on = row.status == CalendarRowStatus.BELL_ON
            Card(
                onClick = { onBell(row) },
                modifier = Modifier.size(size),
                shape = CardDefaults.shape(CircleShape),
                colors = CardDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground
                ),
                border = CardDefaults.border(
                    border = Border.None,
                    focusedBorder = Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = CircleShape)
                ),
                scale = CardDefaults.scale(focusedScale = 1f)
            ) {
                if (on || showBellOff) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (on) Icons.Default.Notifications else Icons.Default.NotificationsNone,
                            contentDescription = stringResource(if (on) R.string.calendar_cd_bell_on else R.string.calendar_cd_bell_off),
                            tint = if (on) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextTertiary,
                            modifier = Modifier.size(size * 0.5f)
                        )
                    }
                }
            }
        }
        CalendarRowStatus.WATCHED -> Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.calendar_cd_watched),
                tint = NuvioTheme.colors.Secondary,
                modifier = Modifier.size(size * 0.5f)
            )
        }
        CalendarRowStatus.NOT_WATCHED, CalendarRowStatus.NONE -> Spacer(Modifier.size(size))
    }
}

@Composable
internal fun CalendarPoster(url: String?, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val shape = RoundedCornerShape(NuvioTheme.spacing.xs)
    Box(modifier.size(width, height).clip(shape)) {
        if (url.isNullOrBlank()) {
            MonochromePosterPlaceholder()
        } else {
            val widthPx = with(density) { width.roundToPx() }.coerceAtLeast(1)
            val heightPx = with(density) { height.roundToPx() }.coerceAtLeast(1)
            val request = remember(url, widthPx, heightPx) {
                ImageRequest.Builder(context)
                    .data(url)
                    .size(widthPx, heightPx)
                    .memoryCacheKey("${url}_${widthPx}x$heightPx")
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(NuvioTheme.colors.BackgroundCard)
            )
        }
    }
}

@Composable
internal fun calendarRowLine(row: CalendarRow): String = when (row.kind) {
    CalendarRowKind.EPISODE -> {
        val code = if (row.season != null && row.episode != null) {
            stringResource(R.string.calendar_episode_code, row.season, row.episode)
        } else null
        when {
            code != null && row.episodeTitle != null -> stringResource(R.string.calendar_line_join, code, row.episodeTitle)
            else -> code ?: row.episodeTitle.orEmpty()
        }
    }
    CalendarRowKind.FILM_CINEMA -> stringResource(R.string.calendar_film_cinema)
    CalendarRowKind.FILM_DIGITAL -> stringResource(R.string.calendar_film_digital)
    CalendarRowKind.SHOW -> when (row.showStatus) {
        CalendarShowStatus.IN_PRODUCTION -> stringResource(R.string.calendar_show_in_production)
        CalendarShowStatus.RUNNING -> stringResource(R.string.calendar_show_returning)
        else -> stringResource(R.string.calendar_show_next_unknown)
    }
}

@Composable
internal fun calendarLabelText(label: CalendarEpisodeLabel): String? = when (label) {
    CalendarEpisodeLabel.NONE -> null
    CalendarEpisodeLabel.NEW_SHOW -> stringResource(R.string.calendar_label_new_show)
    CalendarEpisodeLabel.SEASON_PREMIERE -> stringResource(R.string.calendar_label_season_premiere)
    CalendarEpisodeLabel.MID_SEASON_PREMIERE -> stringResource(R.string.calendar_label_mid_season_premiere)
    CalendarEpisodeLabel.MID_SEASON_FINALE -> stringResource(R.string.calendar_label_mid_season_finale)
    CalendarEpisodeLabel.SEASON_FINALE -> stringResource(R.string.calendar_label_season_finale)
    CalendarEpisodeLabel.SERIES_FINALE -> stringResource(R.string.calendar_label_series_finale)
}

/** "Today · 8:30 pm", "Aired Monday 5 October · 9:00 pm" and the like, for the panel's first line. */
@Composable
private fun calendarWhenLine(row: CalendarRow, formats: CalendarFormats, today: LocalDate?): String? {
    val date = row.date ?: return null
    val day = when (date) {
        today -> stringResource(R.string.calendar_section_today)
        today?.plusDays(1) -> stringResource(R.string.calendar_section_tomorrow)
        else -> if (today != null && date.year != today.year) formats.yearDate(date) else formats.longDate(date)
    }
    val aired = row.status == CalendarRowStatus.WATCHED || row.status == CalendarRowStatus.NOT_WATCHED
    val withTime = row.time?.let { stringResource(R.string.calendar_line_join, day, formats.time(it)) } ?: day
    return when {
        aired && date != today -> stringResource(if (row.isFilm) R.string.calendar_out_on else R.string.calendar_aired_on, withTime)
        else -> withTime
    }
}

@Composable
private fun CalendarSidePanel(
    focus: CalendarFocus?,
    formats: CalendarFormats,
    today: LocalDate?,
    modifier: Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val row = focus?.row ?: return
    val details = focus.details
    Box(modifier.clip(shape).background(NuvioTheme.colors.BackgroundCard)) {
        Column(Modifier.fillMaxSize()) {
            if (details?.backdrop != null) {
                CalendarPanelArt(details.backdrop)
            }
            Column(
                Modifier.fillMaxWidth().padding(NuvioTheme.spacing.sidePanel.inner),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                calendarWhenLine(row, formats, today)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.colors.Secondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = row.name ?: stringResource(R.string.calendar_unknown_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = calendarRowLine(row),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = listOfNotNull(calendarLabelText(row.label), details?.let { calendarRuntimeText(it) }, row.network)
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                details?.synopsis?.let {
                    Spacer(Modifier.height(NuvioTheme.spacing.xs))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarPanelArt(url: String) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val height = calendarMetrics().panelArtHeight
    val heightPx = with(density) { height.roundToPx() }.coerceAtLeast(1)
    val request = remember(url, heightPx) {
        ImageRequest.Builder(context).data(url).size(heightPx * 16 / 9, heightPx).crossfade(false).build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().height(height).background(NuvioTheme.colors.BackgroundElevated)
    )
}

@Composable
private fun CalendarOptionsDialog(
    row: CalendarRow,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onBell: () -> Unit,
    onHide: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusAfterFrames() }
    NuvioDialog(
        onDismiss = onDismiss,
        title = row.name ?: stringResource(R.string.calendar_unknown_title),
        subtitle = stringResource(R.string.calendar_options_subtitle)
    ) {
        CalendarDialogAction(
            label = stringResource(if (row.isFilm) R.string.calendar_action_open_film else R.string.calendar_action_open_show),
            onClick = onOpen,
            focusRequester = first
        )
        if (row.status.hasBell) {
            CalendarDialogAction(
                label = stringResource(
                    when {
                        row.status == CalendarRowStatus.BELL_ON -> R.string.calendar_action_stop_remind
                        row.isFilm -> R.string.calendar_action_remind_film
                        else -> R.string.calendar_action_remind
                    }
                ),
                onClick = onBell
            )
        }
        CalendarDialogAction(label = stringResource(R.string.calendar_action_hide), onClick = onHide)
    }
}

/** Where the list opens: the first row of Today, else the first of what comes next, else the first row. */
internal fun calendarOpeningRowKey(list: CalendarListModel): String? =
    list.firstRowKeyIn(CalendarSectionKind.TODAY) ?: list.startRowKey

@Composable
private fun calendarRuntimeText(details: CalendarRowDetails): String? {
    details.runtimeMinutes?.let { minutes ->
        return calendarRuntime(
            minutes,
            stringResource(R.string.calendar_runtime_hours_minutes),
            stringResource(R.string.calendar_runtime_hours),
            stringResource(R.string.calendar_runtime_minutes)
        )
    }
    return details.filmRuntime
}
