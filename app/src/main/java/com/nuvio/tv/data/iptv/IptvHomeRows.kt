package com.nuvio.tv.data.iptv

import android.content.Context
import com.nuvio.tv.core.iptv.GuideGridWindow
import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.GuideProgrammeCell
import com.nuvio.tv.core.iptv.HomeRowKind
import com.nuvio.tv.core.iptv.HomeRowSettings
import com.nuvio.tv.core.iptv.HomeRows
import com.nuvio.tv.core.iptv.VodKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class IptvHomePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("iptv-live", Context.MODE_PRIVATE)

    val settings: HomeRowSettings
        get() = HomeRowSettings.read { kind -> key(kind).takeIf(preferences::contains)?.let { preferences.getBoolean(it, kind.enabledByDefault) } }

    fun set(kind: HomeRowKind, on: Boolean) = preferences.edit().putBoolean(key(kind), on).apply()

    private fun key(kind: HomeRowKind) = "settings-home-${kind.key}"
}

data class IptvHomeChannel(val channel: IptvListedChannel, val programme: GuideProgramme?)

class IptvHomeRowsLoader(private val catalogue: IptvCatalogueStore, guides: IptvGuideStore, private val vod: IptvVodRepository,
    private val vodStreams: IptvVodStreams, private val hidden: (IptvSourceRef) -> Set<String>) {
    private val browse = IptvBrowseRepository(catalogue, guides)

    suspend fun favourites(profileId: Int, sources: List<IptvSource>, now: Long, limit: Int = HomeRows.FAVOURITES): List<IptvHomeChannel> = withContext(Dispatchers.IO) {
        val groups = mutableListOf<List<IptvListedChannel>>()
        var count = 0
        for (source in sources) {
            if (count >= limit) break
            currentCoroutineContext().ensureActive()
            val channels = quiet("home favourites") { browse.page(source.ref, IptvBrowseQuery(favouritesOnly = true), null, (limit - count).coerceIn(1, 200)).channels }
            groups += channels
            count += channels.size
        }
        val channels = HomeRows.merged(groups, { identity(it) }, limit)
        channels.groupBy { it.item.channel.sourceId }.values.flatMap { group -> nowPlaying(profileId, group, now) }
            .associateBy { identity(it.channel) }.let { found -> channels.mapNotNull { found[identity(it)] } }
    }

    suspend fun sport(sources: List<IptvSource>, now: Long, limit: Int = HomeRows.SPORT): List<IptvHomeChannel> = withContext(Dispatchers.IO) {
        val listings = sources.flatMap { source ->
            currentCoroutineContext().ensureActive()
            quiet("home sport") { browse.sports(source.ref, now, aheadMillis = 0, limit = limit.coerceIn(1, 200), excludedCategories = hidden(source.ref).take(500).toSet()) }
                .map { it.channel to it.programme }
        }
        HomeRows.sportOnNow(listings, { identity(it) }, now, limit).map { (channel, programme) -> IptvHomeChannel(channel, programme) }
    }

    suspend fun recentTitles(profileId: Int, kind: VodKind, limit: Int = HomeRows.TITLES): List<IptvVodTitle> = withContext(Dispatchers.IO) {
        val groups = vodStreams.sources(profileId, kind).filter { it.playbackEligible }.map { source ->
            currentCoroutineContext().ensureActive()
            quiet("home titles") { vod.page(source.ref, kind, null, 0, limit.coerceIn(1, 200), recent = true).items }
        }
        HomeRows.recentTitles(groups, { it.ref.format() }, { it.addedSeconds }, limit)
    }

    suspend fun logos(profileId: Int, channels: Collection<Pair<String, String>>): Map<Pair<String, String>, String> = withContext(Dispatchers.IO) {
        channels.distinct().take(HomeRows.RECORDINGS).mapNotNull { (sourceId, channelId) ->
            currentCoroutineContext().ensureActive()
            val item = runCatching { catalogue.playbackItem(IptvSourceRef(profileId, sourceId), channelId) }.getOrNull()
            HomeRows.logo(item?.attributes?.get("tvg-logo"))?.let { (sourceId to channelId) to it }
        }.toMap()
    }

    private suspend fun <T> quiet(stage: String, block: suspend () -> List<T>): List<T> = try { block() }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { IptvLog.failure(stage, error); emptyList() }

    private suspend fun nowPlaying(profileId: Int, channels: List<IptvListedChannel>, now: Long): List<IptvHomeChannel> {
        val window = GuideGridWindow(now, now + HomeRows.NOW_SPAN_MILLIS)
        val rows = try { browse.guideRows(profileId, channels.take(200), window, HomeRows.PROGRAMMES_PER_CHANNEL) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { IptvLog.failure("home guide", error); emptyMap() }
        return channels.map { channel ->
            val programmes = rows[channel.item.channel.id]?.cells?.filterIsInstance<GuideProgrammeCell>()?.map { it.programme }.orEmpty()
            IptvHomeChannel(channel, HomeRows.nowPlaying(programmes, now))
        }
    }

    companion object {
        fun identity(channel: IptvListedChannel): String = channel.item.channel.sourceId + "\u0000" + channel.item.channel.id
    }
}
