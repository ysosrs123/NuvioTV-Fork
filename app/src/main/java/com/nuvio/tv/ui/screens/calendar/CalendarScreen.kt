package com.nuvio.tv.ui.screens.calendar

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.Brush
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
import com.nuvio.tv.core.calendar.CalendarWatchTarget
import com.nuvio.tv.ui.screens.player.LoadingOverlay
import kotlinx.coroutines.launch
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.MonochromePosterPlaceholder
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.PanelActionRow
import com.nuvio.tv.ui.components.TvdbCreditLine
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.appearance.V2Atmosphere
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.NuvioGlassSurface
import com.nuvio.tv.ui.v2.components.nuvioControlSurface
import com.nuvio.tv.ui.v2.components.nuvioGlass
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import com.nuvio.tv.ui.v2.components.sidebarPageContent
import com.nuvio.tv.ui.v2.quality.LocalVisualQuality
import com.nuvio.tv.ui.v2.quality.VisualQualityTier
import java.time.LocalDate

private const val KEY_REPEAT_THROTTLE_MS = 80L

/** Sizes that differ between the two visual styles; V2 draws on a larger canvas. */
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
    if (LocalV2Appearance.current != null) CalendarMetrics(64.dp, 88.dp, 50.dp, 132.dp, 44.dp, 140.dp)
    else CalendarMetrics(58.dp, 80.dp, 45.dp, 112.dp, 36.dp, 140.dp)

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
    onOpenTitle: (CalendarRow) -> Unit,
    onWatch: (CalendarWatchTarget) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val focus by viewModel.focus.collectAsState()
    val v2 = LocalV2Appearance.current
    val performance = LocalVisualQuality.current.tier == VisualQualityTier.PERFORMANCE
    val formats = rememberCalendarFormats()
    var optionsRow by remember { mutableStateOf<CalendarRow?>(null) }
    val modeFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var finding by remember { mutableStateOf<CalendarRow?>(null) }
    LaunchedEffect(Unit) { viewModel.onScreenShown() }
    BackHandler(enabled = finding != null) { finding = null }

    val backdrop = focus?.details?.backdrop ?: focus?.row?.backdrop
    LaunchedEffect(backdrop, v2 != null) {
        if (v2 == null) return@LaunchedEffect
        HeroBackdropState.selectPageArtwork(ownerKey = backdrop?.let { "calendar" }, url = backdrop)
    }

    val open: (CalendarRow) -> Unit = { row ->
        HeroBackdropState.update(viewModel.focus.value?.takeIf { it.row.titleKey == row.titleKey }?.details?.backdrop ?: row.backdrop)
        viewModel.onTitleOpened()
        onOpenTitle(row)
    }
    val watch: (CalendarRow) -> Unit = { row ->
        finding = row
        scope.launch {
            val target = viewModel.watchTarget(row)
            if (finding?.key != row.key) return@launch
            finding = null
            if (target != null) {
                viewModel.onTitleOpened()
                onWatch(target)
            } else open(row)
        }
    }

    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        if (v2 != null) V2Atmosphere(background = v2.settingsBackground)
        if (v2 == null && viewMode == CalendarViewMode.WEEK && uiState.phase == CalendarPhase.READY) {
            CalendarPageBackdrop(backdrop)
        }

        Column(
            modifier = Modifier
                .sidebarPageContent()
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
                        CalendarViewMode.WEEK -> CalendarWeekView(
                            viewModel = viewModel,
                            uiState = uiState,
                            focus = focus,
                            formats = formats,
                            performance = performance,
                            onOpen = open,
                            onOptions = { optionsRow = it }
                        )
                        CalendarViewMode.LIST -> CalendarListView(
                            viewModel = viewModel,
                            uiState = uiState,
                            focus = focus,
                            formats = formats,
                            performance = performance,
                            onOpen = open,
                            onWatch = watch,
                            onOptions = { optionsRow = it }
                        )
                        CalendarViewMode.MONTH -> CalendarMonthView(
                            viewModel = viewModel,
                            uiState = uiState,
                            formats = formats,
                            performance = performance,
                            onOpen = open,
                            onOptions = { optionsRow = it },
                            tabsRequester = modeFocusRequester
                        )
                    }
                    else -> CalendarStateMessage(uiState, modeFocusRequester)
                }
            }
            TvdbCreditLine(
                Modifier
                    .align(Alignment.End)
                    .padding(top = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.sm)
            )
        }
    }

    finding?.let { row ->
        LoadingOverlay(
            visible = true,
            backdropUrl = row.backdrop,
            logoUrl = row.logo,
            title = row.name,
            message = stringResource(R.string.calendar_reminder_finding),
            modifier = Modifier.fillMaxSize()
        )
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
    val v2 = LocalV2Appearance.current != null
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
                    shape = ButtonDefaults.shape(if (v2) RoundedCornerShape(NuvioTheme.radii.md) else pill),
                    scale = ButtonDefaults.scale(focusedScale = if (v2) 1f else 1.1f),
                    modifier = Modifier
                        .nuvioControlSurface(RoundedCornerShape(NuvioTheme.radii.md))
                        .then(if (selected) Modifier.focusRequester(modeFocusRequester) else Modifier),
                    colors = ButtonDefaults.colors(
                        containerColor = if (v2) {
                            if (selected) NuvioTheme.colors.Secondary.copy(alpha = .12f) else Color.Transparent
                        } else if (selected) NuvioTheme.colors.Secondary else NuvioTheme.colors.BackgroundCard,
                        contentColor = if (!v2 && selected) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary,
                        focusedContainerColor = when {
                            v2 -> Color.Transparent
                            selected -> NuvioTheme.colors.Secondary
                            else -> NuvioTheme.colors.FocusBackground
                        },
                        focusedContentColor = when {
                            v2 -> Color.White
                            selected -> NuvioTheme.colors.OnSecondary
                            else -> NuvioTheme.colors.TextPrimary
                        }
                    ),
                    border = if (v2) ButtonDefaults.border() else ButtonDefaults.border(
                        focusedBorder = Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = pill)
                    )
                ) {
                    Text(
                        stringResource(
                            when (mode) {
                                CalendarViewMode.WEEK -> R.string.calendar_view_week
                                CalendarViewMode.LIST -> R.string.calendar_view_list
                                CalendarViewMode.MONTH -> R.string.calendar_view_month
                            }
                        )
                    )
                }
            }
        }
    }
}

/** The Original look has no page artwork of its own, so the Week view draws the focused title's backdrop. */
@Composable
private fun CalendarPageBackdrop(url: String?) {
    if (url.isNullOrBlank()) return
    val background = NuvioTheme.colors.Background
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.72f).fillMaxHeight(0.72f)) {
            CalendarArtImage(url = url, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to background, 0.45f to background.copy(alpha = 0.55f), 1f to Color.Transparent)
                )
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.4f to Color.Transparent, 1f to background)
                )
            )
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
    onWatch: (CalendarRow) -> Unit,
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
            next = focus?.row?.let { calendarNextFrom(uiState.byTitle, it) }.orEmpty(),
            performance = performance,
            onWatch = onWatch,
            onOpen = onOpen,
            onBell = viewModel::toggleBell,
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

/** Holding OK or pressing Menu opens the options; the click that ends a long press does not open the title. */
internal class CalendarPress {
    var longPressed by mutableStateOf(false)

    fun click(action: () -> Unit) {
        if (longPressed) longPressed = false else action()
    }
}

@Composable
internal fun Modifier.calendarOptionsKeys(press: CalendarPress, onOptions: () -> Unit): Modifier {
    val longPress = rememberLongPressKeyTracker()
    return onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
            press.longPressed = true
            onOptions()
            return@onPreviewKeyEvent true
        }
        if (longPress.handle(native, ::isSelectKey) { press.longPressed = true; onOptions() }) {
            if (native.action == AndroidKeyEvent.ACTION_UP) press.longPressed = false
            return@onPreviewKeyEvent true
        }
        if (native.action == AndroidKeyEvent.ACTION_UP && press.longPressed &&
            (isSelectKey(native.keyCode) || native.keyCode == AndroidKeyEvent.KEYCODE_MENU)
        ) {
            press.longPressed = false
            return@onPreviewKeyEvent true
        }
        false
    }
}

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
    val v2 = LocalV2Appearance.current != null
    val metrics = calendarMetrics()
    val shape = RoundedCornerShape(if (v2) NuvioTheme.radii.md else NuvioTheme.radii.sm)
    var focused by remember { mutableStateOf(false) }
    var bellFocused by remember { mutableStateOf(false) }
    val press = remember { CalendarPress() }
    val contentAlpha = if (row.dimmed) 0.6f else 1f
    val notAnnounced = stringResource(calendarNoTimeLabel(row))
    val (timePrimary, timeSecondary) = calendarTimeTexts(row, formats, showDate = !compact)
    val estimate = if (row.time == null) calendarTimeText(row, formats) else null
    val quietTime = timePrimary == notAnnounced || (estimate != null && timePrimary == estimate)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Card(
            onClick = { press.click { onOpen(row) } },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = metrics.rowHeight)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused(row)
                }
                .calendarOptionsKeys(press) { onOptions(row) }
                .nuvioV2Focus(focused, shape, stationary = true)
                .then(if (v2 && focused) Modifier.nuvioGlass(GlassRole.CONTROL, focused = true, shape = shape) else Modifier),
            shape = CardDefaults.shape(shape),
            colors = CardDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = if (v2) Color.Transparent else NuvioTheme.colors.FocusBackground
            ),
            border = CardDefaults.border(
                border = Border.None,
                focusedBorder = if (v2) Border.None else Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
            ),
            scale = CardDefaults.scale(focusedScale = if (v2 || performance) 1f else 1.02f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = metrics.rowHeight)
                    .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CalendarArtImage(
                    url = row.backdrop,
                    fallback = row.poster,
                    modifier = Modifier
                        .size(metrics.posterWidth, metrics.posterHeight)
                        .alpha(contentAlpha)
                        .clip(RoundedCornerShape(NuvioTheme.spacing.xs))
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
                        val quiet = quietTime
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
                if (!compact) CalendarTimeColumn(timePrimary, timeSecondary, quietTime, metrics.timeColumn)
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

/** What a row shows in place of a time: a show that streams first is out that day, its hour is just not given. */
internal fun calendarNoTimeLabel(row: CalendarRow): Int =
    if (row.streaming) R.string.calendar_streaming else R.string.calendar_time_not_announced

/** The announced time, else the service's usual hour as an estimate ("About 7:00 PM"). */
@Composable
internal fun calendarTimeText(row: CalendarRow, formats: CalendarFormats): String? =
    row.time?.let(formats::time) ?: row.usualTime?.let { stringResource(R.string.calendar_time_about, formats.time(it)) }

/** The time and, where it helps, the date for the right of a row; nothing for shows without a date. */
@Composable
internal fun calendarTimeTexts(row: CalendarRow, formats: CalendarFormats, showDate: Boolean): Pair<String?, String?> {
    val timeText = calendarTimeText(row, formats)
    val notAnnounced = stringResource(calendarNoTimeLabel(row))
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
private fun CalendarTimeColumn(primary: String?, secondary: String?, quiet: Boolean, width: Dp) {
    Column(Modifier.width(width).padding(start = NuvioTheme.spacing.sm), horizontalAlignment = Alignment.End) {
        primary?.let {
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
    val v2 = LocalV2Appearance.current != null
    when (row.status) {
        CalendarRowStatus.BELL_ON, CalendarRowStatus.BELL_OFF -> {
            var focused by remember { mutableStateOf(false) }
            val on = row.status == CalendarRowStatus.BELL_ON
            Card(
                onClick = { onBell(row) },
                modifier = Modifier
                    .size(size)
                    .onFocusChanged { focused = it.isFocused }
                    .nuvioV2Focus(focused, CircleShape, stationary = true)
                    .then(if (v2 && focused) Modifier.nuvioGlass(GlassRole.CONTROL, focused = true, shape = CircleShape) else Modifier),
                shape = CardDefaults.shape(CircleShape),
                colors = CardDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = if (v2) Color.Transparent else NuvioTheme.colors.FocusBackground
                ),
                border = CardDefaults.border(
                    border = Border.None,
                    focusedBorder = if (v2) Border.None else Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = CircleShape)
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
internal fun calendarWhenLine(row: CalendarRow, formats: CalendarFormats, today: LocalDate?): String? {
    val date = row.date ?: return null
    val day = when (date) {
        today -> stringResource(R.string.calendar_section_today)
        today?.plusDays(1) -> stringResource(R.string.calendar_section_tomorrow)
        else -> if (today != null && date.year != today.year) formats.yearDate(date) else formats.longDate(date)
    }
    val aired = row.status == CalendarRowStatus.WATCHED || row.status == CalendarRowStatus.NOT_WATCHED
    val withTime = calendarTimeText(row, formats)?.let { stringResource(R.string.calendar_line_join, day, it) } ?: day
    return when {
        aired && date != today -> stringResource(if (row.isFilm) R.string.calendar_out_on else R.string.calendar_aired_on, withTime)
        else -> withTime
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarSidePanel(
    focus: CalendarFocus?,
    formats: CalendarFormats,
    today: LocalDate?,
    next: List<CalendarRow>,
    performance: Boolean,
    onWatch: (CalendarRow) -> Unit,
    onOpen: (CalendarRow) -> Unit,
    onBell: (CalendarRow) -> Unit,
    modifier: Modifier
) {
    val v2 = LocalV2Appearance.current != null
    val shape = RoundedCornerShape(if (v2) NuvioTheme.radii.xxl else NuvioTheme.radii.md)
    val container: @Composable (@Composable () -> Unit) -> Unit = { content ->
        if (v2) {
            NuvioGlassSurface(GlassRole.PANEL, modifier, shape = shape) { content() }
        } else {
            Box(modifier.clip(shape).background(NuvioTheme.colors.BackgroundCard)) { content() }
        }
    }
    val row = focus?.row ?: return
    val details = focus.details
    val scroll = remember(row.key) { ScrollState(0) }
    container {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth().height(calendarMetrics().panelArtHeight)) {
                CalendarArtImage(
                    url = details?.still(row.season, row.episode),
                    alternate = row.backdrop ?: details?.backdrop,
                    fallback = row.poster,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize()
                )
                Box(Modifier.fillMaxSize().background(calendarArtScrim()))
                CalendarLogo(
                    logo = row.logo ?: details?.logo,
                    name = row.name,
                    height = 36.dp,
                    maxWidth = 220.dp,
                    textStyle = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.align(Alignment.BottomStart).padding(NuvioTheme.spacing.md)
                )
            }
            Column(
                Modifier.fillMaxWidth().padding(NuvioTheme.spacing.sidePanel.inner),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                calendarWhenLine(row, formats, today)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.colors.Secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = calendarRowLine(row).ifEmpty { row.name ?: stringResource(R.string.calendar_unknown_title) },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = listOfNotNull(
                    calendarLabelText(row.label),
                    row.network,
                    details?.let { calendarRuntimeText(it) },
                    calendarRatingGenre(row.rating ?: details?.rating, row.genre ?: details?.genre)
                )
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                details?.synopsis?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = if (next.isEmpty()) 5 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(NuvioTheme.spacing.xs))
                CalendarPanelActions(row, performance, onWatch, onOpen, onBell)
                if (next.isNotEmpty()) {
                    Spacer(Modifier.height(NuvioTheme.spacing.xs))
                    Text(
                        text = stringResource(R.string.calendar_next_from, row.name ?: stringResource(R.string.calendar_unknown_title)),
                        style = MaterialTheme.typography.titleSmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    next.forEach { upcoming -> CalendarNextRow(upcoming, details, formats, today, performance, onOpen) }
                }
            }
        }
    }
}

@Composable
private fun CalendarPanelAction(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    performance: Boolean,
    onClick: () -> Unit
) {
    val v2 = LocalV2Appearance.current != null
    val pill = RoundedCornerShape(NuvioTheme.radii.full)
    CalendarFocusCard(onClick = onClick, shape = pill, performance = performance, focusedScale = 1.06f) { focused ->
        Row(
            Modifier
                .then(if (v2 && focused) Modifier.nuvioGlass(GlassRole.CONTROL, focused = true, shape = pill) else Modifier)
                .clip(pill)
                .background(
                    if (!v2 && focused) NuvioTheme.colors.FocusBackground
                    else NuvioTheme.colors.BackgroundElevated.copy(alpha = if (v2) 0.5f else 1f)
                )
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            icon?.let { Icon(it, contentDescription = null, tint = NuvioTheme.colors.TextPrimary, modifier = Modifier.size(18.dp)) }
            Text(text, style = MaterialTheme.typography.titleSmall, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
        }
    }
}

/** Watch now once it is out, then Open show and the bell. */
@Composable
private fun CalendarPanelActions(
    row: CalendarRow,
    performance: Boolean,
    onWatch: (CalendarRow) -> Unit,
    onOpen: (CalendarRow) -> Unit,
    onBell: (CalendarRow) -> Unit
) {
    val out = row.status == CalendarRowStatus.WATCHED || row.status == CalendarRowStatus.NOT_WATCHED
    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        if (out && row.kind != CalendarRowKind.FILM_CINEMA && row.kind != CalendarRowKind.SHOW) {
            CalendarPanelAction(stringResource(R.string.calendar_reminder_watch_now), Icons.Default.PlayArrow, performance) { onWatch(row) }
        }
        CalendarPanelAction(
            stringResource(if (row.isFilm) R.string.calendar_action_open_film else R.string.calendar_action_open_show),
            null,
            performance
        ) { onOpen(row) }
        if (row.status.hasBell) {
            val on = row.status == CalendarRowStatus.BELL_ON
            CalendarRoundButton(
                icon = if (on) Icons.Default.Notifications else Icons.Default.NotificationsNone,
                description = stringResource(if (on) R.string.calendar_cd_bell_on else R.string.calendar_cd_bell_off),
                performance = performance,
                onClick = { onBell(row) },
                tint = if (on) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextPrimary,
                size = 36.dp
            )
        }
    }
}

/** Down from the panel's buttons moves through these; the panel scrolls them into view. OK opens the show. */
@Composable
private fun CalendarNextRow(
    row: CalendarRow,
    details: CalendarRowDetails?,
    formats: CalendarFormats,
    today: LocalDate?,
    performance: Boolean,
    onOpen: (CalendarRow) -> Unit
) {
    val v2 = LocalV2Appearance.current != null
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    CalendarFocusCard(
        onClick = { onOpen(row) },
        shape = shape,
        performance = performance,
        focusedScale = 1.02f,
        modifier = Modifier.fillMaxWidth().padding(top = NuvioTheme.spacing.xs)
    ) { focused ->
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (v2 && focused) Modifier.nuvioGlass(GlassRole.CONTROL, focused = true, shape = shape) else Modifier)
            .background(if (!v2 && focused) NuvioTheme.colors.FocusBackground else Color.Transparent)
            .padding(NuvioTheme.spacing.xxs)
    ) {
        CalendarArtImage(
            url = details?.still(row.season, row.episode),
            alternate = row.backdrop ?: details?.backdrop,
            fallback = row.poster,
            modifier = Modifier.size(72.dp, 40.dp).clip(RoundedCornerShape(NuvioTheme.spacing.xs))
        )
        Spacer(Modifier.width(NuvioTheme.spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                text = calendarRowLine(row),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val line = listOfNotNull(calendarWhenLine(row, formats, today), calendarLabelText(row.label)).joinToString(" · ")
            if (line.isNotEmpty()) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    }
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
        PanelActionRow(
            label = stringResource(if (row.isFilm) R.string.calendar_action_open_film else R.string.calendar_action_open_show),
            onClick = onOpen,
            focusRequester = first
        )
        if (row.status.hasBell) {
            PanelActionRow(
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
        PanelActionRow(label = stringResource(R.string.calendar_action_hide), onClick = onHide)
    }
}

/** Where the list opens: the first row of Today, else the first of what comes next, else the first row. */
internal fun calendarOpeningRowKey(list: CalendarListModel): String? =
    list.firstRowKeyIn(CalendarSectionKind.TODAY) ?: list.startRowKey

@Composable
internal fun calendarRuntimeText(details: CalendarRowDetails): String? {
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
