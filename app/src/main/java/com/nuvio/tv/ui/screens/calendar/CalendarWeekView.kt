@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.calendar

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import java.time.LocalDate
import kotlinx.coroutines.launch

private const val WEEK_CARDS_ACROSS = 4
private const val KEY_REPEAT_THROTTLE_MS = 80L
private val FOCUS_ROOM = 8.dp

/** Leaves scrolling to the Week list itself, which puts the focused day right under the hero. */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
private object CalendarNoAutoScroll : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = spring()
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** Scrolls a day's row only as far as it takes to show the focused card. */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
private object CalendarMinimalScroll : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = spring()
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            offset >= 0f && end <= containerSize -> 0f
            offset < 0f || size > containerSize -> offset
            else -> end - containerSize
        }
    }
}

/** A focusable surface that looks like the rest of the app in both styles. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CalendarFocusCard(
    onClick: () -> Unit,
    shape: Shape,
    performance: Boolean,
    modifier: Modifier = Modifier,
    focusedScale: Float = 1.04f,
    onFocus: (Boolean) -> Unit = {},
    content: @Composable (focused: Boolean) -> Unit
) {
    val v2 = LocalV2Appearance.current != null
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                onFocus(it.isFocused)
            }
            .nuvioV2Focus(focused, shape, stationary = performance),
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = if (v2) Border.None else Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        ),
        scale = CardDefaults.scale(focusedScale = if (v2 || performance) 1f else focusedScale)
    ) { content(focused) }
}

@Composable
internal fun CalendarWeekView(
    viewModel: CalendarViewModel,
    uiState: CalendarUiState,
    focus: CalendarFocus?,
    formats: CalendarFormats,
    performance: Boolean,
    onOpen: (CalendarRow) -> Unit,
    onOptions: (CalendarRow) -> Unit
) {
    val today = uiState.today ?: return
    val chosenStart by viewModel.weekStart.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val weekStart = chosenStart ?: calendarWeekStart(today, formats.firstDayOfWeek)
    val week = remember(uiState.days, weekStart, filter) { buildCalendarWeek(uiState.days, weekStart, filter) }
    val shelves = remember(week) { week.filterNot { it.isEmpty } }
    val shelfRequesters = remember(weekStart, filter, shelves.map { it.date }) { shelves.associate { it.date to FocusRequester() } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val stripRequester = remember { FocusRequester() }
    var placed by remember { mutableStateOf(false) }
    val lastKeyRepeat = remember { longArrayOf(0L) }

    val openingKey = remember(week) { calendarWeekOpeningKey(week) }
    val openingCard = remember(week, openingKey) { week.flatMap { it.cards }.firstOrNull { it.key == openingKey } }
    val focusInWeek = focus?.row?.let { row -> week.any { day -> day.cards.any { card -> card.rows.any { it.key == row.key } } } } == true
    val heroRow = if (focusInWeek) focus?.row else openingCard?.row
    val heroDetails = focus?.details?.takeIf { focus.row.key == heroRow?.key }

    LaunchedEffect(weekStart, filter, openingKey) {
        if (!focusInWeek) openingCard?.let { viewModel.onRowFocused(it.row, fromList = false) }
    }

    fun jumpTo(date: LocalDate, focusCard: Boolean) {
        val index = shelves.indexOfFirst { it.date >= date }.takeIf { it >= 0 } ?: shelves.lastIndex
        if (index < 0) return
        scope.launch {
            listState.scrollToItem(index)
            if (focusCard) shelfRequesters[shelves[index].date]?.requestFocusAfterFrames(1)
        }
    }

    // Where focus was on the shelves, so a card that goes away (hidden) hands focus to its day or the next one.
    val lastCard = remember { arrayOfNulls<Pair<String, LocalDate>>(1) }

    // Up and Down move a whole day and always land on its first card, wherever focus was along the row.
    val focusedShelf = remember { intArrayOf(-1) }
    fun moveDay(by: Int): Boolean {
        val from = focusedShelf[0].takeIf { it in shelves.indices } ?: return false
        val to = from + by
        if (to < 0) return false
        if (to > shelves.lastIndex) return true
        jumpTo(shelves[to].date, focusCard = true)
        return true
    }

    LaunchedEffect(Unit) {
        val asked = viewModel.pendingWeekDay.value
        viewModel.consumePendingWeekDay()
        if (shelves.isEmpty()) {
            stripRequester.requestFocusAfterFrames()
        } else {
            jumpTo(asked?.takeIf { day -> shelves.any { it.date == day } } ?: shelves.first().date, focusCard = true)
        }
        placed = true
    }
    LaunchedEffect(weekStart, filter) {
        if (!placed) return@LaunchedEffect
        lastCard[0] = null
        if (shelves.isNotEmpty()) listState.scrollToItem(0)
    }
    LaunchedEffect(week) {
        val (key, date) = lastCard[0] ?: return@LaunchedEffect
        if (week.any { day -> day.cards.any { it.key == key } }) return@LaunchedEffect
        lastCard[0] = null
        if (shelves.isEmpty()) stripRequester.requestFocusAfterFrames() else jumpTo(date, focusCard = true)
    }

    Column(Modifier.fillMaxSize()) {
        CalendarWeekStrip(
            modifier = Modifier.onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.keyCode != AndroidKeyEvent.KEYCODE_DPAD_DOWN || shelves.isEmpty()) return@onPreviewKeyEvent false
                if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                    jumpTo(shelves[listState.firstVisibleItemIndex.coerceIn(shelves.indices)].date, focusCard = true)
                }
                true
            },
            week = week,
            today = today,
            formats = formats,
            filter = filter,
            performance = performance,
            stripRequester = stripRequester,
            onDay = { jumpTo(it, focusCard = true) },
            onShift = { viewModel.shiftWeek(weekStart, it) },
            onFilter = viewModel::setFilter
        )
        Spacer(Modifier.height(NuvioTheme.spacing.md))
        if (heroRow != null) {
            CalendarWeekHero(row = heroRow, details = heroDetails, formats = formats, today = today)
            Spacer(Modifier.height(NuvioTheme.spacing.md))
        }
        if (shelves.isEmpty()) {
            Text(
                text = stringResource(R.string.calendar_week_empty),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(top = NuvioTheme.spacing.xl)
            )
            return@Column
        }
        // The list scrolls itself so the focused day sits right under the hero; the default TV pivot would leave
        // part of the day before it showing.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides CalendarNoAutoScroll) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .requiredWidth(maxWidth + FOCUS_ROOM * 2)
                .fillMaxHeight()
                .clipToBounds()
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount > 0) {
                        val now = System.currentTimeMillis()
                        if (now - lastKeyRepeat[0] < KEY_REPEAT_THROTTLE_MS) return@onPreviewKeyEvent true
                        lastKeyRepeat[0] = now
                    }
                    when {
                        native.action != AndroidKeyEvent.ACTION_DOWN -> false
                        native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN -> moveDay(1)
                        native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP -> moveDay(-1)
                        else -> false
                    }
                },
            contentPadding = PaddingValues(start = FOCUS_ROOM, end = FOCUS_ROOM, bottom = NuvioTheme.spacing.xxxl * 4),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            itemsIndexed(shelves, key = { _, day -> day.date.toString() }) { index, day ->
                CalendarDayShelf(
                    day = day,
                    today = today,
                    formats = formats,
                    performance = performance,
                    firstRequester = shelfRequesters[day.date],
                    onFocused = { row ->
                        focusedShelf[0] = index
                        lastCard[0] = (week.flatMap { it.cards }.firstOrNull { card -> card.rows.any { it.key == row.key } }?.key ?: row.key) to day.date
                        viewModel.onRowFocused(row, fromList = false)
                        if (placed && (listState.firstVisibleItemIndex != index || listState.firstVisibleItemScrollOffset != 0)) {
                            scope.launch { if (performance) listState.scrollToItem(index) else listState.animateScrollToItem(index) }
                        }
                    },
                    onOpen = onOpen,
                    onOptions = onOptions
                )
            }
        }
        }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarWeekStrip(
    modifier: Modifier,
    week: List<CalendarWeekDay>,
    today: LocalDate,
    formats: CalendarFormats,
    filter: CalendarFilter,
    performance: Boolean,
    stripRequester: FocusRequester,
    onDay: (LocalDate) -> Unit,
    onShift: (Long) -> Unit,
    onFilter: (CalendarFilter) -> Unit
) {
    val v2 = LocalV2Appearance.current != null
    val chipShape = RoundedCornerShape(if (v2) NuvioTheme.radii.lg else NuvioTheme.radii.md)
    val pill = RoundedCornerShape(NuvioTheme.radii.full)
    Row(
        modifier = modifier.fillMaxWidth().focusRestorer(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        CalendarRoundButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            description = stringResource(R.string.calendar_cd_week_previous),
            performance = performance,
            onClick = { onShift(-1) }
        )
        week.forEach { day ->
            val isToday = day.date == today
            CalendarFocusCard(
                onClick = { onDay(day.date) },
                shape = chipShape,
                performance = performance,
                modifier = Modifier
                    .width(if (v2) 58.dp else 54.dp)
                    .then(if (isToday || (week.none { it.date == today } && day == week.first())) Modifier.focusRequester(stripRequester) else Modifier)
            ) { focused ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(if (v2 && focused) Modifier.nuvioGlass(GlassRole.CONTROL, focused = true, shape = chipShape) else Modifier)
                        .clip(chipShape)
                        .background(
                            when {
                                !v2 && focused -> NuvioTheme.colors.FocusBackground
                                isToday -> NuvioTheme.colors.Secondary.copy(alpha = 0.16f)
                                else -> NuvioTheme.colors.BackgroundCard.copy(alpha = if (v2) 0.45f else 0.8f)
                            }
                        )
                        .padding(vertical = NuvioTheme.spacing.xs),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isToday) stringResource(R.string.calendar_section_today) else formats.weekday(day.date.dayOfWeek),
                        style = CalendarText.stripDay,
                        color = if (isToday) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = day.date.dayOfMonth.toString(),
                        style = CalendarText.stripNumber,
                        color = NuvioTheme.colors.TextPrimary
                    )
                    Row(Modifier.height(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(day.dots) {
                            Box(Modifier.size(4.dp).clip(CircleShape).background(NuvioTheme.colors.TextPrimary.copy(alpha = 0.8f)))
                        }
                    }
                }
            }
        }
        CalendarRoundButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            description = stringResource(R.string.calendar_cd_week_next),
            performance = performance,
            onClick = { onShift(1) }
        )
        Spacer(Modifier.width(NuvioTheme.spacing.lg))
        CalendarFilter.entries.forEach { option ->
            val selected = option == filter
            CalendarFocusCard(onClick = { onFilter(option) }, shape = pill, performance = performance, focusedScale = 1.08f) { focused ->
                Text(
                    text = stringResource(
                        when (option) {
                            CalendarFilter.ALL -> R.string.calendar_filter_all
                            CalendarFilter.SHOWS -> R.string.calendar_filter_shows
                            CalendarFilter.FILMS -> R.string.calendar_filter_films
                        }
                    ),
                    style = CalendarText.pill,
                    color = when {
                        !v2 && selected && !focused -> NuvioTheme.colors.OnSecondary
                        else -> NuvioTheme.colors.TextPrimary
                    },
                    modifier = Modifier
                        .then(if (v2 && focused) Modifier.nuvioGlass(GlassRole.CONTROL, focused = true, shape = pill) else Modifier)
                        .clip(pill)
                        .background(
                            when {
                                !v2 && focused -> NuvioTheme.colors.FocusBackground
                                selected -> if (v2) NuvioTheme.colors.Secondary.copy(alpha = 0.16f) else NuvioTheme.colors.Secondary
                                else -> NuvioTheme.colors.BackgroundCard.copy(alpha = if (v2) 0.45f else 0.8f)
                            }
                        )
                        .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.xs)
                )
            }
        }
    }
}

@Composable
internal fun CalendarRoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    performance: Boolean,
    onClick: () -> Unit,
    tint: Color = NuvioTheme.colors.TextPrimary,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier
) {
    val v2 = LocalV2Appearance.current != null
    CalendarFocusCard(onClick = onClick, shape = CircleShape, performance = performance, focusedScale = 1.1f, modifier = modifier) { focused ->
        Box(
            Modifier
                .size(size)
                .then(if (v2 && focused) Modifier.nuvioGlass(GlassRole.CONTROL, focused = true, shape = CircleShape) else Modifier)
                .clip(CircleShape)
                .background(
                    if (!v2 && focused) NuvioTheme.colors.FocusBackground
                    else NuvioTheme.colors.BackgroundCard.copy(alpha = if (v2) 0.45f else 0.8f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size * 0.6f))
        }
    }
}

/** The focused title's logo, episode, when, where, rating and summary, the way Home shows its hero. */
@Composable
private fun CalendarWeekHero(row: CalendarRow, details: CalendarRowDetails?, formats: CalendarFormats, today: LocalDate) {
    Column(Modifier.width(560.dp)) {
        CalendarLogo(
            logo = row.logo ?: details?.logo,
            name = row.name,
            height = 48.dp,
            maxWidth = 300.dp,
            textStyle = MaterialTheme.typography.headlineMedium,
            alignment = Alignment.CenterStart
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = calendarRowLine(row),
            style = CalendarText.heroEpisode,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        CalendarMetaLine(row, details, formats, today, style = CalendarText.heroMeta)
        Spacer(Modifier.height(6.dp))
        Text(
            text = details?.synopsis.orEmpty(),
            style = CalendarText.heroSummary,
            color = NuvioTheme.colors.TextSecondary,
            minLines = 3,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** "Today · 15:00 · Paramount+ · 51m · ★ 8.4 · Drama", the when part in the accent colour. */
@Composable
internal fun CalendarMetaLine(
    row: CalendarRow,
    details: CalendarRowDetails?,
    formats: CalendarFormats,
    today: LocalDate?,
    withWhen: Boolean = true,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge
) {
    val whenText = if (withWhen) calendarWhenLine(row, formats, today) else null
    val rest = listOfNotNull(
        row.network,
        details?.let { calendarRuntimeText(it) },
        calendarRatingGenre(row.rating ?: details?.rating, row.genre ?: details?.genre)
    )
    if (whenText == null && rest.isEmpty()) return
    val accent = NuvioTheme.colors.Secondary
    val text = buildAnnotatedString {
        whenText?.let { withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(it) } }
        rest.forEachIndexed { index, part ->
            if (index > 0 || whenText != null) append(" · ")
            append(part)
        }
    }
    Text(
        text = text,
        style = style,
        color = NuvioTheme.colors.TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun CalendarDayShelf(
    day: CalendarWeekDay,
    today: LocalDate,
    formats: CalendarFormats,
    performance: Boolean,
    firstRequester: FocusRequester?,
    onFocused: (CalendarRow) -> Unit,
    onOpen: (CalendarRow) -> Unit,
    onOptions: (CalendarRow) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        val (main, hint) = when (day.date) {
            today -> stringResource(R.string.calendar_section_today) to formats.shortDate(day.date)
            today.plusDays(1) -> stringResource(R.string.calendar_section_tomorrow) to formats.shortDate(day.date)
            else -> formats.longDate(day.date) to null
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm), modifier = Modifier.padding(bottom = 6.dp)) {
            Text(
                text = main,
                modifier = Modifier.alignByBaseline(),
                style = CalendarText.dayHeading,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1
            )
            hint?.let {
                Text(
                    text = it,
                    modifier = Modifier.alignByBaseline(),
                    style = CalendarText.dayHint,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 14.dp
            val cardWidth = (maxWidth - gap * (WEEK_CARDS_ACROSS - 1)) / WEEK_CARDS_ACROSS
            // The row reaches past the page margin by the focus room, so the focused card's ring and lift are not cut.
            CompositionLocalProvider(LocalBringIntoViewSpec provides CalendarMinimalScroll) {
            LazyRow(
                modifier = Modifier.requiredWidth(maxWidth + FOCUS_ROOM * 2).focusRestorer(),
                horizontalArrangement = Arrangement.spacedBy(gap),
                contentPadding = PaddingValues(horizontal = FOCUS_ROOM, vertical = FOCUS_ROOM)
            ) {
                itemsIndexed(day.cards, key = { _, card -> card.key }) { index, card ->
                    CalendarShelfCard(
                        card = card,
                        formats = formats,
                        width = cardWidth,
                        performance = performance,
                        focusRequester = if (index == 0) firstRequester else null,
                        onFocused = onFocused,
                        onOpen = onOpen,
                        onOptions = onOptions
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun CalendarShelfCard(
    card: CalendarCard,
    formats: CalendarFormats,
    width: Dp,
    performance: Boolean,
    focusRequester: FocusRequester?,
    onFocused: (CalendarRow) -> Unit,
    onOpen: (CalendarRow) -> Unit,
    onOptions: (CalendarRow) -> Unit
) {
    val row = card.row
    val v2 = LocalV2Appearance.current != null
    val shape = RoundedCornerShape(if (v2) NuvioTheme.radii.lg else NuvioTheme.radii.md)
    val press = remember { CalendarPress() }
    val code = calendarCardCode(card)
    Column(Modifier.width(width)) {
        CalendarFocusCard(
            onClick = { press.click { onOpen(row) } },
            shape = shape,
            performance = performance,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .calendarOptionsKeys(press) { onOptions(row) },
            onFocus = { if (it) onFocused(row) }
        ) {
            CalendarArtTile(
                row = row,
                code = code,
                time = row.time,
                showLabel = true,
                logoHeight = 28.dp,
                modifier = Modifier.fillMaxSize().clip(shape)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = calendarCardTitle(card),
            style = CalendarText.cardTitle,
            color = if (row.dimmed) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val noTime = calendarTimeText(row, formats) ?: stringResource(calendarNoTimeLabel(row))
        listOfNotNull(noTime.takeIf { row.time == null && !row.isFilm }, row.network)
            .joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
            Spacer(Modifier.height(3.dp))
            Text(
                text = it,
                style = CalendarText.cardSub,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A title's picture with its time, label, logo and episode code laid over it. */
@Composable
internal fun CalendarArtTile(
    row: CalendarRow,
    code: String?,
    time: java.time.LocalTime?,
    showLabel: Boolean,
    logoHeight: Dp,
    modifier: Modifier = Modifier,
    picture: String? = null,
    formats: CalendarFormats = rememberCalendarFormats()
) {
    Box(modifier) {
        CalendarArtImage(url = picture ?: row.backdrop, fallback = row.poster, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(calendarArtScrim()))
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            time?.let { CalendarBadge(formats.time(it)) } ?: Spacer(Modifier.width(1.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                if (showLabel) calendarLabelText(row.label)?.let { CalendarBadge(it, accent = true) }
                when (row.status) {
                    CalendarRowStatus.WATCHED -> CalendarStatusDot(Icons.Default.Check, stringResource(R.string.calendar_cd_watched))
                    CalendarRowStatus.BELL_ON -> CalendarStatusDot(Icons.Default.Notifications, stringResource(R.string.calendar_cd_bell_on))
                    else -> Unit
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            CalendarLogo(
                logo = row.logo,
                name = row.name,
                height = logoHeight,
                maxWidth = 200.dp,
                textStyle = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.weight(0.01f))
            code?.let { CalendarBadge(it) }
        }
    }
}

@Composable
private fun CalendarStatusDot(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String) {
    Box(
        Modifier.size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.62f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = NuvioTheme.colors.Secondary, modifier = Modifier.size(16.dp))
    }
}
