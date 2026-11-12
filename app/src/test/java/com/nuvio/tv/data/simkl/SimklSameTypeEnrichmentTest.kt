package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SimklSameTypeEnrichmentTest {
    private val show = SimklLibraryEntry(
        mediaType = SimklMediaType.SHOWS,
        show = SimklMedia(
            title = "Show",
            ids = mapOf("tmdb" to JsonPrimitive(1396), "simkl" to JsonPrimitive(11))
        )
    )
    private val film = SimklLibraryEntry(
        mediaType = SimklMediaType.MOVIES,
        movie = SimklMedia(
            title = "Film",
            ids = mapOf("tmdb" to JsonPrimitive(1396), "simkl" to JsonPrimitive(22))
        )
    )
    private val animeFilm = SimklLibraryEntry(
        mediaType = SimklMediaType.ANIME,
        animeType = "movie",
        show = SimklMedia(
            title = "Anime film",
            ids = mapOf("kitsu" to JsonPrimitive(7), "simkl" to JsonPrimitive(33))
        )
    )

    @Test
    fun `a film is not turned into the show with the same number`() {
        val reference = TrackingMediaReference(
            kind = TrackingMediaKind.MOVIE,
            ids = TrackingExternalIds(tmdb = 1396)
        )

        assertEquals(reference, snapshot(show).enrichMediaReferenceOfSameType(reference, entryIndex = null))
    }

    @Test
    fun `an episode is not turned into the film with the same number`() {
        val reference = TrackingMediaReference(
            kind = TrackingMediaKind.SHOW,
            ids = TrackingExternalIds(tmdb = 1396),
            episode = TrackingEpisode(season = 1, number = 2)
        )

        assertEquals(reference, snapshot(film).enrichMediaReferenceOfSameType(reference, entryIndex = null))
    }

    @Test
    fun `a film still takes the ids of its own library entry`() {
        val reference = TrackingMediaReference(
            kind = TrackingMediaKind.MOVIE,
            ids = TrackingExternalIds(tmdb = 1396)
        )

        val enriched = snapshot(film).enrichMediaReferenceOfSameType(reference, entryIndex = null)

        assertEquals(TrackingMediaKind.MOVIE, enriched.kind)
        assertEquals(22L, enriched.ids.simkl)
        assertEquals("Film", enriched.title)
    }

    @Test
    fun `an anime film still becomes a film`() {
        val reference = TrackingMediaReference(
            kind = TrackingMediaKind.ANIME,
            ids = TrackingExternalIds(kitsu = 7)
        )

        val enriched = snapshot(animeFilm).enrichMediaReferenceOfSameType(reference, entryIndex = null)

        assertEquals(TrackingMediaKind.MOVIE, enriched.kind)
        assertEquals(33L, enriched.ids.simkl)
    }

    private fun snapshot(vararg entries: SimklLibraryEntry) =
        SimklSyncSnapshot(isInitialized = true, entries = entries.toList())
}
