package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.FixtureChannel
import com.nuvio.tv.core.iptv.FixtureLinkReason
import com.nuvio.tv.core.iptv.FixtureListing
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.SportsCatchup
import com.nuvio.tv.core.iptv.SportsDbLeague
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureMatching
import com.nuvio.tv.core.iptv.SportsGuideSlices
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.core.iptv.SportsPolling
import com.nuvio.tv.core.iptv.SportsSources
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class IptvSportsFixtures(val enabled: Boolean, val fixtures: List<SportsFixture> = emptyList(), val failed: Boolean = false,
    val missingKey: Boolean = false, val noLeagues: Boolean = false)

data class IptvFixtureLink(val row: IptvListedChannel, val reason: FixtureLinkReason, val programme: GuideProgramme? = null, val broadcaster: String? = null)

class IptvSportsFixturesRepository(private val preferences: IptvSportsPreferences, private val catalogue: IptvCatalogueStore, private val guides: IptvGuideStore,
    client: IptvSportsFixturesClient, store: IptvSportsFixturesStore) {
    private val browse = IptvBrowseRepository(catalogue, guides)
    private val cache = IptvSportsFixturesCache(client, store)

    suspend fun load(nowMillis: Long, zone: ZoneId, refresh: Boolean, favourites: Set<String> = emptySet(), followedOnly: Boolean = false): IptvSportsFixtures =
        withContext(Dispatchers.IO) {
            if (!preferences.enabled) return@withContext IptvSportsFixtures(false)
            val chosen = SportsLeagues.chosen(preferences.leagues)
            val leagues = if (followedOnly) SportsPolling.followedLeagues(favourites).let { ids -> chosen.filter { it.id in ids } } else chosen
            if (leagues.isEmpty()) return@withContext IptvSportsFixtures(true, noLeagues = true)
            val key = if (preferences.hasKey) preferences.key()?.takeIf { IptvSportsPreferences.validKey(it) } else null
            val usable = leagues.filter { SportsSources.available(it, key != null) }
            if (usable.isEmpty()) return@withContext IptvSportsFixtures(true, missingKey = true)
            val result = cache.load(usable, key, nowMillis, zone, refresh, favourites, prune = !followedOnly, country = Locale.getDefault().country)
            result.copy(fixtures = SportsSources.logos(result.fixtures, preferences.logos))
        }

    suspend fun leagueList(nowMillis: Long): List<SportsDbLeague>? {
        val key = withContext(Dispatchers.IO) { if (preferences.hasKey) preferences.key()?.takeIf { IptvSportsPreferences.validKey(it) } else null } ?: return null
        return cache.leagues(key, nowMillis)
    }

    suspend fun links(ref: IptvSourceRef, fixtures: List<SportsFixture>, nowMillis: Long, hiddenCategories: Set<String>): Map<String, List<IptvFixtureLink>> =
        withContext(Dispatchers.IO) {
            val upcoming = fixtures.filter { it.status != FixtureStatus.FINAL }.take(MAX_FIXTURES)
            val recent = SportsCatchup.recent(fixtures, nowMillis)
            val current = upcoming + recent
            if (current.isEmpty()) return@withContext emptyMap()
            val excluded = hiddenCategories.take(500).toSet()
            val associations = catalogue.guideAssociations(ref)
            val order = (associations.priority + associations.feedIds).distinct().take(16)
            val source = preferences.channelSource
            val matched = mutableListOf<IptvAiringMatch>()
            val slices = SportsGuideSlices.plan(upcoming, recent, nowMillis)
            if (source.guide && order.isNotEmpty()) for (slice in slices.keys) {
                currentCoroutineContext().ensureActive()
                val start = slice * SportsGuideSlices.SLICE_MILLIS
                val near = (slice - NEIGHBOUR_SLICES..slice + 1).flatMap { slices[it].orEmpty() }.distinctBy { it.key }
                fun query(limit: Int) = guides.sportsMatches(ref.profileId, order, start, start + SportsGuideSlices.SLICE_MILLIS, limit)
                val found = query(PROGRAMMES_PER_SLICE).let { if (it.size >= PROGRAMMES_PER_SLICE / order.size) query(BUSY_PROGRAMMES_PER_SLICE) else it }
                matched += found.filter { match -> near.any { SportsFixtureMatching.guideMatch(it, match.programme, nowMillis) != null } }
            }
            val unique = matched.distinctBy { Triple(it.key, it.programme.start.epochMillis, it.programme.titles.firstOrNull()?.text) }
            val guideRows = browse.guideChannels(ref, unique, excluded)
            val byKey = unique.groupBy { it.key }
            val listings = guideRows.flatMap { row -> byKey[row.guide.key].orEmpty().map { FixtureListing(row.item.channel.id, it.programme) } }
            val terms = if (source.broadcasters) SportsFixtureMatching.searchTerms(current.flatMap { it.broadcasters }.distinct()) else emptyList()
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
            SportsFixtureMatching.link(current, channels, listings, excluded, nowMillis, source = source).mapValues { (_, links) ->
                links.mapNotNull { link -> byId[link.channelId]?.let { IptvFixtureLink(it, link.reason, link.programme, link.broadcaster) } }
            }.filterValues { it.isNotEmpty() }
        }

    private companion object {
        const val MAX_FIXTURES = 320
        const val NEIGHBOUR_SLICES = 3
        const val PROGRAMMES_PER_SLICE = 400
        const val BUSY_PROGRAMMES_PER_SLICE = 2000
        const val ROWS_PER_TERM = 200
        const val CATEGORY_ATTRIBUTE = "group-title"
    }
}
