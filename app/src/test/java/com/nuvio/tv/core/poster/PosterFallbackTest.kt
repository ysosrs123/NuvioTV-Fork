package com.nuvio.tv.core.poster

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterFallbackTest {
    private val meta = MetaPreview(id = "tt1", type = ContentType.MOVIE, name = "Title", poster = "custom-poster",
        posterShape = PosterShape.POSTER, background = "background", logo = null, description = null,
        releaseInfo = null, imdbRating = null, genres = emptyList(), landscapePoster = "custom-landscape",
        rawPosterUrl = "addon-poster")

    @Test fun portraitCardFallsBackToTheAddonPoster() {
        assertEquals("addon-poster", meta.posterFallbackUrl("custom-poster", landscapeCard = false))
        assertNull(meta.copy(rawPosterUrl = null).posterFallbackUrl("custom-poster", landscapeCard = false))
        assertNull(meta.posterFallbackUrl("addon-poster", landscapeCard = false))
    }

    @Test fun landscapeCardFallsBackToTheBackgroundOnly() {
        assertEquals("background", meta.posterFallbackUrl("custom-landscape", landscapeCard = true))
        assertNull(meta.posterFallbackUrl("background", landscapeCard = true))
        assertNull(meta.copy(background = null).posterFallbackUrl("custom-landscape", landscapeCard = true))
        assertNull(meta.copy(background = "addon-poster").posterFallbackUrl("custom-landscape", landscapeCard = true))
    }

    @Test fun landscapeCardShowingThePosterKeepsThePosterFallback() {
        val posterOnly = meta.copy(background = null, landscapePoster = null)
        assertEquals("addon-poster", posterOnly.posterFallbackUrl("custom-poster", landscapeCard = true))
    }

    @Test fun onlyAFailureWithNothingToFallBackToWaitsForALateFallback() {
        val key = com.nuvio.tv.core.image.CustomPosterFallbackInterceptor.FALLBACK_URL_KEY
        assertTrue(failedWithoutFallback("custom-landscape", emptyMap(), "custom-landscape"))
        assertFalse(failedWithoutFallback("custom-landscape", mapOf(key to "background"), "custom-landscape"))
        assertFalse(failedWithoutFallback("background", emptyMap(), "custom-landscape"))
        assertEquals("background", meta.posterFallbackUrl("custom-landscape", landscapeCard = true))
    }

    @Test fun onlyClearlyTallArtworkCountsAsPortrait() {
        assertTrue(isPortraitArtwork(500, 750))
        assertTrue(isPortraitArtwork(400, 600))
        assertFalse(isPortraitArtwork(600, 600))
        assertFalse(isPortraitArtwork(540, 600))
        assertFalse(isPortraitArtwork(1280, 720))
        assertFalse(isPortraitArtwork(0, 750))
        assertFalse(isPortraitArtwork(500, 0))
    }

    @Test fun encodedShapePlaceholderAlsoResolvesTheLandscapePoster() {
        val plain = meta.copy(poster = "addon-poster", landscapePoster = null, rawPosterUrl = null)
        val resolved = plain.withCustomPosterUrl("https://example.com/%7Bimdb_id%7D/%7Bshape%7D.jpg")
        assertEquals("https://example.com/tt1/poster.jpg", resolved.poster)
        assertEquals("https://example.com/tt1/landscape.jpg", resolved.landscapePoster)
        assertEquals("addon-poster", resolved.rawPosterUrl)
        assertNull(plain.withCustomPosterUrl("https://example.com/{imdb_id}.jpg").landscapePoster)
    }
}
