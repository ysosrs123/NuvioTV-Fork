package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.FixtureChannel
import com.nuvio.tv.core.iptv.FixtureLinkReason
import com.nuvio.tv.core.iptv.FixtureListing
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.SportsCacheEntry
import com.nuvio.tv.core.iptv.SportsDays
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureMatching
import com.nuvio.tv.core.iptv.SportsLeague
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.core.iptv.SportsRefresh
import com.nuvio.tv.core.iptv.SportsService
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class IptvSportsFixtures(val service: SportsService, val fixtures: List<SportsFixture> = emptyList(), val failed: Boolean = false,
    val missingKey: Boolean = false, val noLeagues: Boolean = false)

data class IptvFixtureLink(val row: IptvListedChannel, val reason: FixtureLinkReason, val programme: GuideProgramme? = null, val broadcaster: String? = null)

class IptvSportsFixturesRepository(private val preferences: IptvSportsPreferences, private val catalogue: IptvCatalogueStore, private val guides: IptvGuideStore,
    private val client: IptvSportsFixturesClient, private val store: IptvSportsFixturesStore) {
    private val browse = IptvBrowseRepository(catalogue, guides)
    private val memory = HashMap<String, SportsCacheEntry>()
    private val mutex = Mutex()
    private val requests = Semaphore(MAX_REQUESTS)

    suspend fun load(nowMillis: Long, zone: ZoneId, refresh: Boolean): IptvSportsFixtures = mutex.withLock {
        withContext(Dispatchers.IO) {
            val service = preferences.service
            if (service == SportsService.OFF) return@withContext IptvSportsFixtures(service)
            val leagues = SportsLeagues.chosen(preferences.leagues, service)
            if (leagues.isEmpty()) return@withContext IptvSportsFixtures(service, noLeagues = true)
            val key = if (service == SportsService.THESPORTSDB) preferences.key()?.takeIf { IptvSportsPreferences.validKey(it) } else null
            if (service == SportsService.THESPORTSDB && key == null) return@withContext IptvSportsFixtures(service, missingKey = true)
            val (from, until) = SportsDays.window(nowMillis, zone)
            val dates = SportsDays.serviceDates(from, until, SportsDays.zone(service))
            val wanted = leagues.flatMap { league -> dates.map { date -> Wanted(IptvSportsFixturesStore.key(service, league, date), league, date) } }
            for (item in wanted) if (item.key !in memory) store.read(item.key)?.let { memory[item.key] = it }
            memory.keys.retainAll(wanted.map { it.key }.toSet())
            if (refresh) {
                val due = wanted.filter { SportsRefresh.due(memory[it.key], nowMillis) }
                if (due.isNotEmpty()) {
                    val results = coroutineScope { due.map { item -> async { requests.withPermit { item.key to fetch(service, item, key, nowMillis) } } }.awaitAll() }
                    for ((name, entry) in results) { memory[name] = entry; store.write(name, entry) }
                    val failures = results.count { it.second.failedAt == nowMillis }
                    IptvLog.info("sports fetch service=${service.name.lowercase()} requests=${due.size} failed=$failures")
                    store.prune(wanted.map { it.key }.toSet())
                }
            }
            val fixtures = wanted.flatMap { memory[it.key]?.fixtures.orEmpty() }
                .filter { it.startMillis >= from - EARLIER_MILLIS && it.startMillis < until }.distinctBy { it.league to it.id }
            IptvSportsFixtures(service, fixtures, failed = wanted.any { (memory[it.key]?.failures ?: 0) > 0 })
        }
    }

    private suspend fun fetch(service: SportsService, item: Wanted, key: String?, nowMillis: Long): SportsCacheEntry = try {
        SportsRefresh.succeeded(client.fixtures(service, item.league, item.date, key, nowMillis), nowMillis)
    } catch (cancel: CancellationException) { throw cancel }
    catch (error: Exception) {
        IptvLog.failure("sports fetch ${service.name.lowercase()}${(error as? SportsFetchException)?.status?.let { " status=$it" }.orEmpty()}", error)
        SportsRefresh.failed(memory[item.key], nowMillis)
    }

    suspend fun links(ref: IptvSourceRef, fixtures: List<SportsFixture>, nowMillis: Long, hiddenCategories: Set<String>): Map<String, List<IptvFixtureLink>> =
        withContext(Dispatchers.IO) {
            val current = fixtures.filter { it.status != FixtureStatus.FINAL }.take(MAX_FIXTURES)
            if (current.isEmpty()) return@withContext emptyMap()
            val excluded = hiddenCategories.take(500).toSet()
            val associations = catalogue.guideAssociations(ref)
            val order = (associations.priority + associations.feedIds).distinct().take(16)
            val matched = mutableListOf<IptvAiringMatch>()
            val slices = current.map { Math.floorDiv(maxOf(it.startMillis, nowMillis - SLICE_MILLIS), SLICE_MILLIS) }.distinct().sorted().take(MAX_SLICES)
            if (order.isNotEmpty()) for (slice in slices) {
                currentCoroutineContext().ensureActive()
                val start = slice * SLICE_MILLIS
                val found = guides.sportsMatches(ref.profileId, order, start, start + SLICE_MILLIS, PROGRAMMES_PER_SLICE)
                matched += found.filter { match -> current.any { SportsFixtureMatching.guideMatch(it, match.programme, nowMillis) != null } }
            }
            val unique = matched.distinctBy { Triple(it.key, it.programme.start.epochMillis, it.programme.titles.firstOrNull()?.text) }
            val guideRows = browse.guideChannels(ref, unique, excluded)
            val byKey = unique.groupBy { it.key }
            val listings = guideRows.flatMap { row -> byKey[row.guide.key].orEmpty().map { FixtureListing(row.item.channel.id, it.programme) } }
            val terms = SportsFixtureMatching.searchTerms(current.flatMap { it.broadcasters }.distinct())
            val broadcastRows = terms.flatMap { term ->
                currentCoroutineContext().ensureActive()
                try { browse.page(ref, IptvBrowseQuery(search = term, excludedCategories = excluded), null, ROWS_PER_TERM).channels }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { IptvLog.failure("sports channels", error); emptyList() }
            }
            val rows = (guideRows + broadcastRows).distinctBy { it.item.channel.id }
            val channels = rows.map { FixtureChannel(it.item.channel.id, it.item.overlay.customName ?: it.item.channel.data.name,
                it.item.attributes[CATEGORY_ATTRIBUTE]?.trim()?.take(240), it.item.overlay.hidden) }
            val byId = rows.associateBy { it.item.channel.id }
            SportsFixtureMatching.link(current, channels, listings, excluded, nowMillis).mapValues { (_, links) ->
                links.mapNotNull { link -> byId[link.channelId]?.let { IptvFixtureLink(it, link.reason, link.programme, link.broadcaster) } }
            }.filterValues { it.isNotEmpty() }
        }

    private class Wanted(val key: String, val league: SportsLeague, val date: LocalDate)

    private companion object {
        const val MAX_REQUESTS = 2
        const val MAX_FIXTURES = 120
        const val MAX_SLICES = 24
        const val PROGRAMMES_PER_SLICE = 400
        const val ROWS_PER_TERM = 200
        const val SLICE_MILLIS = 60L * 60 * 1000
        const val EARLIER_MILLIS = 12L * 60 * 60 * 1000
        const val CATEGORY_ATTRIBUTE = "group-title"
    }
}
