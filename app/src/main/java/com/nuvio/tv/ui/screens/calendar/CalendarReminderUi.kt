package com.nuvio.tv.ui.screens.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.calendar.CalendarCornerMessage
import com.nuvio.tv.core.calendar.CalendarEntryKind
import com.nuvio.tv.core.calendar.CalendarReminder
import com.nuvio.tv.core.calendar.CalendarReminderCard
import com.nuvio.tv.core.calendar.CalendarReminderCenter
import com.nuvio.tv.core.calendar.CalendarTitleType
import com.nuvio.tv.core.calendar.CalendarWatchTarget
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.player.DialogButton
import com.nuvio.tv.ui.screens.player.LoadingOverlay
import com.nuvio.tv.ui.screens.settings.MemoryBudget
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

val LocalCalendarReminderCenter = staticCompositionLocalOf<CalendarReminderCenter?> { null }

/** Tells the reminders when Home's content is on screen and its page is in front. */
@Composable
fun CalendarHomeSignal(contentShown: Boolean) {
    val center = LocalCalendarReminderCenter.current ?: return
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val visible = contentShown && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(center, visible) { center.homeVisible(visible) }
    DisposableEffect(center) { onDispose { center.homeVisible(false) } }
}

/**
 * The reminder card and the corner message, drawn over every screen. Watch now looks up the episode with the loading
 * overlay up; moving to another page, Back or playback starting call the lookup off.
 */
@Composable
fun CalendarReminderHost(
    center: CalendarReminderCenter,
    covered: Boolean,
    playerVisible: Boolean,
    currentRoute: String?,
    resolveWatch: suspend (CalendarReminder) -> CalendarWatchTarget?,
    onWatchTarget: (CalendarWatchTarget) -> Unit,
    onOpenTitle: (CalendarReminder) -> Unit,
    onSeeAll: () -> Unit
) {
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(covered, windowFocused) { center.setBlocked(covered || !windowFocused) }
    LaunchedEffect(playerVisible) { center.setPlayerVisible(playerVisible) }

    val scope = rememberCoroutineScope()
    val playing by center.playbackActive.collectAsState()
    var finding by remember { mutableStateOf<CalendarReminder?>(null) }
    val lookup = remember { arrayOfNulls<Job>(1) }
    val lookupRoute = remember { arrayOfNulls<String>(1) }
    val stopLookup = {
        lookup[0]?.cancel()
        lookup[0] = null
        finding = null
    }
    LaunchedEffect(currentRoute, playerVisible, playing) {
        if (finding != null && (playerVisible || playing || currentRoute != lookupRoute[0])) stopLookup()
    }
    BackHandler(enabled = finding != null) { stopLookup() }

    val corner by center.corner.collectAsState()
    val card by center.card.collectAsState()
    CalendarCornerMessageView(corner)
    card?.let { current ->
        CalendarReminderDialog(
            card = current,
            onWatch = { reminder ->
                center.cardActionTaken()
                stopLookup()
                finding = reminder
                lookupRoute[0] = currentRoute
                lookup[0] = scope.launch {
                    val target = resolveWatch(reminder)
                    if (finding !== reminder) return@launch
                    finding = null
                    lookup[0] = null
                    target?.let(onWatchTarget)
                }
            },
            onOpenTitle = { center.cardActionTaken(); onOpenTitle(it) },
            onSeeAll = { center.cardActionTaken(); onSeeAll() },
            onLater = center::dismissCard
        )
    }
    finding?.let { reminder ->
        LoadingOverlay(
            visible = true,
            backdropUrl = null,
            logoUrl = null,
            title = reminder.title.name,
            message = stringResource(R.string.calendar_reminder_finding),
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun CalendarCornerMessageView(message: CalendarCornerMessage?) {
    val performance = MemoryBudget.isLowRamTier
    val last = remember { arrayOfNulls<CalendarCornerMessage>(1) }
    if (message != null) last[0] = message
    Box(Modifier.fillMaxSize().padding(top = NuvioTheme.spacing.xl, end = NuvioTheme.spacing.xxxl), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(
            visible = message != null,
            enter = if (performance) EnterTransition.None else fadeIn(tween(220)),
            exit = if (performance) ExitTransition.None else fadeOut(tween(220))
        ) {
            last[0]?.let { CalendarCornerCard(it) }
        }
    }
}

@Composable
private fun CalendarCornerCard(message: CalendarCornerMessage) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val first = message.items.first()
    Row(
        modifier = Modifier
            .widthIn(max = 420.dp)
            .clip(shape)
            .background(NuvioTheme.colors.BackgroundElevated)
            .padding(NuvioTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CalendarPoster(url = first.title.poster, width = 40.dp, height = 60.dp)
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
            Text(
                text = calendarReminderTitle(message.items, message.count),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.Secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = calendarJoinedNames(message.names).ifBlank { stringResource(R.string.calendar_unknown_title) },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (message.count == 1) {
                Text(
                    text = calendarReminderLine(first),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = stringResource(R.string.calendar_corner_press_menu),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CalendarReminderDialog(
    card: CalendarReminderCard,
    onWatch: (CalendarReminder) -> Unit,
    onOpenTitle: (CalendarReminder) -> Unit,
    onSeeAll: () -> Unit,
    onLater: () -> Unit
) {
    val formats = rememberCalendarFormats()
    val first = remember { FocusRequester() }
    LaunchedEffect(card) { first.requestFocusAfterFrames() }
    NuvioDialog(
        onDismiss = onLater,
        title = calendarReminderTitle(card.items, card.total),
        width = if (card.single) 640.dp else 700.dp
    ) {
        if (card.single) {
            val reminder = card.items.first()
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)) {
                CalendarPoster(url = reminder.title.poster, width = 96.dp, height = 144.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                    Text(
                        text = reminder.title.name ?: stringResource(R.string.calendar_unknown_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = calendarReminderLine(reminder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = calendarAiredText(reminder, formats, card.zone, card.now),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(NuvioTheme.spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                        DialogButton(
                            text = stringResource(R.string.calendar_reminder_watch_now),
                            onClick = { onWatch(reminder) },
                            isPrimary = true,
                            modifier = Modifier.weight(1f, fill = false).focusRequester(first)
                        )
                        DialogButton(
                            text = stringResource(
                                if (reminder.title.type == CalendarTitleType.MOVIE) R.string.calendar_action_open_film
                                else R.string.calendar_action_open_show
                            ),
                            onClick = { onOpenTitle(reminder) },
                            isPrimary = false,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        DialogButton(
                            text = stringResource(R.string.calendar_reminder_later),
                            onClick = onLater,
                            isPrimary = false,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                card.items.forEachIndexed { index, reminder ->
                    CalendarReminderRow(
                        reminder = reminder,
                        aired = calendarAiredText(reminder, formats, card.zone, card.now),
                        focusRequester = if (index == 0) first else null,
                        onWatch = { onWatch(reminder) },
                        onOpenTitle = { onOpenTitle(reminder) }
                    )
                }
                if (card.more > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.calendar_reminder_more, card.more, card.more),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(start = NuvioTheme.spacing.sm, top = NuvioTheme.spacing.xxs)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                DialogButton(
                    text = stringResource(R.string.calendar_reminder_see_all),
                    onClick = onSeeAll,
                    isPrimary = false
                )
                DialogButton(
                    text = stringResource(R.string.calendar_reminder_later),
                    onClick = onLater,
                    isPrimary = false
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarReminderRow(
    reminder: CalendarReminder,
    aired: String,
    focusRequester: FocusRequester?,
    onWatch: () -> Unit,
    onOpenTitle: () -> Unit
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Card(
            onClick = onWatch,
            modifier = Modifier
                .weight(1f)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            shape = CardDefaults.shape(shape),
            colors = CardDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = NuvioTheme.colors.FocusBackground
            ),
            border = CardDefaults.border(
                border = Border.None,
                focusedBorder = Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
            ),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Row(
                modifier = Modifier.padding(NuvioTheme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CalendarPoster(url = reminder.title.poster, width = 40.dp, height = 60.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = reminder.title.name ?: stringResource(R.string.calendar_unknown_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.calendar_line_join, calendarReminderLine(reminder), aired),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        DialogButton(
            text = stringResource(
                if (reminder.title.type == CalendarTitleType.MOVIE) R.string.calendar_reminder_row_film
                else R.string.calendar_reminder_row_show
            ),
            onClick = onOpenTitle,
            isPrimary = false
        )
    }
}

@Composable
private fun calendarReminderTitle(items: List<CalendarReminder>, total: Int): String =
    if (total == 1) stringResource(calendarReminderSingleTitleRes(items))
    else pluralStringResource(calendarReminderTitleRes(items), total, total)

internal fun calendarReminderSingleTitleRes(items: List<CalendarReminder>): Int = when {
    items.all { it.entry.kind == CalendarEntryKind.EPISODE } -> R.string.calendar_reminder_title_one
    items.none { it.entry.kind == CalendarEntryKind.EPISODE } -> R.string.calendar_reminder_title_one_film
    else -> R.string.calendar_reminder_title_one_release
}

/** "New episode", "New film", or "New release" when episodes and films come together. */
internal fun calendarReminderTitleRes(items: List<CalendarReminder>): Int = when {
    items.all { it.entry.kind == CalendarEntryKind.EPISODE } -> R.plurals.calendar_reminder_title
    items.none { it.entry.kind == CalendarEntryKind.EPISODE } -> R.plurals.calendar_reminder_title_films
    else -> R.plurals.calendar_reminder_title_releases
}

/** "S2 E4 · Episode name", or the kind of release for a film. */
@Composable
private fun calendarReminderLine(reminder: CalendarReminder): String {
    val entry = reminder.entry
    return when (entry.kind) {
        CalendarEntryKind.MOVIE_THEATRICAL -> stringResource(R.string.calendar_film_cinema)
        CalendarEntryKind.MOVIE_DIGITAL -> stringResource(R.string.calendar_film_digital)
        CalendarEntryKind.EPISODE -> {
            val code = if (entry.season != null && entry.episode != null) {
                stringResource(R.string.calendar_episode_code, entry.season, entry.episode)
            } else null
            val name = entry.shownTitle
            when {
                code != null && name != null -> stringResource(R.string.calendar_line_join, code, name)
                else -> code ?: name.orEmpty()
            }
        }
    }
}

/** "Aired 11:00 am today", "Aired 8:00 pm Thu 1 Oct", "Out today" and the like, in the Calendar's zone. */
@Composable
private fun calendarAiredText(reminder: CalendarReminder, formats: CalendarFormats, zone: ZoneId, now: Instant): String {
    val entry = reminder.entry
    val today = now.atZone(zone).toLocalDate()
    val date = entry.localDate(zone)
    val time = entry.airInstantMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
    if (entry.kind != CalendarEntryKind.EPISODE) {
        return when (date) {
            null, today -> stringResource(R.string.calendar_reminder_out_today)
            else -> stringResource(R.string.calendar_out_on, formats.shortDate(date))
        }
    }
    return when {
        time != null && date == today -> stringResource(R.string.calendar_reminder_aired_today_at, formats.time(time))
        time != null && date != null -> stringResource(R.string.calendar_reminder_aired_at, formats.time(time), formats.shortDate(date))
        date == null || date == today -> stringResource(R.string.calendar_reminder_aired_today)
        else -> stringResource(R.string.calendar_aired_on, formats.shortDate(date))
    }
}

/** A list of names in the app's language, such as "The Harbour, Low Tide and Signal". */
@Composable
internal fun calendarJoinedNames(names: List<String>): String {
    val locale = LocalConfiguration.current.locales[0] ?: java.util.Locale.getDefault()
    val separator = stringResource(R.string.calendar_list_separator)
    val lastPair = stringResource(R.string.calendar_list_last_pair)
    return remember(names, locale, separator, lastPair) {
        calendarNameList(names, locale) { calendarJoinPlain(it, separator, lastPair) }
    }
}
