package com.nuvio.tv.ui.screens.calendar

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.LocalDate
import java.time.YearMonth

@Composable
internal fun CalendarMonthView(
    viewModel: CalendarViewModel,
    uiState: CalendarUiState,
    formats: CalendarFormats,
    performance: Boolean,
    onOpen: (CalendarRow) -> Unit,
    onOptions: (CalendarRow) -> Unit
) {
    val today = uiState.today ?: return
    val monthState by viewModel.month.collectAsState()
    val month = monthState.month(today)
    val focusedDay = monthState.focusedDay(today)
    val gridRequester = remember { FocusRequester() }
    val dayListRequester = remember { FocusRequester() }
    var gridFocused by remember { mutableStateOf(false) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val grid = remember(month, formats.firstDayOfWeek) { calendarMonthGrid(month, formats.firstDayOfWeek) }
    val dayRows = uiState.days[focusedDay].orEmpty()

    LaunchedEffect(Unit) { gridRequester.requestFocusAfterFrames() }
    LaunchedEffect(focusedDay, dayRows.firstOrNull()) {
        val first = dayRows.firstOrNull()
        if (first == null) viewModel.clearFocus() else viewModel.onRowFocused(first, fromList = false)
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(0.62f).fillMaxHeight().padding(bottom = NuvioTheme.spacing.xxl)) {
            Text(
                text = formats.month(month),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(Modifier.height(NuvioTheme.spacing.sm))
            Row(Modifier.fillMaxWidth()) {
                grid.take(7).forEach { date ->
                    Text(
                        text = formats.weekday(date.dayOfWeek),
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioTheme.colors.TextTertiary,
                        modifier = Modifier.weight(1f).padding(start = NuvioTheme.spacing.sm),
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(NuvioTheme.spacing.xs))
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusRequester(gridRequester)
                    .onFocusChanged { gridFocused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        val key = when (native.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> if (rtl) CalendarMonthKey.RIGHT else CalendarMonthKey.LEFT
                            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> if (rtl) CalendarMonthKey.LEFT else CalendarMonthKey.RIGHT
                            AndroidKeyEvent.KEYCODE_DPAD_UP -> CalendarMonthKey.UP
                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> CalendarMonthKey.DOWN
                            else -> null
                        }
                        val select = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                            native.keyCode == AndroidKeyEvent.KEYCODE_ENTER || native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                        when {
                            select -> {
                                if (native.action == AndroidKeyEvent.ACTION_UP) viewModel.openDayInList(focusedDay)
                                true
                            }
                            key == null || native.action != AndroidKeyEvent.ACTION_DOWN -> false
                            else -> when (val move = calendarMonthMove(focusedDay, month, formats.firstDayOfWeek, key)) {
                                is CalendarMonthMove.To -> {
                                    viewModel.focusDay(move.date)
                                    true
                                }
                                is CalendarMonthMove.Leave -> if (move.key == CalendarMonthKey.RIGHT) {
                                    if (dayRows.isNotEmpty()) runCatching { dayListRequester.requestFocus() }
                                    true
                                } else false
                            }
                        }
                    }
                    .focusable()
            ) {
                grid.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        week.forEach { date ->
                            CalendarMonthCell(
                                date = date,
                                inMonth = YearMonth.from(date) == month,
                                today = date == today,
                                focused = gridFocused && date == focusedDay,
                                selected = date == focusedDay,
                                summary = calendarDaySummary(uiState.days[date]),
                                performance = performance,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(NuvioTheme.spacing.xl))
        CalendarDayPanel(
            date = focusedDay,
            today = today,
            rows = dayRows,
            formats = formats,
            performance = performance,
            firstRequester = dayListRequester,
            modifier = Modifier.weight(0.38f).fillMaxHeight().padding(bottom = NuvioTheme.spacing.xxl),
            onFocused = { viewModel.onRowFocused(it, fromList = false) },
            onOpen = onOpen,
            onBell = viewModel::toggleBell,
            onOptions = onOptions
        )
    }
}

@Composable
private fun CalendarMonthCell(
    date: LocalDate,
    inMonth: Boolean,
    today: Boolean,
    focused: Boolean,
    selected: Boolean,
    summary: CalendarDaySummary,
    performance: Boolean,
    modifier: Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    Box(modifier.padding(NuvioTheme.spacing.xxs)) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    when {
                        focused -> Modifier.clip(shape).background(NuvioTheme.colors.FocusBackground)
                            .border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape)
                        selected -> Modifier.clip(shape).background(NuvioTheme.colors.FocusBackground.copy(alpha = .5f))
                        inMonth -> Modifier.clip(shape).background(NuvioTheme.colors.BackgroundCard.copy(alpha = .35f))
                        else -> Modifier
                    }
                )
                .padding(NuvioTheme.spacing.xs)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
                val number = date.dayOfMonth.toString()
                if (today) {
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).background(NuvioTheme.colors.Secondary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(number, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.OnSecondary)
                    }
                } else {
                    Text(
                        text = number,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (inMonth) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextDisabled
                    )
                }
                if (!summary.isEmpty) {
                    if (performance) {
                        CalendarDayCountText(summary, inMonth)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                            summary.posters.forEach { CalendarPoster(it, 16.dp, 24.dp) }
                            if (summary.more > 0) {
                                Text(
                                    text = stringResource(R.string.calendar_month_more, summary.more),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NuvioTheme.colors.TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCountText(summary: CalendarDaySummary, inMonth: Boolean) {
    val color = if (inMonth) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextDisabled
    if (summary.episodes > 0) {
        Text(
            text = pluralStringResource(R.plurals.calendar_month_episodes, summary.episodes, summary.episodes),
            style = MaterialTheme.typography.labelSmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
    if (summary.films > 0) {
        Text(
            text = pluralStringResource(R.plurals.calendar_month_films, summary.films, summary.films),
            style = MaterialTheme.typography.labelSmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CalendarDayPanel(
    date: LocalDate,
    today: LocalDate,
    rows: List<CalendarRow>,
    formats: CalendarFormats,
    performance: Boolean,
    firstRequester: FocusRequester,
    modifier: Modifier,
    onFocused: (CalendarRow) -> Unit,
    onOpen: (CalendarRow) -> Unit,
    onBell: (CalendarRow) -> Unit,
    onOptions: (CalendarRow) -> Unit
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().padding(NuvioTheme.spacing.md)) {
            Text(
                text = formats.longDate(date),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (date == today) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = NuvioTheme.spacing.xs)
            )
            val summary = calendarDaySummary(rows)
            val counts = listOfNotNull(
                summary.episodes.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.calendar_month_episodes, it, it) },
                summary.films.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.calendar_month_films, it, it) }
            )
            Text(
                text = calendarJoinedNames(counts).ifEmpty { stringResource(R.string.calendar_month_day_empty) },
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(start = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.sm)
            )
            LazyColumn(
                contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                    CalendarRowItem(
                        row = row,
                        formats = formats,
                        performance = performance,
                        focusRequester = if (index == 0) firstRequester else null,
                        onFocused = onFocused,
                        onOpen = onOpen,
                        onBell = onBell,
                        onOptions = onOptions,
                        compact = true
                    )
                }
            }
        }
    }
    Box(modifier.clip(shape).background(NuvioTheme.colors.BackgroundCard)) { content() }
}
