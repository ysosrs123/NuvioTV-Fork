package com.nuvio.tv.ui.screens.home

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.calendar.CalendarClock
import com.nuvio.tv.core.calendar.CALENDAR_COMING_UP_MAX
import com.nuvio.tv.core.calendar.CalendarComingUpItem
import com.nuvio.tv.core.calendar.CalendarComingUpWhen
import com.nuvio.tv.core.calendar.CalendarEntry
import com.nuvio.tv.core.calendar.CalendarEntryKind
import com.nuvio.tv.core.calendar.CalendarIds
import com.nuvio.tv.core.calendar.CalendarRefresher
import com.nuvio.tv.core.calendar.CalendarSettingsDataStore
import com.nuvio.tv.core.calendar.CalendarSource
import com.nuvio.tv.core.calendar.CalendarTitleRecord
import com.nuvio.tv.core.calendar.CalendarTitleType
import com.nuvio.tv.core.calendar.CalendarZoneProvider
import com.nuvio.tv.core.calendar.buildCalendarComingUp
import com.nuvio.tv.core.calendar.calendarContentIds
import com.nuvio.tv.core.calendar.calendarContinueWatchingKeys
import com.nuvio.tv.core.calendar.calendarContinueWatchingTitles
import com.nuvio.tv.core.calendar.calendarTitleInContinueWatching
import com.nuvio.tv.core.calendar.calendarEntryInKeys
import com.nuvio.tv.core.calendar.calendarKeyMatches
import com.nuvio.tv.core.calendar.calendarTitleKey
import com.nuvio.tv.core.calendar.calendarAddonRelease
import com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate
import com.nuvio.tv.core.util.withAppLocale
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.ui.screens.calendar.CalendarFormats
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

/**
 * A Coming up card in the Continue Watching card's shape; null for a title the app cannot open. The backdrop in memory
 * comes first, then the one the Calendar saved.
 */
fun calendarComingUpCard(
    item: CalendarComingUpItem,
    text: CalendarComingUpText,
    formats: CalendarFormats,
    art: (type: String, ids: List<String>) -> Pair<String?, String?> = { _, _ -> null to null },
    upcomingOnly: Boolean = false
): ContinueWatchingItem.NextUp? {
    val title = item.title
    val ids = calendarContentIds(title)
    val contentId = ids.firstOrNull() ?: return null
    val film = title.type == CalendarTitleType.MOVIE
    val type = if (film) "movie" else "series"
    val entry = item.entry
    val (cachedBackdrop, cachedLogo) = art(type, ids)
    val backdrop = cachedBackdrop?.takeIf(String::isNotBlank) ?: title.backdrop?.takeIf(String::isNotBlank)
    val logo = cachedLogo?.takeIf(String::isNotBlank) ?: title.logo?.takeIf(String::isNotBlank)
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
            badgeLabel = calendarComingUpLabel(item, text, formats),
            upcomingOnly = upcomingOnly,
            comingUpKey = title.key
        )
    )
}

/** Leaves out the titles hidden from Coming up; the Calendar keeps its own list. */
fun calendarTitlesShownInComingUp(titles: List<CalendarTitleRecord>, hiddenKeys: Set<String>): List<CalendarTitleRecord> =
    if (hiddenKeys.isEmpty()) titles
    else titles.filterNot { title -> title.key in hiddenKeys || hiddenKeys.any { calendarKeyMatches(it, title.type, title.ids) } }

/** Films and Coming up episodes without numbers show no "S1 E1". */
val NextUpInfo.showsEpisodeCode: Boolean
    get() = !contentType.equals("movie", ignoreCase = true) && !(badgeLabel != null && episode <= 0)

val ContinueWatchingItem.isComingUp: Boolean
    get() = this is ContinueWatchingItem.NextUp && info.badgeLabel != null

/**
 * While the Coming up row is on, the episodes of the Upcoming row are shown there instead. One without a release date
 * cannot be placed there and stays in the Upcoming row.
 */
val HomeUiState.shownUpcomingItems: List<ContinueWatchingItem>
    get() = if (upcomingInComingUp) upcomingItems.filter { upcomingAirDate(it, ZoneId.systemDefault()) == null } else upcomingItems

/** The day an Upcoming episode airs, read the way the Calendar reads add-on dates. Null for films and undated items. */
fun upcomingAirDate(item: ContinueWatchingItem, zone: ZoneId): LocalDate? {
    val info = (item as? ContinueWatchingItem.NextUp)?.info ?: return null
    if (info.contentType.equals("movie", ignoreCase = true) || info.episode <= 0) return null
    return info.released?.let(::calendarAddonRelease)?.let { (date, exactMs) ->
        exactMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: LocalDate.parse(date)
    }
        ?: parseEpisodeReleaseLocalDate(info.released, zone)
        ?: info.releaseTimestamp?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
}

/**
 * Adds the Upcoming row's episodes to the Calendar's titles. A show the Calendar follows keeps its own dates and art
 * and only gains an episode it does not list; a show hidden in the Calendar stays out; any other show gets a title of
 * its own.
 */
fun calendarTitlesWithUpcoming(
    titles: List<CalendarTitleRecord>,
    upcoming: List<ContinueWatchingItem>,
    zone: ZoneId,
    hiddenTitleKeys: Set<String> = emptySet()
): List<CalendarTitleRecord> {
    if (upcoming.isEmpty()) return titles
    val result = titles.toMutableList()
    upcoming.forEach { item ->
        val date = upcomingAirDate(item, zone) ?: return@forEach
        val info = (item as ContinueWatchingItem.NextUp).info
        val ids = CalendarIds.fromContentId(info.contentId)
        if (!ids.isEmpty && hiddenTitleKeys.any { calendarKeyMatches(it, CalendarTitleType.SERIES, ids) }) return@forEach
        val release = info.released?.let(::calendarAddonRelease)
        val entry = CalendarEntry(
            season = info.season,
            episode = info.episode,
            title = info.episodeTitle,
            airDate = release?.first ?: date.toString(),
            placeAtMs = release?.second,
            source = CalendarSource.ADDON
        )
        val index = result.indexOfFirst {
            it.contentId == info.contentId || (!ids.isEmpty && it.type == CalendarTitleType.SERIES && it.ids.sharesIdWith(ids))
        }
        if (index >= 0) {
            val title = result[index]
            val day = entry.placedFor(title.networkZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }).localDate(zone) ?: date
            // A guess the Calendar has already passed (a later episode out on or before that date) is stale.
            val passed = title.sources.any { (source, entries) ->
                source != CalendarSource.ADDON && entries.any { listed ->
                    listed.kind == CalendarEntryKind.EPISODE &&
                        ((listed.season ?: 0) > info.season || (listed.season == info.season && (listed.episode ?: 0) > info.episode)) &&
                        listed.localDate(zone)?.let { it <= day } == true
                }
            }
            val listed = passed || title.entries.any { it.slot == entry.slot }
            result[index] = title.copy(
                poster = title.poster?.takeIf(String::isNotBlank) ?: info.poster,
                backdrop = title.backdrop?.takeIf(String::isNotBlank) ?: info.backdrop,
                logo = title.logo?.takeIf(String::isNotBlank) ?: info.logo,
                sources = if (listed) {
                    title.sources
                } else {
                    title.sources + (CalendarSource.ADDON to title.sources[CalendarSource.ADDON].orEmpty() + entry)
                }
            )
        } else {
            result += CalendarTitleRecord(
                key = calendarTitleKey(CalendarTitleType.SERIES, ids) ?: "series:${info.contentId}",
                type = CalendarTitleType.SERIES,
                ids = ids,
                name = info.name,
                contentId = info.contentId,
                poster = info.poster,
                backdrop = info.backdrop,
                logo = info.logo,
                sources = mapOf(CalendarSource.ADDON to listOf(entry))
            )
        }
    }
    return result
}

private data class UpcomingCardSource(
    val contentId: String,
    val contentType: String,
    val name: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val released: String?,
    val releaseTimestamp: Long?,
    val poster: String?,
    val backdrop: String?,
    val logo: String?
)

private fun upcomingCardSources(items: List<ContinueWatchingItem>): List<UpcomingCardSource?> = items.map { item ->
    (item as? ContinueWatchingItem.NextUp)?.info?.let {
        UpcomingCardSource(
            it.contentId, it.contentType, it.name, it.season, it.episode, it.episodeTitle, it.released,
            it.releaseTimestamp, it.poster, it.backdrop, it.logo
        )
    }
}

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
     * [continueWatching] is what the Continue Watching row shows, [upcoming] what the Upcoming row would show, and
     * [active] is true while Home is in front. Until the Calendar knows what has been watched only the Upcoming
     * episodes show.
     */
    fun items(
        continueWatching: Flow<List<ContinueWatchingItem>>,
        upcoming: Flow<List<ContinueWatchingItem>>,
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
                    ) to calendarContinueWatchingTitles(
                        items.mapNotNull { item ->
                            when (item) {
                                is ContinueWatchingItem.InProgress -> item.progress.contentId
                                is ContinueWatchingItem.NextUp -> item.info.contentId.takeIf { item.info.hasAired }
                            }
                        }
                    )
                }.distinctUntilChanged()
                val minutes = active.distinctUntilChanged().flatMapLatest { if (it) minutes() else emptyFlow() }
                val upcomingCards = upcoming.distinctUntilChanged { old, new -> upcomingCardSources(old) == upcomingCardSources(new) }
                val withUpcoming = combine(shownKeys, upcomingCards) { shown, items -> shown to items }
                val hiddenHere = settingsStore.settings.map { it?.comingUpHiddenKeys.orEmpty() }.distinctUntilChanged()
                combine(calendar.state, zoneProvider.zones, withUpcoming, minutes, hiddenHere) { state, zone, (cw, upcomingItems), now, comingUpHidden ->
                    val (shown, watchable) = cw
                    val ready = state.loaded && state.watched != null
                    if (!ready && upcomingItems.isEmpty()) return@combine emptyList()
                    val hidden = settingsStore.settings.value?.hiddenTitleKeys.orEmpty()
                    val calendarTitles = if (ready) state.titles else emptyList()
                    val calendarKeys = calendarTitles.mapTo(HashSet()) { it.key }
                    val titles = calendarTitlesShownInComingUp(calendarTitlesWithUpcoming(calendarTitles, upcomingItems, zone, hidden), comingUpHidden)
                        .filterNot { calendarTitleInContinueWatching(watchable, it) }
                    val localContext = localizedContext()
                    val locale = localContext.resources.configuration.locales[0] ?: java.util.Locale.getDefault()
                    val formats = CalendarFormats.forDevice(localContext, locale)
                    val text = CalendarComingUpText.from(localContext)
                    buildCalendarComingUp(titles, state.watched, now, zone, { title, entry ->
                        calendarEntryInKeys(shown, title, entry)
                    }, CALENDAR_COMING_UP_MAX + upcomingItems.size)
                        .mapNotNull { calendarComingUpCard(it, text, formats, ::cachedArt, upcomingOnly = it.title.key !in calendarKeys) }
                        .distinctBy { Triple(it.info.contentId, it.info.season, it.info.episode) }
                }.onStart { if (calendar.state.value.watched == null) calendar.reloadWatched() }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    val rowOn: Flow<Boolean> = settingsStore.settings.map { it?.comingUpRow == true }.distinctUntilChanged()

    suspend fun hide(card: ContinueWatchingItem) {
        val info = (card as? ContinueWatchingItem.NextUp)?.info ?: return
        val key = info.comingUpKey ?: return
        settingsStore.setHiddenFromComingUp(key, hidden = true, name = info.name)
    }

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
        source.rowOn.collect { on ->
            _uiState.update { state -> if (state.upcomingInComingUp == on) state else state.copy(upcomingInComingUp = on) }
        }
    }
    viewModelScope.launch {
        withTimeoutOrNull(COMING_UP_WAIT_MS) { _initialCwResolved.first { it } }
        delay(COMING_UP_START_DELAY_MS)
        val shown = uiState.map { state ->
            if (state.continueWatchingEnabled) state.continueWatchingItems else emptyList()
        }.distinctUntilChanged()
        val upcoming = uiState.map { state ->
            if (state.continueWatchingEnabled) state.upcomingItems else emptyList()
        }.distinctUntilChanged()
        val watchProgress = merge(
            watchProgressRepository.allProgress.map { },
            watchProgressRepository.watchedItems.map { }
        ).debounce(WATCH_PROGRESS_SETTLE_MS)
        launch { watchProgress.collect { source.reloadWatched() } }
        source.items(shown, upcoming, comingUpActive)
            .collect { items ->
                _uiState.update { state -> if (state.comingUpItems == items) state else state.copy(comingUpItems = items) }
            }
    }
}

internal fun HomeViewModel.hideFromComingUp(card: ContinueWatchingItem) {
    val source = calendarComingUp ?: return
    val key = (card as? ContinueWatchingItem.NextUp)?.info?.comingUpKey ?: return
    _uiState.update { state ->
        state.copy(comingUpItems = state.comingUpItems.filterNot { (it as? ContinueWatchingItem.NextUp)?.info?.comingUpKey == key })
    }
    viewModelScope.launch { source.hide(card) }
}

private const val COMING_UP_WAIT_MS = 10_000L
private const val WATCH_PROGRESS_SETTLE_MS = 2_000L
private const val COMING_UP_START_DELAY_MS = 1_500L
