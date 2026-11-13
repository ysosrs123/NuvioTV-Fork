package com.nuvio.tv.core.scout

import com.nuvio.tv.LocaleCache
import com.nuvio.tv.core.util.withAppLocale
import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.catalog.BuiltInCatalogProvider
import com.nuvio.tv.core.catalog.BuiltInCatalogs
import com.nuvio.tv.data.local.ScoutStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.ScoutFeature
import com.nuvio.tv.domain.model.ScoutPick
import com.nuvio.tv.domain.model.ScoutPool
import com.nuvio.tv.domain.model.ScoutProfile
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSort
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import com.nuvio.tv.domain.model.catalogRowLegacyKey
import com.nuvio.tv.domain.model.scoutRotate
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val START_DELAY_MS = 8_000L
private const val ROTATE_MS = 6L * 60 * 60 * 1000
private const val PICKED_ITEMS = 40
private const val SHOWN_FIRST = 12
private const val ROW_TYPE = "scout"

@Singleton
class ScoutCatalogProvider @Inject constructor(
    private val artwork: ScoutArtwork,
    @ApplicationContext private val appContext: Context,
    private val engine: ScoutEngine,
    private val store: ScoutStore,
    private val resolver: com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver,
    private val folderSupport: ScoutFolderSupport,
    private val picksBuilder: ScoutPicksBuilder,
    private val ratingSync: dagger.Lazy<com.nuvio.tv.core.scout.sync.ScoutRatingSync>
) : BuiltInCatalogProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    internal val tileHeroes = MutableStateFlow<Map<String, String>>(emptyMap())
    @Volatile private var savedAnswers: List<String> = emptyList()
    @Volatile private var localized: Pair<String, Context>? = null

    private val context: Context
        get() {
            val tag = LocaleCache.localeTag
            localized?.takeIf { it.first == tag }?.let { return it.second }
            return appContext.withAppLocale().also { localized = tag to it }
        }

    override val addons: Flow<List<Addon>> = if (!ScoutFeature.available) flowOf(emptyList()) else
        combine(engine.pool, store.profile, LocaleCache.tags) { pool, profile, _ -> addonsFor(pool, profile) }
            .distinctUntilChanged()
            .onStart { startOnce() }

    override val collections: Flow<List<Collection>> = if (!ScoutFeature.available) flowOf(emptyList()) else
        combine(engine.pool, store.profile, LocaleCache.tags, tileHeroes) { pool, profile, _, _ -> tilesFor(pool, profile) }.distinctUntilChanged()

    override val rowChanges: Flow<Unit> = if (!ScoutFeature.available) kotlinx.coroutines.flow.emptyFlow() else
        merge(folderSupport.changes, engine.pool.drop(1).map { })

    override val defaultFirstKeys: List<String>
        get() = listOf(
            catalogRowLegacyKey(ADDON_ID, ROW_TYPE, CATALOG_PICKED),
            catalogRowLegacyKey(ADDON_ID, ROW_TYPE, CATALOG_NEW),
            catalogRowLegacyKey(ADDON_ID, ROW_TYPE, CATALOG_TRENDING),
            *slotKeys.toTypedArray(),
            "collection_$TILES_COLLECTION_ID",
            catalogRowLegacyKey(ADDON_ID, ROW_TYPE, CATALOG_BECAUSE + 0),
            catalogRowLegacyKey(ADDON_ID, ROW_TYPE, CATALOG_BECAUSE + 1),
            catalogRowLegacyKey(ADDON_ID, ROW_TYPE, CATALOG_GEMS)
        ) + (ScoutSlices.all.map { it.id } + savedAnswers).map { catalogRowLegacyKey(ADDON_ID, ROW_TYPE, CATALOG_SAVED + it) }

    private val slotKeys = List(SCOUT_ROWS_MAX) { catalogRowLegacyKey(ADDON_ID, ROW_TYPE, CATALOG_TASTE + scoutTasteSlotId(it)) }

    override fun anchorFor(key: String, saved: List<String>): String? {
        val slot = slotKeys.indexOf(key)
        if (slot < 0) return null
        slotKeys.take(slot).lastOrNull { it in saved }?.let { return it }
        val legacyPrefix = catalogRowLegacyKey(ADDON_ID, ROW_TYPE, CATALOG_TASTE)
        saved.firstOrNull { it.startsWith(legacyPrefix) && it !in slotKeys }?.let { return it }
        return listOf(CATALOG_PICKED, CATALOG_NEW, CATALOG_TRENDING)
            .map { catalogRowLegacyKey(ADDON_ID, ROW_TYPE, it) }
            .filter { it in saved }
            .maxByOrNull(saved::indexOf)
    }

    private fun startOnce() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            delay(START_DELAY_MS)
            engine.followProfiles()
        }
        scope.launch {
            delay(START_DELAY_MS)
            ratingSync.get().follow()
        }
        scope.launch {
            delay(START_DELAY_MS)
            combine(engine.pool.filterNotNull().map { it.builtAt }, store.profile.map { it.settings.tilesRow }) { builtAt, on -> builtAt to on }
                .distinctUntilChanged()
                .collectLatest { (builtAt, on) -> if (on) refreshTileHeroes(builtAt) else tileHeroes.value = emptyMap() }
        }
    }

    private fun heroFile() = File(appContext.filesDir, "scout/tile_heroes_${engine.activeProfile()}.json")

    /** One page per Home tile, after a rebuild only: the same pool on the same day reads what is saved. */
    private suspend fun refreshTileHeroes(builtAt: Long) {
        val file = heroFile()
        val saved = runCatching { JSONObject(file.readText()) }.getOrNull()
        val now = System.currentTimeMillis()
        if (saved != null && saved.optLong("builtAt") == builtAt && now - saved.optLong("at") < HERO_KEEP_MS) {
            val heroes = saved.getJSONObject("heroes")
            tileHeroes.value = heroes.keys().asSequence().associateWith { heroes.getString(it) }
            return
        }
        tileHeroes.value = emptyMap()
        delay(HERO_WAIT_MS)
        val found = ScoutSlices.homeTiles.mapNotNull(ScoutSlices::byId).mapNotNull { slice -> heroFor(slice)?.let { slice.id to it } }.toMap()
        tileHeroes.value = found
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(JSONObject().put("builtAt", builtAt).put("at", now).put("heroes", JSONObject(found)).toString())
        }
    }

    /** The backdrop of a title from the tile's own list, so the big picture always matches the tile. */
    internal suspend fun heroFor(slice: ScoutSlice): String? {
        val source = sourcesFor(slice).firstOrNull() ?: return null
        val result = resolver.resolve(source).first { it !is com.nuvio.tv.core.network.NetworkResult.Loading }
        val items = (result as? com.nuvio.tv.core.network.NetworkResult.Success)?.data?.items.orEmpty()
        return folderSupport.withoutSeen(items).firstNotNullOfOrNull { it.background }
    }

    override fun collectionForFolders(collectionId: String): Collection? =
        allSlicesCollection().takeIf { collectionId == TILES_COLLECTION_ID }

    override fun handles(baseUrl: String?): Boolean = baseUrl == BASE_URL

    override fun ownsAddon(addonId: String?): Boolean = addonId == ADDON_ID

    internal fun addonsFor(pool: ScoutPool?, profile: ScoutProfile): List<Addon> {
        val settings = profile.settings
        savedAnswers = settings.savedSlices.filter { ScoutAnswers.decode(it) != null }
        val catalogs = buildList {
            if (settings.pickedRow && !pool?.picked.isNullOrEmpty()) add(descriptor(CATALOG_PICKED, context.getString(R.string.scout_row_picked)))
            if (settings.pickedRow && !pool?.newForYou.isNullOrEmpty()) add(descriptor(CATALOG_NEW, context.getString(R.string.scout_picks_row_new)))
            if (settings.pickedRow && !pool?.trendingForYou.isNullOrEmpty()) add(descriptor(CATALOG_TRENDING, context.getString(R.string.scout_picks_row_trending)))
            if (settings.genreRows) pool?.tasteRows?.take(SCOUT_ROWS_MAX)?.forEachIndexed { index, row ->
                add(descriptor(CATALOG_TASTE + scoutTasteSlotId(index), tasteRowName(row)))
            }
            if (settings.becauseRow) pool?.because?.forEachIndexed { index, because ->
                add(descriptor(CATALOG_BECAUSE + index, context.getString(R.string.scout_row_because_title, because.seedName)))
            }
            if (settings.gemsRow && !pool?.gems.isNullOrEmpty()) add(descriptor(CATALOG_GEMS, context.getString(R.string.scout_row_gems)))
            settings.savedSlices.forEach { id ->
                ScoutSlices.byId(id)?.let { add(descriptor(CATALOG_SAVED + id, sliceTitle(it))) }
            }
        }
        if (catalogs.isEmpty()) return emptyList()
        return listOf(
            Addon(
                id = ADDON_ID,
                name = context.getString(R.string.scout_title),
                version = "1",
                description = null,
                logo = null,
                baseUrl = BASE_URL,
                catalogs = catalogs,
                types = listOf(ContentType.MOVIE, ContentType.SERIES),
                resources = listOf(AddonResource(name = "catalog", types = emptyList(), idPrefixes = null))
            )
        )
    }

    private fun descriptor(id: String, name: String) =
        CatalogDescriptor(type = ContentType.UNKNOWN, rawType = ROW_TYPE, id = id, name = name, showInHome = true)

    private val bundledArt: Set<String> by lazy {
        runCatching { appContext.assets.list(ART_FOLDER)?.toSet() }.getOrNull().orEmpty()
    }

    /** The picture shipped with the app for this tile, named as in the art brief. */
    fun bundledCover(slice: ScoutSlice): String? {
        val name = (if (slice.id == COMPLETE_SERIES) "length_$COMPLETE_SERIES" else slice.id) + ".webp"
        return if (name in bundledArt) "file:///android_asset/$ART_FOLDER/$name" else null
    }

    private fun tileCover(slice: ScoutSlice): String? = SCOUT_PINNED_ART[slice.id]?.let { TMDB_ORIGINAL + it } ?: bundledCover(slice)

    private fun sliceTitle(slice: ScoutSlice) = slice.title { res, args -> context.getString(res, *args) }

    internal fun tilesFor(pool: ScoutPool?, profile: ScoutProfile): List<Collection> {
        val folders = buildList {
            if (profile.settings.tilesRow) {
                add(actionFolder(FOLDER_SURPRISE, R.string.scout_tile_surprise, actionCover("surprise_me", "acclaim_crowd")))
                val art = (pool?.picked.orEmpty() + pool?.gems.orEmpty()).distinctBy { it.key }.drop(1)
                ScoutSlices.homeTiles.mapNotNull(ScoutSlices::byId).forEachIndexed { index, slice ->
                    add(folderFor(slice, tileCover(slice) ?: art.getOrNull(index)?.backdrop).copy(heroBackdropUrl = tileHeroes.value[slice.id]))
                }
                add(actionFolder(FOLDER_MORE, R.string.scout_tile_more, actionCover("more_filters", "acclaim_gems")))
            }
        }
        return if (folders.isEmpty()) emptyList() else listOf(collection(folders))
    }

    /** Every slice, so a tile on Home or the picker opens through the same folder page. */
    fun allSlicesCollection(): Collection = collection(ScoutSlices.all.map { folderFor(it, null) })

    private fun collection(folders: List<CollectionFolder>) = Collection(
        id = TILES_COLLECTION_ID,
        title = context.getString(R.string.scout_title),
        showAllTab = true,
        folders = folders
    )

    private fun actionCover(name: String, otherwise: String): String? =
        listOf(name, otherwise).firstOrNull { "$it.webp" in bundledArt }?.let { "file:///android_asset/$ART_FOLDER/$it.webp" }

    private fun actionFolder(id: String, titleRes: Int, cover: String?) = CollectionFolder(
        id = id,
        title = context.getString(titleRes),
        coverImageUrl = cover,
        tileShape = PosterShape.LANDSCAPE
    )

    fun folderFor(slice: ScoutSlice, cover: String?): CollectionFolder {
        val title = sliceTitle(slice)
        return CollectionFolder(
            id = slice.id,
            title = title,
            coverImageUrl = cover,
            tileShape = PosterShape.LANDSCAPE,
            sources = sourcesFor(slice)
        )
    }

    private val awardLists: Map<String, ScoutAwardLists> by lazy { runCatching { readAwards() }.getOrDefault(emptyMap()) }

    private fun readAwards(): Map<String, ScoutAwardLists> {
        val text = context.assets.open(AWARDS_FILE).bufferedReader().use { it.readText() }
        val awards = JSONObject(text).getJSONObject("awards")
        fun ints(side: JSONObject?, name: String): List<Int> {
            val list = side?.optJSONArray(name) ?: return emptyList()
            return List(list.length()) { list.getInt(it) }
        }
        return awards.keys().asSequence().associateWith { key ->
            val award = awards.getJSONObject(key)
            val won = award.optJSONObject("won")
            val nominated = award.optJSONObject("nominated")
            ScoutAwardLists(ints(won, "movie"), ints(won, "tv"), ints(nominated, "movie"), ints(nominated, "tv"))
        }
    }

    private fun titlesOf(slice: ScoutSlice) = slice.award?.let { scoutAwardTitles(it, awardLists) }

    /** An award with too few titles would be a nearly empty page, so it gets no tile. */
    fun offered(slice: ScoutSlice): Boolean = titlesOf(slice)?.let { it.count >= SCOUT_AWARD_MIN_TITLES } ?: true

    private fun awardSource(titleRes: Int, type: TmdbCollectionMediaType, ids: List<Int>) = ids.takeIf { it.isNotEmpty() }?.let {
        TmdbCollectionSource(
            sourceType = TmdbCollectionSourceType.LIST,
            title = context.getString(titleRes),
            mediaType = type,
            sortBy = TmdbCollectionSort.ORIGINAL.value,
            ids = it
        )
    }

    private fun sourcesFor(slice: ScoutSlice) = slice.award?.takeIf { it.tabs.isNotEmpty() }?.let { choice ->
        scoutAwardTabs(choice, awardLists).mapNotNull { tab ->
            awardSource(tab.titleRes, if (tab.series) TmdbCollectionMediaType.TV else TmdbCollectionMediaType.MOVIE, tab.ids)
        }
    } ?: titlesOf(slice)?.let { titles ->
        listOfNotNull(
            awardSource(R.string.type_movie, TmdbCollectionMediaType.MOVIE, titles.movies),
            awardSource(R.string.type_series, TmdbCollectionMediaType.TV, titles.series)
        )
    } ?: listOfNotNull(
        slice.movie?.let {
            TmdbCollectionSource(
                sourceType = TmdbCollectionSourceType.DISCOVER,
                title = context.getString(R.string.type_movie),
                mediaType = TmdbCollectionMediaType.MOVIE,
                sortBy = slice.sortBy,
                filters = it
            )
        },
        slice.tv?.let {
            TmdbCollectionSource(
                sourceType = TmdbCollectionSourceType.DISCOVER,
                title = context.getString(R.string.type_series),
                mediaType = TmdbCollectionMediaType.TV,
                sortBy = slice.sortBy,
                filters = it
            )
        }
    )

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
    ): CatalogRow {
        if (catalogId.startsWith(CATALOG_SAVED)) return savedRow(addonBaseUrl, addonId, addonName, catalogId, catalogName, skip, posterPattern)
        val pool = engine.pool.value
        val profile = store.profile.first()
        val leftOut = profile.settings.leaveOutGenres
        val picks = when {
            skip > 0 -> emptyList()
            catalogId == CATALOG_PICKED -> scoutRotate(
                pool?.picked.orEmpty(), PICKED_ITEMS, System.currentTimeMillis() / ROTATE_MS
            )
            catalogId.startsWith(CATALOG_BECAUSE) ->
                pool?.because?.getOrNull(catalogId.removePrefix(CATALOG_BECAUSE).toIntOrNull() ?: -1)?.picks.orEmpty()
            catalogId == CATALOG_GEMS -> pool?.gems.orEmpty()
            catalogId == CATALOG_NEW -> pool?.newForYou.orEmpty()
            catalogId == CATALOG_TRENDING -> pool?.trendingForYou.orEmpty()
            catalogId.startsWith(CATALOG_TASTE) ->
                scoutTasteSlotIndex(catalogId.removePrefix(CATALOG_TASTE))?.let { pool?.tasteRows?.getOrNull(it) }?.picks.orEmpty()
            else -> emptyList()
        }
        if (catalogId == CATALOG_PICKED || catalogId == CATALOG_NEW || catalogId == CATALOG_TRENDING || catalogId.startsWith(CATALOG_TASTE)) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { picksBuilder.noteShown(picks.take(SHOWN_FIRST).map { it.key }) }
        }
        val items = artwork.withPattern(
            folderSupport.withoutSeen(picks.filterNot { it.genres.any(leftOut::contains) }.map { it.toPreview(catalogId) }),
            posterPattern
        )
        return CatalogRow(
            addonId = addonId,
            addonName = addonName,
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = catalogName,
            type = ContentType.UNKNOWN,
            rawType = ROW_TYPE,
            items = items,
            hasMore = false,
            supportsSkip = false
        )
    }

    private suspend fun savedRow(
        addonBaseUrl: String, addonId: String, addonName: String, catalogId: String, catalogName: String, skip: Int,
        posterPattern: String
    ): CatalogRow {
        val slice = ScoutSlices.byId(catalogId.removePrefix(CATALOG_SAVED))
        val lists = if (skip > 0 || slice == null) emptyList() else sourcesFor(slice).map { source ->
            val result = resolver.resolve(source).first { it !is com.nuvio.tv.core.network.NetworkResult.Loading }
            (result as? com.nuvio.tv.core.network.NetworkResult.Success)?.data?.items.orEmpty()
        }
        val all = (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
        val ratingLimits = com.nuvio.tv.core.ratinglimit.ratingLimitGateOrNull(appContext)
        val mixed = ratingLimits?.activeLimit()?.let { limit -> ratingLimits.keepFitting(limit, all) { it.id to it.apiType } } ?: all
        return CatalogRow(
            addonId = addonId,
            addonName = addonName,
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = catalogName,
            type = ContentType.UNKNOWN,
            rawType = ROW_TYPE,
            items = artwork.withPattern(folderSupport.withoutSeen(mixed), posterPattern),
            hasMore = false,
            supportsSkip = false
        )
    }

    private fun ScoutPick.whyLine(): String? {
        val word = whyKeyword?.takeIf { context.resources.configuration.locales[0].language == "en" }
        val person = whyPerson
        return when {
            person != null && word != null && whyLoved -> context.getString(R.string.scout_picks_why_person_keyword, person, word)
            person != null && whyLoved -> context.getString(R.string.scout_picks_why_person_loved, person)
            person != null -> context.getString(R.string.scout_picks_why_person, person)
            word != null -> context.getString(R.string.scout_picks_why_keyword, word)
            else -> null
        }
    }

    private fun tasteRowName(row: com.nuvio.tv.domain.model.ScoutTasteRow): String {
        val genre = row.genre?.let { ScoutSlices.byId("genre_$it") }?.takeIf { it.titleRes != 0 }?.let { context.getString(it.titleRes) }
        val language = row.language?.takeIf { it != "en" }?.let { java.util.Locale(it).displayLanguage }
        val word = row.keyword?.takeIf { context.resources.configuration.locales[0].language == "en" }
        return when {
            genre != null && language != null -> context.getString(R.string.scout_picks_row_genre_language, genre, language)
            genre != null && word != null -> context.getString(R.string.scout_picks_row_genre_keyword, genre, word)
            genre != null -> context.getString(R.string.scout_picks_row_genre, genre)
            else -> context.getString(R.string.scout_row_picked)
        }
    }

    private fun ScoutPick.toPreview(catalogId: String): MetaPreview = MetaPreview(
        id = "tmdb:$tmdbId",
        type = if (tv) ContentType.SERIES else ContentType.MOVIE,
        rawType = if (tv) "series" else "movie",
        name = name,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = backdrop,
        logo = null,
        description = overview,
        releaseInfo = released?.take(4),
        released = released,
        imdbRating = rating,
        voteCount = votes,
        genres = emptyList(),
        scoutReason = when {
            catalogId == CATALOG_GEMS -> context.getString(R.string.scout_row_gems)
            !aiReason.isNullOrBlank() -> aiReason
            stretch -> context.getString(R.string.scout_picks_why_stretch)
            whyLine() != null -> whyLine()
            seedName != null -> context.getString(R.string.scout_row_because_title, seedName)
            else -> null
        }
    )

    companion object {
        const val BASE_URL = BuiltInCatalogs.BASE_PREFIX + "scout"
        const val ADDON_ID = "builtin.scout"
        const val TILES_COLLECTION_ID = "scout_tiles"
        private const val HERO_WAIT_MS = 20_000L
        private const val HERO_KEEP_MS = 24L * 60 * 60 * 1000
        private const val AWARDS_FILE = "scout_awards.json"
        private const val ART_FOLDER = "scout_art"
        private const val COMPLETE_SERIES = "complete_series"
        private const val TMDB_ORIGINAL = "https://image.tmdb.org/t/p/original"
        const val FOLDER_SURPRISE = "surprise"
        const val FOLDER_MORE = "more"
        const val CATALOG_PICKED = "picked"
        const val CATALOG_BECAUSE = "because_"
        const val CATALOG_GEMS = "gems"
        const val CATALOG_TASTE = "taste_"
        const val CATALOG_NEW = "new_for_you"
        const val CATALOG_TRENDING = "trending_for_you"
        const val CATALOG_SAVED = "saved_"
    }
}

/** Genre rows sit in fixed places on Home; the genre in each place may change from day to day. */
internal fun scoutTasteSlotId(index: Int): String = "slot${index + 1}"

internal fun scoutTasteSlotIndex(id: String): Int? =
    id.removePrefix("slot").takeIf { id.startsWith("slot") }?.toIntOrNull()?.minus(1)?.takeIf { it in 0 until SCOUT_ROWS_MAX }

@Module
@InstallIn(SingletonComponent::class)
abstract class ScoutCatalogModule {
    @Binds
    @IntoSet
    abstract fun provider(provider: ScoutCatalogProvider): BuiltInCatalogProvider
}
