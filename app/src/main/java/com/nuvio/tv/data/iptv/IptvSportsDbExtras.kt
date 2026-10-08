package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.SportsDbLeague
import com.nuvio.tv.core.iptv.SportsDbLeagues
import com.nuvio.tv.core.iptv.SportsDbLive
import com.nuvio.tv.core.iptv.SportsDbLiveScore
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsRefresh
import com.nuvio.tv.core.iptv.SportsService
import com.nuvio.tv.core.iptv.SportsTv
import com.nuvio.tv.core.iptv.SportsTvChannel
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

class IptvSportsDbExtras(private val client: IptvSportsFixturesClient, private val store: IptvSportsFixturesStore) {
    private class Live(val scores: Map<String, SportsDbLiveScore>, val fetchedAt: Long?, val failedAt: Long? = null, val failures: Int = 0)
    private class Tv(val at: Long, val channels: List<SportsTvChannel>)
    private class Match(val at: Long, val id: String)

    private val live = HashMap<String, Live>()
    private var tv: MutableMap<String, Tv>? = null
    private val matches = HashMap<String, Match>()
    private var tvFailedAt = 0L
    private var leagueList: Pair<Long, List<SportsDbLeague>>? = null

    suspend fun livescores(fixtures: List<SportsFixture>, key: String?, nowMillis: Long, refresh: Boolean): List<SportsFixture> {
        val sports = if (key == null) emptyList() else SportsDbLive.wanted(fixtures, nowMillis)
        if (sports.isEmpty()) return fixtures
        if (refresh) for (sport in sports) {
            val entry = synchronized(live) { live[sport] }
            if (!due(entry, nowMillis)) continue
            val next = try { Live(client.livescore(sport, key!!).associateBy { it.eventId }, nowMillis) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    IptvLog.failure("sports livescore${(error as? SportsFetchException)?.status?.let { " status=$it" }.orEmpty()}", error)
                    Live(entry?.scores.orEmpty(), entry?.fetchedAt, nowMillis, minOf((entry?.failures ?: 0) + 1, 20))
                }
            synchronized(live) { live[sport] = next }
        }
        val scores = synchronized(live) {
            sports.flatMap { sport -> live[sport]?.takeIf { it.fetchedAt != null && nowMillis - it.fetchedAt in 0..SportsDbLive.FRESH_MILLIS }?.scores?.values.orEmpty() }
        }.associateBy { it.eventId }
        return SportsDbLive.merge(fixtures, scores, nowMillis)
    }

    private fun due(entry: Live?, nowMillis: Long): Boolean {
        if (entry == null) return true
        val failedAt = entry.failedAt
        if (entry.failures > 0 && failedAt != null && nowMillis - failedAt in 0 until SportsRefresh.backoff(entry.failures)) return false
        val fetched = entry.fetchedAt ?: return true
        return nowMillis < fetched || nowMillis - fetched >= SportsRefresh.LIVE_MILLIS
    }

    suspend fun channels(fixtures: List<SportsFixture>, favourites: Set<String>, key: String?, country: String?, nowMillis: Long, refresh: Boolean,
        events: suspend (SportsFixture) -> List<SportsFixture>?): List<SportsFixture> {
        if (key == null || favourites.isEmpty()) return fixtures
        val wanted = SportsTv.wanted(fixtures, favourites, nowMillis)
        if (wanted.isEmpty()) return fixtures
        val cached = synchronized(matches) { tv ?: load().also { tv = it } }
        var budget = if (refresh && nowMillis - tvFailedAt !in 0 until TV_BACKOFF_MILLIS) MAX_REQUESTS else 0
        var changed = false
        val found = HashMap<String, List<String>>()
        for (fixture in wanted) {
            val id = if (fixture.source == SportsService.THESPORTSDB) fixture.id else {
                val known = synchronized(matches) { matches[fixture.key]?.takeIf { nowMillis - it.at in 0 until (if (it.id.isEmpty()) MISS_MILLIS else SportsTv.MATCH_TTL_MILLIS) } }
                when {
                    known != null -> known.id
                    budget > 0 -> {
                        val day = try { events(fixture) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
                        if (day == null) { budget = 0; continue }
                        budget--
                        val matched = SportsTv.match(fixture, day)?.id.orEmpty()
                        synchronized(matches) { matches[fixture.key] = Match(nowMillis, matched) }
                        matched
                    }
                    else -> continue
                }
            }
            if (id.isEmpty()) continue
            val entry = synchronized(matches) { cached[id]?.takeIf { nowMillis - it.at in 0 until SportsTv.TTL_MILLIS } } ?: if (budget > 0) {
                budget--
                try { Tv(nowMillis, client.tv(id, key)).also { synchronized(matches) { cached[id] = it }; changed = true } }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    IptvLog.failure("sports tv${(error as? SportsFetchException)?.status?.let { " status=$it" }.orEmpty()}", error)
                    tvFailedAt = nowMillis; budget = 0; null
                }
            } else null
            entry?.let { found[fixture.key] = SportsTv.names(it.channels, country) }
        }
        if (changed) save(cached, nowMillis)
        return SportsTv.add(fixtures, found)
    }

    private fun load(): MutableMap<String, Tv> {
        val text = store.readText(IptvSportsFixturesStore.TV) ?: return HashMap()
        return runCatching {
            val events = JSONObject(text).getJSONObject("events")
            val result = HashMap<String, Tv>()
            events.keys().asSequence().take(MAX_EVENTS).forEach { id ->
                val item = events.optJSONObject(id) ?: return@forEach
                val list = item.optJSONArray("channels") ?: JSONArray()
                result[id] = Tv(item.optLong("at"), (0 until minOf(list.length(), 64)).mapNotNull { list.optJSONObject(it) }.mapNotNull { channel ->
                    channel.optString("name").takeIf { it.isNotBlank() }?.let { SportsTvChannel(it, channel.optString("country").takeIf(String::isNotBlank)) }
                })
            }
            result
        }.getOrElse { HashMap() }
    }

    private fun save(cached: MutableMap<String, Tv>, nowMillis: Long) {
        val text = synchronized(matches) {
            cached.entries.removeAll { nowMillis - it.value.at !in 0 until SportsTv.TTL_MILLIS }
            JSONObject().put("events", JSONObject().apply {
                cached.entries.sortedByDescending { it.value.at }.take(MAX_EVENTS).forEach { (id, entry) ->
                    put(id, JSONObject().put("at", entry.at).put("channels", JSONArray().apply {
                        entry.channels.forEach { put(JSONObject().put("name", it.name).apply { it.country?.let { country -> put("country", country) } }) }
                    }))
                }
            }).toString()
        }
        store.writeText(IptvSportsFixturesStore.TV, text)
    }

    suspend fun leagues(key: String, nowMillis: Long): List<SportsDbLeague> {
        val known = synchronized(matches) { leagueList } ?: store.readText(IptvSportsFixturesStore.LEAGUES)?.let(SportsDbLeagues::decode)
        if (known != null && nowMillis - known.first in 0 until SportsDbLeagues.TTL_MILLIS && known.second.isNotEmpty()) {
            synchronized(matches) { leagueList = known }
            return known.second
        }
        val fetched = try { client.leagues(key) } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) {
            IptvLog.failure("sports leagues", error)
            known?.second?.takeIf { it.isNotEmpty() } ?: throw error
        }
        if (fetched.isNotEmpty() && fetched !== known?.second) {
            synchronized(matches) { leagueList = nowMillis to fetched }
            store.writeText(IptvSportsFixturesStore.LEAGUES, SportsDbLeagues.encode(nowMillis, fetched))
        }
        return fetched
    }

    private companion object {
        const val MAX_REQUESTS = 3
        const val MAX_EVENTS = 200
        const val MISS_MILLIS = 6L * 60 * 60 * 1000
        const val TV_BACKOFF_MILLIS = 30L * 60 * 1000
    }
}
