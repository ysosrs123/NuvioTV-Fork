package com.nuvio.tv.ui.screens.collection

import com.nuvio.tv.data.mediaserver.GenreSummary
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.data.mediaserver.ServerSort
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.catalogTypesMatch

data class GenreChoice(
    val name: String,
    val count: Int? = null,
    val ticked: Boolean = false,
    val picture: String? = null
)

data class GenreFoldersState(
    val libraries: List<AvailableCatalog>,
    val library: AvailableCatalog,
    val sort: ServerSort = ServerSort.ADDED,
    val genres: List<GenreChoice> = emptyList(),
    val countsLoading: Boolean = false
) {
    val tickedCount: Int get() = genres.count { it.ticked }
    val canCreate: Boolean get() = !countsLoading && tickedCount > 0
}

internal const val GENRE_FOLDERS_PRETICKED = 8
internal const val GENRE_FOLDERS_LONG_ROW = 10

/** Server libraries in the picker that have genres, each by its own (newest added) entry. */
internal fun genreFolderLibraries(catalogs: List<AvailableCatalog>): List<AvailableCatalog> =
    catalogs.filter { catalog ->
        ServerCatalog.isServerAddonId(catalog.addonId) &&
            catalog.genreOptions.isNotEmpty() &&
            ServerCatalog.libraryCatalog(catalog.catalogId).first == ServerSort.ADDED
    }

/**
 * The genres to offer, biggest first when the server could count them, else in the server's order.
 * Without [summaries] (still counting) nothing is ticked, so the ticks don't jump when the counts land;
 * after that the first [GENRE_FOLDERS_PRETICKED] that hold titles start ticked.
 */
internal fun genreChoices(names: List<String>, summaries: Map<String, GenreSummary>?): List<GenreChoice> {
    val choices = names.map { GenreChoice(it, summaries?.get(it)?.count, picture = summaries?.get(it)?.picture) }
    if (summaries == null) return choices
    val ordered = if (choices.none { it.count != null }) {
        choices
    } else {
        choices.sortedWith(compareByDescending<GenreChoice> { it.count ?: -1 }.thenBy { it.name.lowercase() })
    }
    var ticked = 0
    return ordered.map { choice ->
        val tick = ticked < GENRE_FOLDERS_PRETICKED && choice.count != 0
        if (tick) ticked++
        choice.copy(ticked = tick)
    }
}

/** One folder per ticked genre; a genre that already has a folder for this library and order is skipped. */
internal fun genreFolders(
    state: GenreFoldersState,
    existing: List<CollectionFolder>,
    newId: () -> String
): List<CollectionFolder> {
    val library = state.library
    val libraryId = ServerCatalog.libraryCatalog(library.catalogId).second
    val catalogId = if (state.sort == ServerSort.ADDED) libraryId else ServerCatalog.sortedCatalogId(state.sort, libraryId)
    val taken = existing.flatMap { it.sources }.filterIsInstance<AddonCatalogCollectionSource>()
        .filter { it.addonId == library.addonId && it.catalogId == catalogId && catalogTypesMatch(it.type, library.type) }
        .mapNotNull { it.genre?.lowercase() }
        .toSet()
    return state.genres.filter { it.ticked && it.name.lowercase() !in taken }.map { genre ->
        CollectionFolder(
            id = newId(),
            title = genre.name,
            heroBackdropUrl = genre.picture,
            sources = listOf(
                AddonCatalogCollectionSource(
                    addonId = library.addonId,
                    type = library.type,
                    catalogId = catalogId,
                    genre = genre.name
                )
            )
        )
    }
}
