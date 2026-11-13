package com.nuvio.tv.core.scout

import android.content.Context
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.R
import com.nuvio.tv.core.catalog.BuiltInCatalogProvider
import com.nuvio.tv.core.image.ScoutMixArt
import com.nuvio.tv.core.util.withAppLocale
import com.nuvio.tv.data.local.ScoutMixStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.ScoutMix
import com.nuvio.tv.domain.model.ScoutMixTile
import com.nuvio.tv.domain.model.ScoutMixes
import com.nuvio.tv.ui.screens.calendar.calendarNameList
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScoutMixCatalogProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ScoutMixStore,
    private val ready: ScoutReadyMixBuilder
) : BuiltInCatalogProvider {
    override val addons: Flow<List<Addon>> = flowOf(emptyList())

    override val collections: Flow<List<Collection>> = if (!ScoutMixes.available) flowOf(emptyList()) else
        combine(store.mixes, LocaleCache.tags) { mixes, _ ->
            if (mixes.isEmpty()) emptyList() else {
                val local = context.withAppLocale()
                val own = scoutMixCollection(local.getString(R.string.scout_mixes_title), mixes, homeWords(local))
                listOf(own.copy(folders = own.folders + readyFolders(local, mixes)))
            }
        }.distinctUntilChanged()

    private fun homeWords(local: Context) = ScoutMixHomeWords(
        kind = local.getString(R.string.scout_mixes_kind),
        shows = { local.resources.getQuantityString(R.plurals.scout_mixes_shows, it, it) },
        about = { names ->
            val list = calendarNameList(names, local.resources.configuration.locales[0]) { it.joinToString(", ") }
            local.getString(R.string.scout_mixes_about, list)
        }
    )

    /** The occasion in season, if any, then the way to every ready-made mix. */
    private suspend fun readyFolders(local: Context, mixes: List<ScoutMix>): List<CollectionFolder> = buildList {
        val kind = local.getString(R.string.scout_premix_kind)
        ScoutReadyMixes.inSeason(java.time.LocalDate.now())?.let { season ->
            val occasion = season.occasion ?: return@let
            val episodes = runCatching { ready.occasionEpisodes(occasion) }.getOrDefault(emptyList())
            if (episodes.size < SCOUT_OCCASION_FEWEST) return@let
            val shows = episodes.distinctBy { it.imdb }.shuffled(kotlin.random.Random(scoutReadyWeekSeed()))
            if (shows.isEmpty()) return@let
            val lead = shows.firstNotNullOfOrNull { it.backdrop }
            val posters = shows.mapNotNull { it.poster }
            val deck = ScoutMixArt.deck(lead, posters, mirrored = false)
            val names = shows.take(HERO_LOGOS).map { it.name }
            add(
                CollectionFolder(
                    id = season.id,
                    title = local.getString(season.nameRes),
                    coverImageUrl = deck,
                    heroBackdropUrl = ScoutMixArt.hero(shows.mapNotNull { it.backdrop }),
                    tileShape = PosterShape.LANDSCAPE,
                    scoutMix = ScoutMixTile(
                        kind = kind,
                        shows = local.getString(R.string.scout_premix_in_season),
                        about = local.getString(R.string.scout_premix_desc_occasion, calendarNameList(names, local.resources.configuration.locales[0]) { it.joinToString(", ") }),
                        genre = null,
                        logos = emptyList(),
                        deck = deck,
                        deckMirrored = ScoutMixArt.deck(lead, posters, mirrored = true)
                    )
                )
            )
        }
        val shows = mixes.flatMap { it.shows }
        val lead = shows.firstNotNullOfOrNull { it.backdrop ?: it.poster }
        val posters = shows.mapNotNull { it.poster ?: it.backdrop }.distinct()
        val deck = ScoutMixArt.deck(lead, posters, mirrored = false)
        add(
            CollectionFolder(
                id = FOLDER_MORE,
                title = local.getString(R.string.scout_premix_more),
                coverImageUrl = deck,
                heroBackdropUrl = ScoutMixArt.hero(shows.mapNotNull { it.backdrop ?: it.poster }),
                tileShape = PosterShape.LANDSCAPE,
                scoutMix = ScoutMixTile(
                    kind = kind,
                    shows = "",
                    about = local.getString(R.string.scout_premix_more_about),
                    genre = null,
                    logos = emptyList(),
                    deck = deck,
                    deckMirrored = ScoutMixArt.deck(lead, posters, mirrored = true)
                )
            )
        )
    }

    override val defaultFirstKeys: List<String>
        get() = if (ScoutMixes.available) listOf("collection_$COLLECTION_ID") else emptyList()

    override val defaultFirstRank: Int get() = 0

    override fun handles(baseUrl: String?): Boolean = false

    override fun ownsAddon(addonId: String?): Boolean = false

    override suspend fun catalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        extraArgs: Map<String, String>,
        posterPattern: String
    ): CatalogRow = CatalogRow(
        addonId = addonId,
        addonName = addonName,
        addonBaseUrl = addonBaseUrl,
        catalogId = catalogId,
        catalogName = catalogName,
        type = ContentType.UNKNOWN,
        rawType = type,
        items = emptyList(),
        hasMore = false,
        supportsSkip = false
    )

    companion object {
        const val COLLECTION_ID = "scout_mixes"
        const val FOLDER_MORE = "ready_more"
    }
}

/** The words a mix tile and its hero need, in the app's language. */
internal class ScoutMixHomeWords(
    val kind: String,
    val shows: (Int) -> String,
    val about: (List<String>) -> String
)

private const val HERO_LOGOS = 4

internal fun scoutMixCollection(title: String, mixes: List<ScoutMix>, words: ScoutMixHomeWords) = Collection(
    id = ScoutMixCatalogProvider.COLLECTION_ID,
    title = title,
    showAllTab = false,
    folders = mixes.map { mix ->
        val lead = mix.shows.firstNotNullOfOrNull { it.backdrop ?: it.poster }
        val posters = mix.shows.mapNotNull { it.poster ?: it.backdrop }
        val deck = ScoutMixArt.deck(lead, posters, mirrored = false)
        val logos = mix.shows.take(HERO_LOGOS).map { it.logo }
        CollectionFolder(
            id = mix.id,
            title = mix.name,
            coverImageUrl = deck,
            heroBackdropUrl = ScoutMixArt.hero(mix.shows.mapNotNull { it.backdrop ?: it.poster }),
            tileShape = PosterShape.LANDSCAPE,
            scoutMix = ScoutMixTile(
                kind = words.kind,
                shows = words.shows(mix.shows.size),
                about = if (mix.shows.isEmpty()) "" else words.about(mix.shows.map { it.name }),
                genre = mix.shows.flatMap { it.genres }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
                logos = if (logos.isNotEmpty() && logos.all { !it.isNullOrBlank() }) logos.filterNotNull() else emptyList(),
                deck = deck,
                deckMirrored = ScoutMixArt.deck(lead, posters, mirrored = true)
            )
        )
    }
)

@Module
@InstallIn(SingletonComponent::class)
abstract class ScoutMixCatalogModule {
    @Binds
    @IntoSet
    abstract fun provider(impl: ScoutMixCatalogProvider): BuiltInCatalogProvider
}
