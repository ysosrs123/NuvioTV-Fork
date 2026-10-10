package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import com.nuvio.tv.core.iptv.VodArt
import com.nuvio.tv.core.iptv.VodArtCandidate
import com.nuvio.tv.core.iptv.VodArtwork
import com.nuvio.tv.core.iptv.VodDetailRoute
import com.nuvio.tv.core.iptv.VodKind
import com.nuvio.tv.core.iptv.VodRef
import com.nuvio.tv.core.iptv.VodStreams
import com.nuvio.tv.core.iptv.VodTitles
import com.nuvio.tv.core.iptv.XtreamVodParser
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbImageSizes
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvVodArtLookup
import com.nuvio.tv.data.iptv.IptvVodArtRateLimited
import com.nuvio.tv.data.iptv.IptvVodArtwork
import com.nuvio.tv.data.iptv.IptvVodArtworkMode
import com.nuvio.tv.data.iptv.IptvVodArtworkPreferences
import com.nuvio.tv.data.iptv.IptvVodRepository
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.MetaRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query

internal interface IptvVodTmdbSearchApi {
    @GET("search/movie")
    suspend fun movies(@Query("api_key") apiKey: String, @Query("query") query: String, @Query("year") year: Int,
        @Query("language") language: String, @Query("include_adult") adult: Boolean = false): Response<ResponseBody>

    @GET("search/tv")
    suspend fun shows(@Query("api_key") apiKey: String, @Query("query") query: String, @Query("first_air_date_year") year: Int,
        @Query("language") language: String, @Query("include_adult") adult: Boolean = false): Response<ResponseBody>
}

internal class IptvVodTmdbLookup(private val api: TmdbApi, private val search: IptvVodTmdbSearchApi, private val tmdb: TmdbService,
    private val meta: MetaRepository) : IptvVodArtLookup {
    override suspend fun details(kind: VodKind, tmdbId: String, language: String): VodArt? {
        val key = tmdb.apiKey().takeIf { it.isNotBlank() } ?: return null
        val id = tmdbId.toIntOrNull() ?: return null
        val response = if (kind == VodKind.SERIES) api.getTvDetails(id, key, language) else api.getMovieDetails(id, key, language)
        limited(response.code(), response.headers()["Retry-After"])
        if (response.code() == 404) return null
        if (!response.isSuccessful) throw java.io.IOException("TMDB status ${response.code()}")
        val details = response.body() ?: return null
        val date = if (kind == VodKind.SERIES) details.firstAirDate else details.releaseDate
        return VodArt(tmdbId, null, (if (kind == VodKind.SERIES) details.name else details.title)?.takeIf { it.isNotBlank() },
            image(details.posterPath, "w500"), image(details.backdropPath, TmdbImageSizes.backdrop), details.overview?.takeIf { it.isNotBlank() }?.take(4000),
            VodTitles.yearOf(date), details.voteAverage?.takeIf { it.isFinite() && it > 0 && it <= 10 })
    }

    override suspend fun tmdbId(kind: VodKind, imdbId: String): String? =
        tmdb.imdbToTmdb(imdbId, if (kind == VodKind.SERIES) "series" else "movie")?.toString()

    override suspend fun search(kind: VodKind, title: String, year: Int, language: String): List<VodArtCandidate> {
        val key = tmdb.apiKey().takeIf { it.isNotBlank() } ?: return emptyList()
        val query = VodTitles.parse(title, year).display.take(200)
        val response = if (kind == VodKind.SERIES) search.shows(key, query, year, language) else search.movies(key, query, year, language)
        limited(response.code(), response.headers()["Retry-After"])
        if (!response.isSuccessful) throw java.io.IOException("TMDB status ${response.code()}")
        val text = response.body()?.use { body -> if (body.contentLength() > MAX_BODY) null else body.string() } ?: return emptyList()
        val results = JSONObject(text).optJSONArray("results") ?: return emptyList()
        return (0 until minOf(results.length(), 20)).mapNotNull { index ->
            val row = results.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optLong("id", 0L).takeIf { it > 0 }?.toString() ?: return@mapNotNull null
            val name = row.optString(if (kind == VodKind.SERIES) "name" else "title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val original = row.optString(if (kind == VodKind.SERIES) "original_name" else "original_title").takeIf { it.isNotBlank() }
            VodArtCandidate(id, name, original, VodTitles.yearOf(row.optString(if (kind == VodKind.SERIES) "first_air_date" else "release_date")))
        }
    }

    override suspend fun addon(kind: VodKind, imdbId: String): VodArt? {
        val type = if (kind == VodKind.SERIES) "series" else "movie"
        val result = withTimeoutOrNull(ADDON_TIMEOUT) { meta.getMetaFromAllAddons(type, imdbId).first { it !is NetworkResult.Loading } }
        val found = (result as? NetworkResult.Success)?.data ?: return null
        return VodArt(null, imdbId, found.name.takeIf { it.isNotBlank() }, VodArtwork.imageUrl(found.poster), VodArtwork.imageUrl(found.background),
            found.description?.takeIf { it.isNotBlank() }?.take(4000), VodTitles.yearOf(found.releaseInfo),
            found.imdbRating?.toDouble()?.takeIf { it.isFinite() && it > 0 && it <= 10 })
    }

    private fun limited(code: Int, retryAfter: String?) {
        if (code == 429) throw IptvVodArtRateLimited((retryAfter?.trim()?.toLongOrNull() ?: 10L) * 1000)
    }

    private fun image(path: String?, size: String): String? = path?.trim()?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/$size$it" }

    private companion object {
        const val MAX_BODY = 512L * 1024
        const val ADDON_TIMEOUT = 8_000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
object IptvVodBrowseModule {
    @Provides @Singleton fun vodArtworkPreferences(@ApplicationContext context: Context) = IptvVodArtworkPreferences(context)

    @Provides @Singleton fun vodArtwork(@ApplicationContext context: Context, api: TmdbApi, @Named("tmdb") retrofit: Retrofit, tmdb: TmdbService,
        meta: MetaRepository): IptvVodArtwork =
        IptvVodArtwork(File(context.cacheDir, "iptv-vod-art"), IptvVodTmdbLookup(api, retrofit.create(IptvVodTmdbSearchApi::class.java), tmdb, meta))
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface IptvVodResumeEntryPoint {
    fun iptvVodRepository(): IptvVodRepository
}

object IptvVodPlayerResume {
    fun applies(contentId: String?, url: String?): Boolean = contentId.isNullOrEmpty() && VodRef.isVod(url)

    fun load(context: Context, url: String): WatchProgress? {
        val ref = VodRef.parse(url)?.takeIf { it.kind != VodKind.SERIES } ?: return null
        val saved = try { repository(context).resume(ref) } catch (error: Exception) { IptvLog.failure("vod resume load", error); null } ?: return null
        return WatchProgress(contentId = "", contentType = if (ref.kind == VodKind.EPISODE) "series" else "movie", name = "", poster = null, backdrop = null,
            logo = null, videoId = "", season = null, episode = null, episodeTitle = null, position = saved.positionMillis, duration = saved.durationMillis,
            lastWatched = saved.updatedAtMillis)
    }

    fun save(context: Context, url: String, positionMillis: Long, durationMillis: Long) {
        val ref = VodRef.parse(url)?.takeIf { it.kind != VodKind.SERIES } ?: return
        try { repository(context).saveResume(ref, positionMillis, durationMillis) } catch (error: Exception) { IptvLog.failure("vod resume save", error) }
    }

    private fun repository(context: Context): IptvVodRepository =
        EntryPointAccessors.fromApplication(context.applicationContext, IptvVodResumeEntryPoint::class.java).iptvVodRepository()
}

@Singleton
class IptvVodMetaSource @Inject constructor(private val repository: IptvVodRepository, private val artwork: IptvVodArtwork,
    private val preferences: IptvVodArtworkPreferences, private val tmdb: TmdbService, private val tmdbMetadata: TmdbMetadataService,
    private val tmdbSettings: TmdbSettingsDataStore) {
    private val lock = Mutex()
    private val cache = object : LinkedHashMap<String, Pair<Long, Meta>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Meta>>?): Boolean = size > MAX_CACHED
    }

    fun cached(id: String): Meta? = synchronized(cache) { cache[id]?.takeIf { System.currentTimeMillis() - it.first < TTL }?.second }

    suspend fun meta(id: String): Meta? = cached(id) ?: lock.withLock {
        cached(id) ?: build(id)?.also { meta -> synchronized(cache) { cache[id] = System.currentTimeMillis() to meta } }
    }

    private suspend fun build(id: String): Meta? {
        val ref = VodDetailRoute.ownTitle(id) ?: return null
        val type = VodDetailRoute.type(ref.kind) ?: return null
        val title = withContext(Dispatchers.IO) { repository.title(ref) } ?: return null
        val series = ref.kind == VodKind.SERIES
        val language = tmdbSettings.settings.value.language.ifBlank { "en" }
        val movie = if (series) null else quiet(INFO_TIMEOUT) { repository.movieInfo(ref) }
        val episodes = if (series) (quiet(INFO_TIMEOUT) { repository.episodes(ref) } ?: return null) else emptyList()
        val details = if (series) quiet(INFO_TIMEOUT) { repository.seriesDetails(ref) } else movie?.details
        val saved = withContext(Dispatchers.IO) { repository.title(ref) } ?: title
        val key = tmdb.apiKey().isNotBlank()
        val art = if (key && preferences.mode == IptvVodArtworkMode.NUVIO)
            artwork.cached(saved, language) ?: quiet(TMDB_TIMEOUT) { artwork.resolve(listOf(saved), language)[ref] } else null
        val imdbId = XtreamVodParser.imdbId(saved.imdbId ?: movie?.imdbId) ?: XtreamVodParser.imdbId(art?.imdbId)
        val tmdbId = XtreamVodParser.tmdbId(saved.tmdbId ?: movie?.tmdbId) ?: XtreamVodParser.tmdbId(art?.tmdbId)
            ?: imdbId?.takeIf { key }?.let { quiet(TMDB_TIMEOUT) { tmdb.imdbToTmdb(it, type)?.toString() } }
        val contentType = if (series) ContentType.SERIES else ContentType.MOVIE
        val extra = if (key && tmdbId != null) quiet(TMDB_TIMEOUT) { tmdbMetadata.fetchEnrichment(tmdbId, contentType, language) } else null
        val seasons = episodes.map { it.season }.distinct()
        val episodeExtra = if (extra != null && tmdbId != null && seasons.isNotEmpty())
            quiet(TMDB_TIMEOUT) { tmdbMetadata.fetchEpisodeEnrichment(tmdbId, seasons, language) }.orEmpty() else emptyMap()
        val videos = episodes.map { episode ->
            val more = episodeExtra[episode.season to episode.episode]
            Video(id = episode.ref.format(), title = more?.title?.takeIf { it.isNotBlank() } ?: episode.title?.takeIf { it.isNotBlank() }
                ?: VodStreams.episodeCode(episode.season, episode.episode), released = null, thumbnail = episode.still ?: more?.thumbnail,
                season = episode.season, episode = episode.episode, overview = episode.plot ?: more?.overview,
                runtime = VodDetailRoute.minutes(episode.durationSeconds) ?: more?.runtimeMinutes, available = true)
        }
        val year = saved.year ?: details?.year ?: movie?.year ?: art?.year
        val cast = extra?.castMembers?.takeIf { it.isNotEmpty() } ?: VodDetailRoute.names(details?.cast).map { MetaCastMember(it) }
        IptvLog.info("vod meta kind=${ref.kind.wire} episodes=${videos.size} tmdb=${extra != null}")
        return Meta(id = ref.format(), type = contentType, rawType = type, name = extra?.localizedTitle ?: art?.title ?: saved.title,
            poster = extra?.poster ?: art?.poster ?: details?.poster ?: movie?.poster ?: saved.artwork, posterShape = PosterShape.POSTER,
            background = extra?.backdrop ?: art?.backdrop ?: details?.backdrop, logo = extra?.logo,
            description = extra?.description ?: art?.overview ?: movie?.plot ?: details?.plot, releaseInfo = extra?.releaseInfo ?: year?.toString(),
            status = extra?.status, imdbRating = VodDetailRoute.rating(extra?.rating ?: art?.rating ?: details?.rating ?: saved.rating),
            genres = extra?.genres?.takeIf { it.isNotEmpty() } ?: VodDetailRoute.names(details?.genre, 6),
            runtime = (extra?.runtimeMinutes ?: VodDetailRoute.minutes(movie?.durationSeconds ?: details?.durationSeconds))?.toString(),
            director = extra?.director?.takeIf { it.isNotEmpty() } ?: VodDetailRoute.names(details?.director, 6), writer = extra?.writer.orEmpty(),
            cast = cast.map { it.name }, castMembers = cast, videos = videos, productionCompanies = extra?.productionCompanies.orEmpty(),
            networks = extra?.networks.orEmpty(), ageRating = extra?.ageRating, country = extra?.countries?.joinToString(", "), awards = null,
            language = extra?.language, links = emptyList())
    }

    private suspend fun <T> quiet(timeout: Long, block: suspend () -> T?): T? = try { withTimeoutOrNull(timeout) { block() } }
        catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { IptvLog.failure("vod meta", error); null }

    private companion object {
        const val MAX_CACHED = 12
        const val TTL = 30L * 60 * 1000
        const val INFO_TIMEOUT = 20_000L
        const val TMDB_TIMEOUT = 8_000L
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface IptvVodMetaEntryPoint {
    fun iptvVodMetaSource(): IptvVodMetaSource
}

object IptvVodMeta {
    fun handles(id: String?): Boolean = VodDetailRoute.ownTitle(id) != null

    fun cached(context: Context, id: String): Meta? = try { source(context).cached(id) } catch (error: Exception) { IptvLog.failure("vod meta cache", error); null }

    fun flow(context: Context, id: String): Flow<NetworkResult<Meta>> = flow {
        val source = source(context)
        source.cached(id)?.let { emit(NetworkResult.Success(it)); return@flow }
        emit(NetworkResult.Loading)
        val meta = try { source.meta(id) } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { IptvLog.failure("vod meta", error); null }
        emit(if (meta != null) NetworkResult.Success(meta) else NetworkResult.Error(context.getString(com.nuvio.tv.R.string.error_meta_not_found), NetworkResult.META_NOT_FOUND_CODE))
    }

    private fun source(context: Context): IptvVodMetaSource =
        EntryPointAccessors.fromApplication(context.applicationContext, IptvVodMetaEntryPoint::class.java).iptvVodMetaSource()
}
