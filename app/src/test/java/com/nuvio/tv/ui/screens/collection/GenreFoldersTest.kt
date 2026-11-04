package com.nuvio.tv.ui.screens.collection

import com.nuvio.tv.data.mediaserver.GenreSummary
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.data.mediaserver.ServerSort
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.CollectionFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreFoldersTest {
    private val serverId = ServerCatalog.addonId("c1")
    private val movies = AvailableCatalog(serverId, "Silo · BlackoutVault", "movie", "lib1", "BlackoutVault · Movies", listOf("Action", "Comedy", "Drama"))

    @Test
    fun onlyServerLibrariesWithGenresByTheirOwnEntry() {
        val catalogs = listOf(
            movies,
            movies.copy(catalogId = ServerCatalog.sortedCatalogId(ServerSort.TITLE, "lib1"), catalogName = "BlackoutVault · Movies · A to Z"),
            movies.copy(catalogId = "lib2", genreOptions = emptyList()),
            AvailableCatalog("com.linvo.cinemeta", "Cinemeta", "movie", "top", "Popular", listOf("Action"))
        )

        assertEquals(listOf(movies), genreFolderLibraries(catalogs))
    }

    @Test
    fun countedGenresComeBiggestFirstWithTheTopEightTicked() {
        val names = (1..12).map { "G$it" }
        val counts = names.mapIndexed { i, name -> name to (i + 1) * 10 }.toMap() + ("G12" to 0)

        val choices = genreChoices(names, counts.mapValues { GenreSummary(it.value, null) })

        assertEquals(listOf("G11", "G10", "G9", "G8", "G7", "G6", "G5", "G4", "G3", "G2", "G1", "G12"), choices.map { it.name })
        assertEquals(GENRE_FOLDERS_PRETICKED, choices.count { it.ticked })
        assertTrue(choices.take(GENRE_FOLDERS_PRETICKED).all { it.ticked })
        assertEquals(110, choices.first().count)
    }

    @Test
    fun emptyGenresStayUnticked() {
        val summaries = mapOf("Action" to 5, "Western" to 0, "Drama" to 2).mapValues { GenreSummary(it.value, null) }

        val choices = genreChoices(listOf("Action", "Western", "Drama"), summaries)

        assertEquals(listOf("Action" to true, "Drama" to true, "Western" to false), choices.map { it.name to it.ticked })
    }

    @Test
    fun nothingIsTickedWhileCounting() {
        val choices = genreChoices(listOf("Action", "Drama"), summaries = null)

        assertEquals(listOf("Action", "Drama"), choices.map { it.name })
        assertTrue(choices.none { it.ticked })
    }

    @Test
    fun withoutCountsTheServersOrderIsKept() {
        val names = (1..10).map { "G$it" }

        val choices = genreChoices(names, summaries = emptyMap())

        assertEquals(names, choices.map { it.name })
        assertEquals(names.take(GENRE_FOLDERS_PRETICKED), choices.filter { it.ticked }.map { it.name })
        assertNull(choices.first().count)
    }

    @Test
    fun createWaitsForTheCounts() {
        val counting = GenreFoldersState(listOf(movies), movies, genres = listOf(GenreChoice("Action", ticked = true)), countsLoading = true)

        assertFalse(counting.canCreate)
        assertTrue(counting.copy(countsLoading = false).canCreate)
        assertFalse(counting.copy(countsLoading = false, genres = listOf(GenreChoice("Action"))).canCreate)
    }

    @Test
    fun oneFolderPerTickedGenreInTheChosenOrder() {
        val state = GenreFoldersState(
            libraries = listOf(movies),
            library = movies,
            sort = ServerSort.TITLE,
            genres = listOf(
                GenreChoice("Action", 9, true, picture = "https://image.tmdb.org/t/p/a.jpg"),
                GenreChoice("Comedy", 5, false),
                GenreChoice("Drama", 3, true)
            )
        )
        var next = 0

        val folders = genreFolders(state, existing = emptyList()) { "f${next++}" }

        assertEquals(listOf("Action", "Drama"), folders.map { it.title })
        assertEquals(listOf("f0", "f1"), folders.map { it.id })
        assertEquals(
            AddonCatalogCollectionSource(serverId, "movie", "sorted.title:lib1", "Action"),
            folders.first().sources.single()
        )
        assertEquals(listOf("https://image.tmdb.org/t/p/a.jpg", null), folders.map { it.heroBackdropUrl })
        assertTrue(folders.all { it.coverImageUrl == null })
    }

    @Test
    fun genresThatAlreadyHaveAFolderAreSkipped() {
        val state = GenreFoldersState(
            libraries = listOf(movies),
            library = movies,
            genres = listOf(GenreChoice("Action", ticked = true), GenreChoice("Drama", ticked = true))
        )
        val existing = listOf(
            CollectionFolder(id = "old", title = "Bang", sources = listOf(AddonCatalogCollectionSource(serverId, "movie", "lib1", "action")))
        )

        val folders = genreFolders(state, existing) { "new" }

        assertEquals(listOf("Drama"), folders.map { it.title })
        assertEquals("lib1", (folders.single().sources.single() as AddonCatalogCollectionSource).catalogId)
    }
}
