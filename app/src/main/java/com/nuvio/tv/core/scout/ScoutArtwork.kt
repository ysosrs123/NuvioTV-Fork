package com.nuvio.tv.core.scout

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.core.poster.patternForScreen
import com.nuvio.tv.core.poster.withCustomPosterUrl
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val LOOKUPS_AT_ONCE = 4
private const val TITLE_BUDGET_MS = 3_000L
private const val ID_BUDGET_MS = 1_500L

/** Scout titles start with plain TMDB art. This makes them show what the user chose everywhere else. */
@Singleton
class ScoutArtwork @Inject constructor(
    private val tmdbService: TmdbService,
    private val metadata: TmdbMetadataService,
    private val tmdbSettings: TmdbSettingsDataStore,
    private val layout: LayoutPreferenceDataStore,
    private val metaRepository: dagger.Lazy<MetaRepository>
) {
    /** Full follow for a short list: add-on art, then the custom poster address for [screen]. */
    suspend fun follow(items: List<MetaPreview>, screen: CustomPosterScreen): List<MetaPreview> {
        val pattern = patternForScreen(
            layout.customPosterUrlPattern.first(),
            screen,
            layout.customPosterEnabledScreens.first()
        )
        val settings = tmdbSettings.settings.first()
        return eachAtOnce(items) { item ->
            withTimeoutOrNull(TITLE_BUDGET_MS) {
                val imdb = imdbId(item)
                val addon = if (settings.enabled && settings.useArtwork) null else imdb?.let { addonMeta(item, it) }
                val extra = extras(item, settings.language)
                item.withChosenArt(
                    imdb = imdb,
                    poster = addon?.poster,
                    background = addon?.background ?: extra?.backdrop,
                    logo = addon?.logo ?: extra?.logo,
                    runtime = extra?.runtimeMinutes?.toString(),
                    genres = extra?.genres.orEmpty()
                ).withCustomPosterUrl(pattern)
            } ?: item.withCustomPosterUrl(pattern)
        }
    }

    /** For long rows: only the custom poster address, so a Home row never makes a call per title to an add-on. */
    suspend fun withPattern(items: List<MetaPreview>, pattern: String): List<MetaPreview> {
        if (pattern.isBlank()) return items
        return eachAtOnce(items) { item ->
            val imdb = withTimeoutOrNull(ID_BUDGET_MS) { imdbId(item) }
            item.copy(imdbId = imdb ?: item.imdbId).withCustomPosterUrl(pattern)
        }
    }

    private suspend fun eachAtOnce(items: List<MetaPreview>, work: suspend (MetaPreview) -> MetaPreview): List<MetaPreview> =
        coroutineScope {
            val lanes = Semaphore(LOOKUPS_AT_ONCE)
            items.map { item ->
                async {
                    lanes.withPermit {
                        try {
                            work(item)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            item
                        }
                    }
                }
            }.awaitAll()
        }

    private suspend fun imdbId(item: MetaPreview): String? =
        item.imdbId ?: item.tmdbNumber()?.let { tmdbService.tmdbToImdb(it, item.apiType) }

    private suspend fun addonMeta(item: MetaPreview, imdb: String): Meta? =
        (metaRepository.get().getMetaFromPrimaryAddon(item.apiType, imdb)
            .first { it !is NetworkResult.Loading } as? NetworkResult.Success)?.data

    private suspend fun extras(item: MetaPreview, language: String) =
        item.tmdbNumber()?.let { metadata.fetchEnrichment(it.toString(), item.type, language) }

    private fun MetaPreview.tmdbNumber(): Int? = id.removePrefix("tmdb:").takeIf { id.startsWith("tmdb:") }?.toIntOrNull()
}

internal fun MetaPreview.withChosenArt(
    imdb: String?,
    poster: String?,
    background: String?,
    logo: String?,
    runtime: String? = null,
    genres: List<String> = emptyList()
): MetaPreview = copy(
    imdbId = imdb ?: imdbId,
    poster = poster?.takeIf(String::isNotBlank) ?: this.poster,
    background = background?.takeIf(String::isNotBlank) ?: this.background,
    logo = logo?.takeIf(String::isNotBlank) ?: this.logo,
    runtime = runtime ?: this.runtime,
    genres = genres.ifEmpty { this.genres }
)
