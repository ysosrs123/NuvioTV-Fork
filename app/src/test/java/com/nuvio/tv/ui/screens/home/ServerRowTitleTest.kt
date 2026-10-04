package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerRowTitleTest {
    private fun row(addonId: String, name: String, type: ContentType, rawType: String) = CatalogRow(
        addonId = addonId,
        addonName = "Source",
        addonBaseUrl = "https://source.example",
        catalogId = "c1",
        catalogName = name,
        type = type,
        rawType = rawType,
        items = emptyList()
    )

    @Test
    fun serverRowsCarryNoTypeSuffix() {
        val movies = row(ServerCatalog.addonId("c1"), "HomeServer · Movies", ContentType.MOVIE, "movie")
        val shows = row(ServerCatalog.addonId("c1"), "HomeServer · Shows", ContentType.SERIES, "series")
        val collections = row(ServerCatalog.addonId("c1"), "HomeServer · Collections", ContentType.UNKNOWN, "collection")

        assertEquals("HomeServer · Movies", catalogRowTitle(movies, showCatalogTypeSuffix = true, strTypeMovie = "Movie", strTypeSeries = "Series"))
        assertEquals("HomeServer · Shows", catalogRowTitle(shows, showCatalogTypeSuffix = true, strTypeMovie = "Movie", strTypeSeries = "Series"))
        assertEquals("HomeServer · Collections", catalogRowTitle(collections, showCatalogTypeSuffix = true))
    }

    @Test
    fun addonRowsKeepTheirTypeSuffix() {
        val popular = row("com.linvo.cinemeta", "Popular", ContentType.MOVIE, "movie")

        assertEquals("Popular - Movie", catalogRowTitle(popular, showCatalogTypeSuffix = true, strTypeMovie = "Movie", strTypeSeries = "Series"))
        assertEquals("Popular", catalogRowTitle(popular, showCatalogTypeSuffix = false))
    }
}
