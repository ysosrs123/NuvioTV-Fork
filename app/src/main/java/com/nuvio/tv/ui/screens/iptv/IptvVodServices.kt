package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import com.nuvio.tv.core.iptv.VodArt
import com.nuvio.tv.core.iptv.VodArtCandidate
import com.nuvio.tv.core.iptv.VodArtwork
import com.nuvio.tv.core.iptv.VodKind
import com.nuvio.tv.core.iptv.VodRef
import com.nuvio.tv.core.iptv.VodTitles
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbImageSizes
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvVodArtLookup
import com.nuvio.tv.data.iptv.IptvVodArtRateLimited
import com.nuvio.tv.data.iptv.IptvVodArtwork
import com.nuvio.tv.data.iptv.IptvVodArtworkPreferences
import com.nuvio.tv.data.iptv.IptvVodRepository
import com.nuvio.tv.data.remote.api.TmdbApi
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
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
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
