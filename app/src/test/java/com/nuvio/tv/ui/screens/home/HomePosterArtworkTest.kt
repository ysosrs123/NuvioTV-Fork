package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.image.CustomPosterFallbackInterceptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.util.StableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomePosterArtworkTest {
    private val hero = HeroPreview(title = "Title", logo = null, description = null,
        contentTypeText = null, yearText = null, imdbText = null, genres = StableList(),
        poster = "fallback-poster", backdrop = "new-backdrop", imageUrl = "poster",
        frozenBackdropUrl = "frozen-backdrop")
    private val item = ModernCarouselItem("key", "Title", null, "poster", hero,
        ModernPayload.Catalog("key", "id", "movie", "addon", "Title", null, "movie"))

    @Test fun warmersUseTheSameCollapsedArtworkAndFrozenLandscapeAsCards() {
        assertEquals("poster", item.collapsedArtworkUrl(false))
        assertEquals("frozen-backdrop", item.collapsedArtworkUrl(true))
        assertEquals("displayed-backdrop", item.collapsedArtworkUrl(true, "displayed-backdrop"))
        assertEquals("fallback-poster", item.copy(imageUrl = null).collapsedArtworkUrl(false))
        assertEquals("fallback-poster", item.collapsedArtworkUrl(true, null))
    }

    @Test fun landscapePosterFromTheAddonWinsUnlessTheClearlogoOptionIsOn() {
        val meta = MetaPreview(id = "id", type = ContentType.MOVIE, name = "Title", poster = "poster",
            posterShape = PosterShape.POSTER, background = "background", logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList(), landscapePoster = "landscape")
        val withLandscape = item.copy(metaPreview = meta)
        assertEquals("landscape", withLandscape.collapsedArtworkUrl(true))
        assertEquals("frozen-backdrop", withLandscape.collapsedArtworkUrl(true, alwaysShowLandscapeClearlogo = true))
        assertEquals("poster", withLandscape.collapsedArtworkUrl(false))
        val customPoster = withLandscape.copy(metaPreview = meta.copy(rawPosterUrl = "addon-poster"))
        assertEquals("landscape", customPoster.collapsedArtworkUrl(true))
        assertEquals("frozen-backdrop", customPoster.collapsedArtworkUrl(true, alwaysShowLandscapeClearlogo = true))
        assertEquals(
            mapOf(CustomPosterFallbackInterceptor.FALLBACK_URL_KEY to "background"),
            withLandscape.customPosterCacheExtras("landscape", landscape = true)
        )
        assertEquals(emptyMap<String, String>(), item.customPosterCacheExtras("poster", landscape = false))
        assertEquals(
            emptyMap<String, String>(),
            customPoster.copy(metaPreview = meta.copy(background = null, rawPosterUrl = "addon-poster"))
                .customPosterCacheExtras("landscape", landscape = true)
        )
        assertEquals(
            mapOf(CustomPosterFallbackInterceptor.FALLBACK_URL_KEY to "addon-poster"),
            customPoster.customPosterCacheExtras("poster", landscape = false)
        )
    }

    private val row = CatalogRow(addonId = "addon", addonName = "Addon", addonBaseUrl = "https://addon",
        catalogId = "top", catalogName = "Top", type = ContentType.MOVIE, items = emptyList())
    private val posterOnly = MetaPreview(id = "id", type = ContentType.MOVIE, name = "Title", poster = "custom-poster",
        posterShape = PosterShape.POSTER, background = null, logo = null, description = null,
        releaseInfo = null, imdbRating = null, genres = emptyList(), rawPosterUrl = "addon-poster")

    @Test fun posterIsNeverFrozenAsTheBackdrop() {
        val built = buildCatalogItem(posterOnly, row, useLandscapePosters = true, occurrence = 0)
        assertNull(built.heroPreview.frozenBackdropUrl)
        assertEquals("custom-poster", built.heroPreview.backdrop)
        assertEquals("custom-poster", built.collapsedArtworkUrl(true))

        val enriched = buildCatalogItem(posterOnly.copy(background = "tmdb-backdrop"), row,
            useLandscapePosters = true, occurrence = 0, previousCachedItem = built)
        assertEquals("tmdb-backdrop", enriched.heroPreview.frozenBackdropUrl)

        val later = buildCatalogItem(posterOnly.copy(background = "other-backdrop"), row,
            useLandscapePosters = true, occurrence = 0, previousCachedItem = enriched)
        assertEquals("tmdb-backdrop", later.heroPreview.frozenBackdropUrl)

        val olderCache = built.copy(heroPreview = built.heroPreview.copy(frozenBackdropUrl = "addon-poster"))
        val rebuilt = buildCatalogItem(posterOnly.copy(background = "tmdb-backdrop"), row,
            useLandscapePosters = true, occurrence = 0, previousCachedItem = olderCache)
        assertEquals("tmdb-backdrop", rebuilt.heroPreview.frozenBackdropUrl)

        val posterAsBackground = buildCatalogItem(posterOnly.copy(background = "custom-poster"), row,
            useLandscapePosters = true, occurrence = 0)
        assertNull(posterAsBackground.heroPreview.frozenBackdropUrl)
    }

    @Test fun landscapeCardLeavesAPosterBackdropOnceAndThenStaysPut() {
        val card = buildCatalogItem(posterOnly, row, useLandscapePosters = true, occurrence = 0)
        assertEquals("custom-poster", card.cardBackdropUrl(null, null, landscape = true))
        assertEquals("custom-poster", card.cardBackdropUrl("custom-poster", "custom-poster", landscape = true))
        assertEquals("custom-poster", card.cardBackdropUrl("custom-poster", "addon-poster", landscape = true))
        assertEquals("tmdb-backdrop", card.cardBackdropUrl("custom-poster", "tmdb-backdrop", landscape = true))
        assertEquals("tmdb-backdrop", card.cardBackdropUrl("tmdb-backdrop", "other-backdrop", landscape = true))
        assertEquals("other-backdrop", card.cardBackdropUrl("tmdb-backdrop", "other-backdrop", landscape = false))

        val rebuilt = buildCatalogItem(posterOnly.copy(background = "tmdb-backdrop"), row,
            useLandscapePosters = true, occurrence = 0, previousCachedItem = card)
        assertEquals("tmdb-backdrop", rebuilt.cardBackdropUrl("custom-poster", null, landscape = true))
        assertEquals("first-backdrop", rebuilt.cardBackdropUrl("first-backdrop", null, landscape = true))
    }

    @Test fun landscapePosterStandingInForTheBackdropGivesWayToRealArt() {
        val landscapeOnly = posterOnly.copy(landscapePoster = "custom-landscape")
        val card = buildCatalogItem(landscapeOnly, row, useLandscapePosters = true, occurrence = 0)
        assertNull(card.heroPreview.frozenBackdropUrl)
        assertEquals("custom-landscape", card.cardBackdropUrl(null, null, landscape = true))
        assertEquals("custom-landscape", card.cardBackdropUrl("custom-landscape", "custom-poster", landscape = true))
        assertEquals("tmdb-backdrop", card.cardBackdropUrl("custom-landscape", "tmdb-backdrop", landscape = true))

        val olderCache = card.copy(heroPreview = card.heroPreview.copy(frozenBackdropUrl = "custom-landscape"))
        val rebuilt = buildCatalogItem(landscapeOnly.copy(background = "tmdb-backdrop"), row,
            useLandscapePosters = true, occurrence = 0, previousCachedItem = olderCache)
        assertEquals("tmdb-backdrop", rebuilt.heroPreview.frozenBackdropUrl)

        val sameAsBackground = buildCatalogItem(posterOnly.copy(background = "fanart", landscapePoster = "fanart"), row,
            useLandscapePosters = true, occurrence = 0)
        assertEquals("fanart", sameAsBackground.heroPreview.frozenBackdropUrl)
        assertEquals("fanart", sameAsBackground.cardBackdropUrl("fanart", "tmdb-backdrop", landscape = true))
    }

    @Test fun expandedPortraitCardFallsBackToTheBackgroundNotThePoster() {
        val meta = posterOnly.copy(background = "background", landscapePoster = "custom-landscape")
        val card = buildCatalogItem(meta, row, useLandscapePosters = false, occurrence = 0)
        assertEquals(
            mapOf(CustomPosterFallbackInterceptor.FALLBACK_URL_KEY to "background"),
            card.customPosterCacheExtras("custom-landscape", landscape = true)
        )
        assertEquals(
            emptyMap<String, String>(),
            buildCatalogItem(meta.copy(background = null), row, useLandscapePosters = false, occurrence = 0)
                .customPosterCacheExtras("custom-landscape", landscape = true)
        )
    }

    private val posterOnlyMeta = Meta(id = "id", type = ContentType.MOVIE, name = "Title", poster = "meta-poster",
        posterShape = PosterShape.POSTER, background = null, logo = null, description = null, releaseInfo = null,
        imdbRating = null, genres = emptyList(), runtime = null, director = emptyList(), cast = emptyList(),
        videos = emptyList(), country = null, awards = null, language = null, links = emptyList())

    @Test fun enrichmentNeverStoresAPosterAsTheBackground() {
        assertNull(posterOnly.enrichedBackground(posterOnlyMeta))
        assertEquals("addon-backdrop", posterOnly.copy(background = "addon-backdrop").enrichedBackground(posterOnlyMeta))
        assertNull(posterOnly.copy(landscapePoster = "custom-landscape").enrichedBackground(posterOnlyMeta))
        assertEquals("meta-landscape",
            posterOnly.copy(background = "addon-backdrop")
                .enrichedBackground(posterOnlyMeta.copy(landscapePoster = "meta-landscape")))
        assertEquals("meta-backdrop",
            posterOnly.copy(background = "addon-backdrop")
                .enrichedBackground(posterOnlyMeta.copy(background = "meta-backdrop", landscapePoster = "meta-landscape")))
    }
}
