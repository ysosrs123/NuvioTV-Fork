package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.SportsCacheEntry
import com.nuvio.tv.core.iptv.SportsDays
import com.nuvio.tv.core.iptv.SportsLeague
import com.nuvio.tv.core.iptv.SportsPolling
import com.nuvio.tv.core.iptv.SportsRefresh
import com.nuvio.tv.core.iptv.SportsService
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

    suspend fun load(service: SportsService, leagues: List<SportsLeague>, key: String?, nowMillis: Long, zone: ZoneId, refresh: Boolean,
        favourites: Set<String> = emptySet(), prune: Boolean = true): IptvSportsFixtures =
        withContext(Dispatchers.IO) {
            val (from, until) = SportsDays.window(nowMillis, zone)
            val dates = SportsDays.serviceDates(from, until, SportsDays.zone(service))
            val wanted = leagues.flatMap { league -> dates.map { date -> Wanted(IptvSportsFixturesStore.key(service, league, date), league, date) } }
            val names = wanted.map { it.key }.toSet()
            synchronized(memory) { for (item in wanted) if (item.key !in memory) store.read(item.key)?.let { memory[item.key] = it } }
            if (refresh) fetching.withLock {
                val due = synchronized(memory) { wanted.filter { SportsPolling.due(memory[it.key], favourites, nowMillis) } }
                if (due.isNotEmpty()) {
                    val results = coroutineScope { due.map { item -> async { requests.withPermit { item.key to fetch(service, item, key, nowMillis) } } }.awaitAll() }
                    synchronized(memory) { for ((name, entry) in results) memory[name] = entry }
                    for ((name, entry) in results) store.write(name, entry)
                    IptvLog.info("sports fetch service=${service.name.lowercase()} requests=${due.size} failed=${results.count { it.second.failedAt == nowMillis }}")
                    if (prune) store.prune(names)
                }
            }
            synchronized(memory) {
                if (prune) memory.keys.retainAll(names)
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
        SportsRefresh.failed(synchronized(memory) { memory[item.key] }, nowMillis)
    }

    private class Wanted(val key: String, val league: SportsLeague, val date: LocalDate)

    private companion object {
        const val MAX_REQUESTS = 2
        const val EARLIER_MILLIS = 12L * 60 * 60 * 1000
    }
}
