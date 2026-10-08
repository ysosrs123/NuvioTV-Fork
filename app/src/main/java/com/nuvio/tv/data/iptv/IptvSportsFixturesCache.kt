package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.SportsCacheEntry
import com.nuvio.tv.core.iptv.SportsDays
import com.nuvio.tv.core.iptv.SportsDbLeague
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsLeague
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.core.iptv.SportsPolling
import com.nuvio.tv.core.iptv.SportsRefresh
import com.nuvio.tv.core.iptv.SportsService
import com.nuvio.tv.core.iptv.SportsSources
import com.nuvio.tv.core.iptv.SportsTv
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class IptvSportsFixturesCache(private val client: IptvSportsFixturesClient, private val store: IptvSportsFixturesStore) {
    private val memory = HashMap<String, SportsCacheEntry>()
    private val fetching = Mutex()
    private val requests = Semaphore(MAX_REQUESTS)
    private val extras = IptvSportsDbExtras(client, store)

    suspend fun load(leagues: List<SportsLeague>, key: String?, nowMillis: Long, zone: ZoneId, refresh: Boolean, favourites: Set<String> = emptySet(),
        prune: Boolean = true, country: String? = null): IptvSportsFixtures =
        withContext(Dispatchers.IO) {
            val (from, until) = SportsDays.window(nowMillis, zone)
            val hasKey = key != null
            fun wanted(service: SportsService, league: SportsLeague) = SportsDays.serviceDates(from, until, SportsDays.zone(service))
                .map { date -> Wanted(IptvSportsFixturesStore.key(service, league, date), service, league, date) }
            val espn = leagues.filter { it.espn != null }.associate { it.id to wanted(SportsService.ESPN, it) }
            val sportsDb = if (hasKey) leagues.filter { it.sportsDb != null }.associate { it.id to wanted(SportsService.THESPORTSDB, it) } else emptyMap()
            val all = espn.values.flatten() + sportsDb.values.flatten()
            val names = all.map { it.key }.toSet()
            synchronized(memory) { for (item in all) if (item.key !in memory) store.read(item.key)?.let { memory[item.key] = it } }
            fun entries(list: List<Wanted>?) = synchronized(memory) { list.orEmpty().map { memory[it.key] } }
            fun fallback(league: SportsLeague) = SportsSources.fallback(league, hasKey, entries(espn[league.id]))
            if (refresh) fetching.withLock {
                val first = espn.values.flatten() + leagues.filter { it.espn == null }.flatMap { sportsDb[it.id].orEmpty() }
                val done = fetch(first.filter { due(it, favourites, nowMillis) }, key, nowMillis).toMutableList()
                done += fetch(leagues.filter { it.espn != null && fallback(it) }.flatMap { sportsDb[it.id].orEmpty() }.filter { due(it, favourites, nowMillis) }, key, nowMillis)
                if (done.isNotEmpty()) {
                    IptvLog.info("sports fetch requests=${done.size} espn=${done.count { it.first.service == SportsService.ESPN }} " +
                        "failed=${done.count { it.second.failedAt == nowMillis }}")
                    if (prune) store.prune(names)
                }
            }
            val (fixtures, failed) = synchronized(memory) {
                if (prune) memory.keys.retainAll(names)
                val shown = leagues.mapNotNull { league ->
                    when (SportsSources.pick(league, hasKey, entries(espn[league.id]), entries(sportsDb[league.id]))) {
                        SportsService.ESPN -> espn[league.id]
                        SportsService.THESPORTSDB -> sportsDb[league.id]
                        null -> null
                    }
                }.flatten()
                shown.flatMap { memory[it.key]?.fixtures.orEmpty() }.filter { it.startMillis >= from - EARLIER_MILLIS && it.startMillis < until }
                    .distinctBy { it.key } to shown.any { (memory[it.key]?.failures ?: 0) > 0 }
            }
            val extended = if (refresh) fetching.withLock { extend(fixtures, favourites, key, country, nowMillis, true) }
                else extend(fixtures, favourites, key, country, nowMillis, false)
            IptvSportsFixtures(true, extended, failed = failed)
        }

    suspend fun leagues(key: String, nowMillis: Long): List<SportsDbLeague> = withContext(Dispatchers.IO) { extras.leagues(key, nowMillis) }

    private suspend fun extend(fixtures: List<SportsFixture>, favourites: Set<String>, key: String?, country: String?, nowMillis: Long, refresh: Boolean) =
        extras.channels(extras.livescores(fixtures, key, nowMillis, refresh), favourites, key, country, nowMillis, refresh) { fixture -> sportsDbDay(fixture, key, nowMillis) }

    private suspend fun sportsDbDay(fixture: SportsFixture, key: String?, nowMillis: Long): List<SportsFixture>? {
        val league = SportsLeagues.byId(fixture.league)?.takeIf { it.sportsDb != null } ?: return emptyList()
        val date = Instant.ofEpochMilli(fixture.startMillis).atZone(SportsDays.SPORTSDB_ZONE).toLocalDate()
        val item = Wanted(IptvSportsFixturesStore.key(SportsService.THESPORTSDB, league, date), SportsService.THESPORTSDB, league, date)
        val known = synchronized(memory) { memory[item.key] ?: store.read(item.key)?.also { memory[item.key] = it } }
        val fetched = known?.fetchedAt
        if (known != null && fetched != null && nowMillis - fetched in 0 until SportsTv.MATCH_TTL_MILLIS) return known.fixtures
        val failedAt = known?.failedAt
        if (known != null && known.failures > 0 && failedAt != null && nowMillis - failedAt in 0 until SportsRefresh.backoff(known.failures)) return null
        val (_, entry) = fetch(listOf(item), key, nowMillis).single()
        return if (entry.failedAt == nowMillis) null else entry.fixtures
    }

    private fun due(item: Wanted, favourites: Set<String>, nowMillis: Long): Boolean = SportsPolling.due(synchronized(memory) { memory[item.key] }, favourites, nowMillis)

    private suspend fun fetch(items: List<Wanted>, key: String?, nowMillis: Long): List<Pair<Wanted, SportsCacheEntry>> {
        if (items.isEmpty()) return emptyList()
        val results = coroutineScope { items.map { item -> async { requests.withPermit { item to fetch(item, key, nowMillis) } } }.awaitAll() }
        synchronized(memory) { for ((item, entry) in results) memory[item.key] = entry }
        for ((item, entry) in results) store.write(item.key, entry)
        return results
    }

    private suspend fun fetch(item: Wanted, key: String?, nowMillis: Long): SportsCacheEntry = try {
        SportsRefresh.succeeded(client.fixtures(item.service, item.league, item.date, key, nowMillis), nowMillis)
    } catch (cancel: CancellationException) { throw cancel }
    catch (error: Exception) {
        IptvLog.failure("sports fetch ${item.service.name.lowercase()}${(error as? SportsFetchException)?.status?.let { " status=$it" }.orEmpty()}", error)
        SportsRefresh.failed(synchronized(memory) { memory[item.key] }, nowMillis)
    }

    private data class Wanted(val key: String, val service: SportsService, val league: SportsLeague, val date: LocalDate)

    private companion object {
        const val MAX_REQUESTS = 2
        const val EARLIER_MILLIS = 12L * 60 * 60 * 1000
    }
}
