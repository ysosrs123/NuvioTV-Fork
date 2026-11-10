package com.nuvio.tv.core.catalog

import android.content.Context
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.providerId
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.titleRes
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.R
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Library source's lists as optional Home rows, off until switched on in Reorder Home Catalogs.
 * Rows follow whichever tracker is the Library source; lists from different trackers are never mixed.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class TrackerListRows internal constructor(
    private val library: Lazy<LibraryRepository>,
    private val titleOf: (LibraryListTab) -> String,
    private val providerName: (TrackingProviderId) -> String,
    private val loadWaitMs: Long
) : BuiltInCatalogProvider {
    @Inject
    constructor(
        library: Lazy<LibraryRepository>,
        @ApplicationContext context: Context
    ) : this(
        library = library,
        titleOf = { tab -> tab.titleRes()?.let(context::getString) ?: tab.title },
        providerName = { id -> context.getString(id.nameRes()) },
        loadWaitMs = LOAD_WAIT_MS
    )

    override val addons: Flow<List<Addon>> = flow {
        val repository = library.get()
        emitAll(
            combine(repository.sourceMode, repository.listTabs) { mode, tabs ->
                val provider = mode.providerId ?: return@combine emptyList()
                val rows = tabs.filter(::canBeRow)
                if (rows.isEmpty()) emptyList() else listOf(addonFor(provider, rows))
            }
        )
    }.distinctUntilChanged()

    /** Fires when the titles of the source's lists change, so rows already on Home can read them again. */
    override val rowChanges: Flow<Unit> = flow {
        emitAll(
            library.get().libraryItems
                .map { items -> items.map { it.id to it.listKeys } }
                .distinctUntilChanged()
                .drop(1)
                .debounce(CHANGE_SETTLE_MS)
                .map { }
        )
    }

    override fun handles(baseUrl: String?): Boolean = baseUrl?.startsWith(BASE_URL_PREFIX) == true

    override fun ownsAddon(addonId: String?): Boolean = addonId?.startsWith(ADDON_ID_PREFIX) == true

    override fun hiddenByDefaultKeys(addons: List<Addon>): List<String> = addons
        .filter { ownsAddon(it.id) }
        .flatMap { addon -> addon.catalogs.map { BuiltInCatalogs.homeKey(addon.id, it.apiType, it.id) } }

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
        val items = withTimeoutOrNull(loadWaitMs) { library.get().libraryItems.first() }.orEmpty()
        val titles = items.filter { catalogId in it.listKeys }.sortedForList(catalogId)
        val page = titles.drop(skip).take(PAGE_SIZE)
        return CatalogRow(
            addonId = addonId,
            addonName = addonName,
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = catalogName,
            type = ContentType.UNKNOWN,
            rawType = type,
            items = page.map(LibraryEntry::toMetaPreview),
            hasMore = skip + PAGE_SIZE < titles.size,
            currentPage = skip / PAGE_SIZE,
            supportsSkip = true,
            skipStep = PAGE_SIZE,
            nextSkip = skip + PAGE_SIZE
        )
    }

    private fun addonFor(provider: TrackingProviderId, tabs: List<LibraryListTab>): Addon {
        val name = providerName(provider)
        return Addon(
            id = ADDON_ID_PREFIX + provider.storageId,
            name = name,
            version = "1",
            description = null,
            logo = null,
            baseUrl = BASE_URL_PREFIX + provider.storageId,
            catalogs = tabs.map { tab ->
                CatalogDescriptor(
                    type = ContentType.UNKNOWN,
                    rawType = MIXED_TYPE,
                    id = tab.key,
                    name = titleOf(tab),
                    extra = listOf(CatalogExtra(name = "skip")),
                    pageSize = PAGE_SIZE,
                    showInHome = true,
                    hasExplicitShowInHome = true
                )
            },
            types = listOf(ContentType.MOVIE, ContentType.SERIES),
            resources = listOf(AddonResource(name = "catalog", types = emptyList(), idPrefixes = null))
        )
    }

    /** A list of finished titles has no place on Home: Watched titles decides what happens to those. */
    private fun canBeRow(tab: LibraryListTab): Boolean = tab.key !in FINISHED_LIST_KEYS

    companion object {
        const val PAGE_SIZE = 60
        private const val MIXED_TYPE = "all"
        private const val LOAD_WAIT_MS = 3_000L
        private const val CHANGE_SETTLE_MS = 2_000L
        private const val ADDON_ID_PREFIX = BuiltInCatalogs.ADDON_ID_PREFIX + "lists."
        private const val BASE_URL_PREFIX = BuiltInCatalogs.BASE_PREFIX + "lists/"
        private val FINISHED_LIST_KEYS = setOf("wetrakr:status:watched", "simkl:status:completed")

        fun isListRow(addonId: String?): Boolean = addonId?.startsWith(ADDON_ID_PREFIX) == true
    }
}

/** The list's own order where it has one, otherwise newest first. */
internal fun List<LibraryEntry>.sortedForList(listKey: String): List<LibraryEntry> =
    sortedWith(
        compareBy<LibraryEntry> { it.listRanks[listKey] ?: Int.MAX_VALUE }
            .thenByDescending { it.listedAt }
    )

private fun TrackingProviderId.nameRes(): Int = when (this) {
    TrackingProviderId.TRAKT -> R.string.trakt_name
    TrackingProviderId.SIMKL -> R.string.simkl_name
    TrackingProviderId.MDBLIST -> R.string.mdblist_name
    TrackingProviderId.FLOPPY -> R.string.floppy_name
    TrackingProviderId.WETRAKR -> R.string.wetrakr_name
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TrackerListRowsModule {
    @Binds
    @IntoSet
    abstract fun rows(rows: TrackerListRows): BuiltInCatalogProvider
}
