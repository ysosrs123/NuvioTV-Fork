package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.FixtureChannel
import com.nuvio.tv.core.iptv.FixtureLinkReason
import com.nuvio.tv.core.iptv.FixtureListing
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.SportsCatchup
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureMatching
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.core.iptv.SportsPolling
import com.nuvio.tv.core.iptv.SportsService
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class IptvSportsFixtures(val service: SportsService, val fixtures: List<SportsFixture> = emptyList(), val failed: Boolean = false,
    val missingKey: Boolean = false, val noLeagues: Boolean = false)

data class IptvFixtureLink(val row: IptvListedChannel, val reason: FixtureLinkReason, val programme: GuideProgramme? = null, val broadcaster: String? = null)

class IptvSportsFixturesRepository(private val preferences: IptvSportsPreferences, private val catalogue: IptvCatalogueStore, private val guides: IptvGuideStore,
    client: IptvSportsFixturesClient, store: IptvSportsFixturesStore) {
    private val browse = IptvBrowseRepository(catalogue, guides)
    private val cache = IptvSportsFixturesCache(client, store)

    suspend fun load(nowMillis: Long, zone: ZoneId, refresh: Boolean, favourites: Set<String> = emptySet(), followedOnly: Boolean = false): IptvSportsFixtures =
        withContext(Dispatchers.IO) {
            val service = preferences.service
            if (service == SportsService.OFF) return@withContext IptvSportsFixtures(service)
            val chosen = SportsLeagues.chosen(preferences.leagues, service)
            val leagues = if (followedOnly) SportsPolling.followedLeagues(favourites).let { ids -> chosen.filter { it.id in ids } } else chosen
            if (leagues.isEmpty()) return@withContext IptvSportsFixtures(service, noLeagues = true)
            val key = if (service == SportsService.THESPORTSDB) preferences.key()?.takeIf { IptvSportsPreferences.validKey(it) } else null
            if (service == SportsService.THESPORTSDB && key == null) return@withContext IptvSportsFixtures(service, missingKey = true)
            cache.load(service, leagues, key, nowMillis, zone, refresh, favourites, prune = !followedOnly)
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
            val matched = mutableListOf<IptvAiringMatch>()
            val slices = (upcoming.map { Math.floorDiv(maxOf(it.startMillis, nowMillis - SLICE_MILLIS), SLICE_MILLIS) }.distinct().sorted().take(MAX_SLICES) +
                recent.map { Math.floorDiv(it.startMillis, SLICE_MILLIS) }.distinct().sortedDescending().take(MAX_RECENT_SLICES)).distinct().sorted()
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

    private companion object {
        const val MAX_FIXTURES = 120
        const val MAX_SLICES = 24
        const val MAX_RECENT_SLICES = 8
        const val PROGRAMMES_PER_SLICE = 400
        const val ROWS_PER_TERM = 200
        const val SLICE_MILLIS = 60L * 60 * 1000
        const val CATEGORY_ATTRIBUTE = "group-title"
    }
}
