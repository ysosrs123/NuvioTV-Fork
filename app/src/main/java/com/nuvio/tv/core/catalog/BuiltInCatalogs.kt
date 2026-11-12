package com.nuvio.tv.core.catalog

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.catalogRowLegacyKey
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/** Rows the app makes itself, shown on Home and in Reorder Home Catalogs like any add-on's. */
interface BuiltInCatalogProvider {
    val addons: Flow<List<Addon>>

    /** Rows of tiles shown on Home like a collection, but never saved or synced as one. */
    val collections: Flow<List<Collection>> get() = flowOf(emptyList())

    /** Where these rows go until the user has ordered them; saved positions always win. */
    val defaultFirstKeys: List<String> get() = emptyList()

    /** A saved key an unsaved row of this provider goes right after, or null to start it at the top. */
    fun anchorFor(key: String, saved: List<String>): String? = null

    /** Lower goes higher among the rows that start at the top. */
    val defaultFirstRank: Int get() = 1

    /** The collection a folder page opens from, which may hold more folders than Home shows. */
    fun collectionForFolders(collectionId: String): Collection? = null

    fun handles(baseUrl: String?): Boolean

    fun ownsAddon(addonId: String?): Boolean

    /** Home keys that stay off until switched on. */
    fun hiddenByDefaultKeys(addons: List<Addon>): List<String> = emptyList()

    /** Fires when rows already on Home should be read again. */
    val rowChanges: Flow<Unit> get() = emptyFlow()

    /** A row may mix films and shows, so its title carries no type. */
    fun showsTypeSuffix(addonId: String): Boolean = false

    suspend fun catalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        extraArgs: Map<String, String>,
        posterPattern: String
    ): CatalogRow
}

@Singleton
class BuiltInCatalogs @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards BuiltInCatalogProvider>
) {
    val addons: Flow<List<Addon>> = when {
        providers.isEmpty() -> flowOf(emptyList())
        else -> combine(providers.map { it.addons }) { lists -> lists.flatMap { it } }
    }

    val collections: Flow<List<Collection>> = when {
        providers.isEmpty() -> flowOf(emptyList())
        else -> combine(providers.map { it.collections }) { lists -> lists.flatMap { it } }
    }

    fun collectionForFolders(collectionId: String): Collection? =
        providers.firstNotNullOfOrNull { it.collectionForFolders(collectionId) }

    fun withDefaultFirst(savedKeys: List<String>): List<String> {
        val placed = savedKeys.toMutableList()
        val top = ArrayList<String>()
        providers.sortedBy { it.defaultFirstRank }.forEach { provider ->
            provider.defaultFirstKeys.forEach { key ->
                if (key in placed || key in top) return@forEach
                val at = provider.anchorFor(key, placed)?.let(placed::indexOf) ?: -1
                if (at >= 0) placed.add(at + 1, key) else top.add(key)
            }
        }
        return top + placed
    }

    fun provider(baseUrl: String?): BuiltInCatalogProvider? = providers.firstOrNull { it.handles(baseUrl) }

    fun hiddenByDefaultKeys(addons: List<Addon>): List<String> = providers.flatMap { it.hiddenByDefaultKeys(addons) }

    fun skipsTypeSuffix(addonId: String): Boolean =
        providers.any { it.ownsAddon(addonId) && !it.showsTypeSuffix(addonId) }

    fun rowChanges(addonId: String): Flow<Unit> =
        providers.firstOrNull { it.ownsAddon(addonId) }?.rowChanges ?: emptyFlow()

    companion object {
        /** Address every built-in row uses, which a title page must not ask for metadata. */
        const val BASE_PREFIX = "nuvio-builtin://"

        /** Rows depend on what this device is signed in to, so their on/off and position stay on the device. */
        const val ADDON_ID_PREFIX = "builtin."

        fun homeKey(addonId: String, type: String, catalogId: String): String =
            catalogRowLegacyKey(addonId, type, catalogId)

        fun isBuiltInKey(key: String): Boolean = key.startsWith(ADDON_ID_PREFIX) || key.startsWith(BASE_PREFIX)

        val None = BuiltInCatalogs(emptySet())
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BuiltInCatalogsModule {
    @Multibinds
    abstract fun providers(): Set<BuiltInCatalogProvider>
}
