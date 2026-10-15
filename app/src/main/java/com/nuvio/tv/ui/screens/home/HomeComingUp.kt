package com.nuvio.tv.ui.screens.home

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.calendar.CalendarClock
import com.nuvio.tv.core.calendar.CalendarComingUpItem
import com.nuvio.tv.core.calendar.CalendarComingUpWhen
import com.nuvio.tv.core.calendar.CalendarEntryKind
import com.nuvio.tv.core.calendar.CalendarRefresher
import com.nuvio.tv.core.calendar.CalendarSettingsDataStore
import com.nuvio.tv.core.calendar.CalendarTitleType
import com.nuvio.tv.core.calendar.CalendarZoneProvider
import com.nuvio.tv.core.calendar.buildCalendarComingUp
import com.nuvio.tv.core.calendar.calendarContentIds
import com.nuvio.tv.core.calendar.calendarContinueWatchingKeys
import com.nuvio.tv.core.calendar.calendarEntryInKeys
import com.nuvio.tv.core.util.withAppLocale
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.ui.screens.calendar.CalendarFormats
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal const val MODERN_COMING_UP_ROW_KEY = "calendar_coming_up"

/** The words on the Coming up cards, so the labels can be checked without Android resources. */
data class CalendarComingUpText(
    val newLabel: String,
    val today: String,
    val todayAt: String,
    val tonightAt: String,
    val tomorrow: String,
    val cinema: String,
    val digital: String
) {
    companion object {
        fun from(context: Context) = CalendarComingUpText(
            newLabel = context.getString(R.string.calendar_coming_up_new),
            today = context.getString(R.string.calendar_section_today),
            todayAt = context.getString(R.string.calendar_coming_up_today_at),
            tonightAt = context.getString(R.string.calendar_coming_up_tonight_at),
            tomorrow = context.getString(R.string.calendar_section_tomorrow),
            cinema = context.getString(R.string.calendar_film_cinema),
            digital = context.getString(R.string.calendar_film_digital)
        )
    }
}

fun calendarComingUpLabel(item: CalendarComingUpItem, text: CalendarComingUpText, formats: CalendarFormats): String =
    when (item.whenKind) {
        CalendarComingUpWhen.NEW -> text.newLabel
        CalendarComingUpWhen.TONIGHT -> item.time?.let { text.tonightAt.format(formats.time(it)) } ?: text.today
        CalendarComingUpWhen.TODAY -> item.time?.let { text.todayAt.format(formats.time(it)) } ?: text.today
        CalendarComingUpWhen.TOMORROW -> text.tomorrow
        CalendarComingUpWhen.WEEKDAY -> formats.weekday(item.date.dayOfWeek)
        CalendarComingUpWhen.DATE -> formats.shortDate(item.date)
    }

/** A Coming up card in the Continue Watching card's shape; null for a title the app cannot open. */
fun calendarComingUpCard(
    item: CalendarComingUpItem,
    text: CalendarComingUpText,
    formats: CalendarFormats,
    art: (type: String, ids: List<String>) -> Pair<String?, String?> = { _, _ -> null to null }
): ContinueWatchingItem.NextUp? {
    val title = item.title
    val ids = calendarContentIds(title)
    val contentId = ids.firstOrNull() ?: return null
    val film = title.type == CalendarTitleType.MOVIE
    val type = if (film) "movie" else "series"
    val entry = item.entry
    val (backdrop, logo) = art(type, ids)
    return ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = contentId,
            contentType = type,
            name = title.name.orEmpty(),
            poster = title.poster,
            backdrop = backdrop,
            logo = logo,
            videoId = "",
            season = if (film) 0 else entry.season ?: 0,
            episode = if (film) 0 else entry.episode ?: 0,
            episodeTitle = when (entry.kind) {
                CalendarEntryKind.EPISODE -> entry.shownTitle
                CalendarEntryKind.MOVIE_THEATRICAL -> text.cinema
                CalendarEntryKind.MOVIE_DIGITAL -> text.digital
            },
            thumbnail = null,
            hasAired = item.whenKind == CalendarComingUpWhen.NEW,
            lastWatched = 0L,
            sortTimestamp = entry.sortInstant()?.toEpochMilli() ?: 0L,
            releaseTimestamp = entry.airInstantMs,
            badgeLabel = calendarComingUpLabel(item, text, formats)
        )
    )
}

/** Films and Coming up episodes without numbers show no "S1 E1". */
val NextUpInfo.showsEpisodeCode: Boolean
    get() = !contentType.equals("movie", ignoreCase = true) && !(badgeLabel != null && episode <= 0)

val ContinueWatchingItem.isComingUp: Boolean
    get() = this is ContinueWatchingItem.NextUp && info.badgeLabel != null

/** Builds the row from the Calendar's saved data only; it never asks the network for anything. */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class CalendarComingUpSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsStore: CalendarSettingsDataStore,
    private val zoneProvider: CalendarZoneProvider,
    private val refresher: dagger.Lazy<CalendarRefresher>,
    private val metaRepository: MetaRepository,
    @param:CalendarClock private val clock: Clock
) {
    private var localized: Pair<String, Context>? = null

    /**
     * [continueWatching] is what the Continue Watching rows show and [active] is true while Home is in front.
     * Nothing shows until the Calendar knows what has been watched.
     */
    fun items(
        continueWatching: Flow<List<ContinueWatchingItem>>,
        active: Flow<Boolean>
    ): Flow<List<ContinueWatchingItem>> =
        settingsStore.settings
            .map { it?.comingUpRow == true }
            .distinctUntilChanged()
            .flatMapLatest { on ->
                if (!on) return@flatMapLatest flowOf(emptyList<ContinueWatchingItem>())
                val calendar = refresher.get().also { it.start() }
                val shownKeys = continueWatching.map { items ->
                    calendarContinueWatchingKeys(
                        items.mapNotNull { (it as? ContinueWatchingItem.NextUp)?.info?.let { info -> Triple(info.contentId, info.season, info.episode) } }
                    )
                }.distinctUntilChanged()
                val minutes = active.distinctUntilChanged().flatMapLatest { if (it) minutes() else emptyFlow() }
                combine(calendar.state, zoneProvider.zones, shownKeys, minutes) { state, zone, shown, now ->
                    if (!state.loaded || state.watched == null) return@combine emptyList()
                    val localContext = localizedContext()
                    val locale = localContext.resources.configuration.locales[0] ?: java.util.Locale.getDefault()
                    val formats = CalendarFormats.forDevice(localContext, locale)
                    val text = CalendarComingUpText.from(localContext)
                    buildCalendarComingUp(state.titles, state.watched, now, zone, { title, entry ->
                        calendarEntryInKeys(shown, title, entry)
                    }).mapNotNull { calendarComingUpCard(it, text, formats, ::cachedArt) }
                }.onStart { if (calendar.state.value.watched == null) calendar.reloadWatched() }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    /** Something was watched: a "New" card for it should go. */
    suspend fun reloadWatched() {
        if (settingsStore.settings.value?.comingUpRow == true) refresher.get().reloadWatched()
    }

    private fun minutes(): Flow<Instant> = flow {
        while (true) {
            emit(clock.instant())
            delay(60_000L)
        }
    }

    private fun localizedContext(): Context {
        val tag = com.nuvio.tv.LocaleCache.localeTag
        localized?.takeIf { it.first == tag }?.let { return it.second }
        return context.withAppLocale().also { localized = tag to it }
    }

    private fun cachedArt(type: String, ids: List<String>): Pair<String?, String?> {
        val meta = ids.firstNotNullOfOrNull { metaRepository.getCachedMeta(type, it) } ?: return null to null
        return meta.background to meta.logo
    }
}

/** Starts once Continue Watching has had its first say, so the row never holds up Home. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
internal fun HomeViewModel.observeComingUpRow() {
    val source = calendarComingUp ?: return
    viewModelScope.launch {
        withTimeoutOrNull(COMING_UP_WAIT_MS) { _initialCwResolved.first { it } }
        delay(COMING_UP_START_DELAY_MS)
        val shown = uiState.map { state ->
            if (state.continueWatchingEnabled) state.continueWatchingItems + state.upcomingItems else emptyList()
        }.distinctUntilChanged()
        val watchProgress = merge(
            watchProgressRepository.allProgress.map { },
            watchProgressRepository.watchedItems.map { }
        ).debounce(WATCH_PROGRESS_SETTLE_MS)
        launch { watchProgress.collect { source.reloadWatched() } }
        source.items(shown, comingUpActive)
            .collect { items ->
                _uiState.update { state -> if (state.comingUpItems == items) state else state.copy(comingUpItems = items) }
            }
    }
}

private const val COMING_UP_WAIT_MS = 10_000L
private const val WATCH_PROGRESS_SETTLE_MS = 2_000L
private const val COMING_UP_START_DELAY_MS = 1_500L
