package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.PlaylistVod
import com.nuvio.tv.core.iptv.PlaylistVodEntry
import com.nuvio.tv.core.iptv.VodKind
import com.nuvio.tv.core.iptv.VodRef
import com.nuvio.tv.core.iptv.VodTitleCandidate
import com.nuvio.tv.core.iptv.VodTitles
import com.nuvio.tv.core.iptv.XtreamVodParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface IptvVodRefresh {
    data object Disabled : IptvVodRefresh
    data object Unsupported : IptvVodRefresh
    data object KeptPrevious : IptvVodRefresh
    data class Saved(val movies: Int, val series: Int, val published: Boolean) : IptvVodRefresh
}

data class IptvVodPlayback(val ref: VodRef, val url: String, val headers: Map<String, String>) {
    override fun toString(): String = "IptvVodPlayback(ref=$ref)"
}

class IptvVodRepository(
    private val store: IptvVodStore,
    private val catalogue: IptvCatalogueStore,
    private val xtream: IptvXtreamClient = IptvXtreamClient(),
    private val now: () -> Long = System::currentTimeMillis,
    private val episodeMaxAge: Long = DAY,
) {
    private val fetches = Mutex()

    fun enabled(source: IptvSource): Boolean = enabled(source.kind, store.state(source.ref))

    fun setEnabled(ref: IptvSourceRef, enabled: Boolean) = store.setEnabled(ref, enabled)

    fun detected(ref: IptvSourceRef): Boolean = store.state(ref)?.detected == true

    suspend fun refresh(ref: IptvSourceRef): IptvVodRefresh = withContext(Dispatchers.IO) {
        val source = catalogue.sources(ref.profileId).firstOrNull { it.ref == ref } ?: return@withContext IptvVodRefresh.Disabled
        if (source.kind != IptvSourceKind.XTREAM) return@withContext IptvVodRefresh.Unsupported
        val previous = store.state(ref)
        if (!enabled(source.kind, previous)) return@withContext IptvVodRefresh.Disabled
        val connection = catalogue.connection(ref)
        val context = currentCoroutineContext()
        val started = now()
        val import = store.beginImport(ref)
        var published = false
        try {
            import.categories(xtream.vodCategories(connection, VodKind.MOVIE))
            context.ensureActive()
            import.categories(xtream.vodCategories(connection, VodKind.SERIES))
            context.ensureActive()
            val movies = xtream.movies(connection) { import.movie(it) }
            context.ensureActive()
            val series = xtream.series(connection) { import.series(it) }
            context.ensureActive()
            IptvLog.info("vod download movies=${movies.accepted} series=${series.accepted} skipped=${movies.invalid + series.invalid} ms=${now() - started}")
            if (movies.accepted + series.accepted == 0) {
                if (movies.invalid + series.invalid > 0) throw MetadataException(MetadataFailure.INVALID_RESPONSE)
                if ((previous?.movies ?: 0) + (previous?.series ?: 0) > 0) return@withContext IptvVodRefresh.KeptPrevious
            }
            published = import.publish()
            IptvVodRefresh.Saved(movies.accepted, series.accepted, published)
        } finally {
            if (!published) runCatching { import.discard() }.onFailure { IptvLog.failure("vod discard", it) }
        }
    }

    fun savePlaylist(ref: IptvSourceRef, entries: List<PlaylistVodEntry>): IptvVodRefresh {
        val state = store.state(ref)
        val detected = entries.isNotEmpty()
        if (state == null && !detected) return IptvVodRefresh.Disabled
        if (!(state?.enabled ?: detected)) {
            store.setDetected(ref, detected)
            return IptvVodRefresh.Disabled
        }
        val vod = PlaylistVod.catalogue(entries)
        val import = store.beginImport(ref)
        var published = false
        try {
            import.categories(vod.categories)
            for (row in vod.movies) import.movie(row.movie, IptvVodLocator(row.locator, row.headers))
            for (row in vod.series) import.series(row)
            for (row in vod.episodes) import.episode(row.seriesId, row.episode, IptvVodLocator(row.locator, row.headers))
            published = import.publish(detected)
            return IptvVodRefresh.Saved(vod.movies.size, vod.series.size, published)
        } finally {
            if (!published) runCatching { import.discard() }.onFailure { IptvLog.failure("vod discard", it) }
        }
    }

    fun byTmdb(profileId: Int, kind: VodKind, tmdbId: String): List<IptvVodTitle> =
        XtreamVodParser.tmdbId(tmdbId)?.let { ordered(profileId, store.byTmdb(profileId, kind, it)) }.orEmpty()

    fun byImdb(profileId: Int, kind: VodKind, imdbId: String): List<IptvVodTitle> =
        XtreamVodParser.imdbId(imdbId)?.let { ordered(profileId, store.byImdb(profileId, kind, it)) }.orEmpty()

    fun byTitle(profileId: Int, kind: VodKind, title: String, year: Int?, tmdbId: String? = null): List<IptvVodTitle> {
        val key = VodTitles.parse(title).matchKey
        if (key.isEmpty()) return emptyList()
        val positions = sourcePositions(profileId)
        val candidates = store.byMatchKey(profileId, kind, key).mapNotNull { item ->
            positions[item.ref.sourceId]?.let { VodTitleCandidate(item, item.name, item.year, item.tmdbId, it) }
        }
        return VodTitles.rank(title, year, candidates, XtreamVodParser.tmdbId(tmdbId))
    }

    fun find(profileId: Int, kind: VodKind, tmdbId: String?, imdbId: String?, title: String?, year: Int?): List<IptvVodTitle> {
        val direct = (tmdbId?.let { byTmdb(profileId, kind, it) }.orEmpty() + imdbId?.let { byImdb(profileId, kind, it) }.orEmpty()).distinctBy { it.ref }
        if (direct.isNotEmpty() || title.isNullOrBlank()) return direct
        return byTitle(profileId, kind, title, year, tmdbId)
    }

    fun title(ref: VodRef): IptvVodTitle? = store.title(ref)

    fun categories(ref: IptvSourceRef, kind: VodKind): List<IptvVodCategory> = store.categories(ref, kind)

    fun page(ref: IptvSourceRef, kind: VodKind, categoryId: String?, offset: Int = 0, limit: Int = 100): IptvVodPage =
        store.page(ref, kind, categoryId, offset, limit)

    fun search(profileId: Int, kind: VodKind?, text: String, limit: Int = 100): List<IptvVodTitle> =
        ordered(profileId, store.search(profileId, kind, text, limit), keepOrder = true)

    suspend fun episodes(series: VodRef): List<IptvVodEpisode> = withContext(Dispatchers.IO) {
        require(series.kind == VodKind.SERIES)
        val cached = store.episodes(series)
        val source = source(series) ?: return@withContext emptyList()
        if (source.kind != IptvSourceKind.XTREAM || fresh(cached)) return@withContext cached.episodes
        fetches.withLock {
            val again = store.episodes(series)
            if (fresh(again)) return@withLock again.episodes
            try {
                store.title(series) ?: return@withLock again.episodes
                val info = xtream.seriesInfo(catalogue.connection(source.ref), series.id)
                IptvLog.info("vod episodes count=${info.episodes.size} skipped=${info.invalidRows}")
                store.saveEpisodes(series, info.episodes, info.series)
                store.episodes(series).episodes
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (again.episodes.isEmpty()) throw error
                IptvLog.failure("vod episodes", error)
                again.episodes
            }
        }
    }

    suspend fun episode(series: VodRef, season: Int, episode: Int): IptvVodEpisode? =
        episodes(series).firstOrNull { it.season == season && it.episode == episode }

    suspend fun playback(ref: VodRef): IptvVodPlayback = withContext(Dispatchers.IO) {
        require(ref.kind != VodKind.SERIES)
        val source = source(ref) ?: throw MetadataException(MetadataFailure.INVALID_ADDRESS)
        when (source.kind) {
            IptvSourceKind.XTREAM -> {
                val connection = catalogue.connection(source.ref)
                val url = if (ref.kind == VodKind.MOVIE) {
                    val title = store.title(ref) ?: throw MetadataException(MetadataFailure.INVALID_ADDRESS)
                    IptvXtreamClient.movieUrl(connection, ref.id, title.extension ?: DEFAULT_EXTENSION)
                } else {
                    val episode = store.episode(ref) ?: episodes(requireNotNull(ref.series)).firstOrNull { it.ref == ref }
                        ?: throw MetadataException(MetadataFailure.INVALID_ADDRESS)
                    IptvXtreamClient.episodeUrl(connection, ref.itemId, episode.extension ?: DEFAULT_EXTENSION)
                }
                IptvVodPlayback(ref, url, emptyMap())
            }
            IptvSourceKind.M3U -> store.locator(ref)?.let { IptvVodPlayback(ref, it.url, it.headers) }
                ?: throw MetadataException(MetadataFailure.INVALID_ADDRESS)
            IptvSourceKind.STALKER -> throw MetadataException(MetadataFailure.INVALID_ADDRESS)
        }
    }

    private fun fresh(episodes: IptvVodEpisodes): Boolean =
        episodes.fetchedAtMillis?.let { now() - it in 0 until episodeMaxAge } == true

    private fun source(ref: VodRef): IptvSource? =
        catalogue.sources(ref.profileId).firstOrNull { it.ref.sourceId == ref.sourceId }

    private fun sourcePositions(profileId: Int): Map<String, Int> =
        catalogue.sources(profileId).withIndex().associate { (index, source) -> source.ref.sourceId to index }

    private fun ordered(profileId: Int, items: List<IptvVodTitle>, keepOrder: Boolean = false): List<IptvVodTitle> {
        val positions = sourcePositions(profileId)
        val known = items.filter { it.ref.sourceId in positions }
        return if (keepOrder) known else known.sortedBy { positions.getValue(it.ref.sourceId) }
    }

    companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        private const val DEFAULT_EXTENSION = "mp4"

        fun enabled(kind: IptvSourceKind, state: IptvVodSourceState?): Boolean = state?.enabled ?: when (kind) {
            IptvSourceKind.XTREAM -> true
            IptvSourceKind.M3U -> state?.detected == true
            IptvSourceKind.STALKER -> false
        }
    }
}
