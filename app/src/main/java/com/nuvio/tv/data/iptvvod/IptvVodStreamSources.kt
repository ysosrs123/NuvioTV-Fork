package com.nuvio.tv.data.iptvvod

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.VodKind
import com.nuvio.tv.core.iptv.VodStreamRequest
import com.nuvio.tv.core.iptv.VodStreams
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvSource
import com.nuvio.tv.data.iptv.IptvVodStreamItem
import com.nuvio.tv.data.iptv.IptvVodStreams
import com.nuvio.tv.data.iptv.IptvVodTitle
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.mediaserver.ServerStreamSource
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IptvVodStreamSources @Inject constructor(
    @ApplicationContext private val context: Context,
    private val streams: IptvVodStreams,
    private val settings: PlayerSettingsDataStore,
    private val profiles: ProfileManager,
    private val tmdb: TmdbService,
    private val tmdbApi: TmdbApi,
) {
    private data class TmdbTitles(val titles: List<String>, val year: Int?)

    private val titleCache = object : LinkedHashMap<String, TmdbTitles>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TmdbTitles>?): Boolean = size > 64
    }

    suspend fun enabled(): Boolean = BuildConfig.FEATURE_IPTV_ENABLED && settings.playerSettings.first().iptvVodStreamsEnabled

    suspend fun revision(): String {
        if (!enabled()) return VodStreams.revision(false, emptyList())
        val profileId = profiles.activeProfileId.value
        return VodStreams.revision(true, io(listOf("unavailable")) { streams.revision(profileId) })
    }

    suspend fun sources(type: String, videoId: String, season: Int?, episode: Int?): List<ServerStreamSource> {
        if (!enabled()) return emptyList()
        val request = VodStreams.request(type, videoId, season, episode) ?: return emptyList()
        val profileId = profiles.activeProfileId.value
        val available = io(emptyList()) { streams.sources(profileId, request.kind) }
        if (available.isEmpty()) return emptyList()
        val matches = Matches(profileId, type, request)
        return available.map { source ->
            val label = label(source)
            ServerStreamSource(label, preferred = false) {
                val own = matches.direct().filter { it.ref.sourceId == source.ref.sourceId }
                    .ifEmpty { matches.titled().filter { it.ref.sourceId == source.ref.sourceId } }
                    .take(VodStreams.MAX_TITLES_PER_SOURCE)
                if (own.isEmpty()) emptyList() else streams.items(source, request, own).map { stream(it, label) }
            }
        }
    }

    suspend fun sourceNames(type: String, videoId: String, season: Int?, episode: Int?): List<String> =
        sources(type, videoId, season, episode).map { it.name }

    private fun label(source: IptvSource): String = context.getString(R.string.iptv_vod_stream_source, source.label)

    private fun stream(item: IptvVodStreamItem, label: String): Stream = Stream(
        name = label,
        title = item.text.title,
        description = listOfNotNull(item.text.title, item.text.description).joinToString("\n"),
        url = item.ref.format(),
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = "iptv-vod-${item.ref.sourceId}",
            countryWhitelist = null,
            proxyHeaders = null,
            filename = item.text.filename
        ),
        addonName = label,
        addonLogo = null
    )

    private inner class Matches(private val profileId: Int, private val type: String, private val request: VodStreamRequest) {
        private val lock = Mutex()
        private var tmdbId: String? = null
        private var directHits: List<IptvVodTitle>? = null
        private var titleHits: List<IptvVodTitle>? = null

        suspend fun direct(): List<IptvVodTitle> = lock.withLock {
            directHits ?: run {
                val tmdbValue = request.tmdbId ?: request.imdbId?.let { quiet { tmdb.ensureTmdbId(it, type) } }
                val imdbValue = request.imdbId ?: request.tmdbId?.toIntOrNull()?.let { quiet { tmdb.tmdbToImdb(it, type) } }
                tmdbId = tmdbValue
                withContext(Dispatchers.IO) { streams.direct(profileId, request.kind, tmdbValue, imdbValue) }.also { directHits = it }
            }
        }

        suspend fun titled(): List<IptvVodTitle> {
            direct()
            return lock.withLock {
                titleHits ?: run {
                    val id = tmdbId
                    val names = id?.toIntOrNull()?.let { tmdbTitles(request.kind, it) }
                    if (names == null) emptyList()
                    else withContext(Dispatchers.IO) { streams.byTitles(profileId, request.kind, names.titles, names.year, id) }
                }.also { titleHits = it }
            }
        }
    }

    private suspend fun tmdbTitles(kind: VodKind, id: Int): TmdbTitles? {
        val key = "${kind.wire}:$id"
        synchronized(titleCache) { titleCache[key] }?.let { return it }
        val details = quiet {
            val response = if (kind == VodKind.MOVIE) tmdbApi.getMovieDetails(id, tmdb.apiKey()) else tmdbApi.getTvDetails(id, tmdb.apiKey())
            response.body()?.takeIf { response.isSuccessful }
        } ?: return null
        val titles = if (kind == VodKind.MOVIE) listOfNotNull(details.title, details.originalTitle) else listOfNotNull(details.name, details.originalName)
        val date = if (kind == VodKind.MOVIE) details.releaseDate else details.firstAirDate
        val result = TmdbTitles(titles.filter { it.isNotBlank() }.distinct(), date?.take(4)?.toIntOrNull())
        if (result.titles.isEmpty()) return null
        synchronized(titleCache) { titleCache[key] = result }
        return result
    }

    private suspend fun <T> quiet(block: suspend () -> T?): T? = try { block() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }

    private suspend fun <T> io(fallback: T, block: () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            IptvLog.failure("vod streams", error)
            fallback
        }
    }
}
