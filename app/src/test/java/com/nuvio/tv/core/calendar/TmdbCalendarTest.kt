package com.nuvio.tv.core.calendar

import com.nuvio.tv.data.remote.api.TmdbDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbEpisodeToAir
import com.nuvio.tv.data.remote.api.TmdbMovieReleaseDatesResponse
import com.nuvio.tv.data.remote.api.TmdbSeasonResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbCalendarTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val releases = moshi.adapter(TmdbMovieReleaseDatesResponse::class.java).fromJson(
        """
        {"id":1,"results":[
          {"iso_3166_1":"AU","release_dates":[
            {"certification":"M","descriptors":[],"iso_639_1":"","note":"","release_date":"2026-10-08T00:00:00.000Z","type":3}]},
          {"iso_3166_1":"US","release_dates":[
            {"certification":"PG-13","release_date":"2026-09-30T00:00:00.000Z","type":1},
            {"certification":"PG-13","release_date":"2026-10-02T00:00:00.000Z","type":3},
            {"certification":"PG-13","release_date":"2026-12-20T00:00:00.000Z","type":4},
            {"certification":"PG-13","release_date":"2026-12-01T00:00:00.000Z","type":4}]},
          {"iso_3166_1":"DE","release_dates":[
            {"certification":"12","release_date":"2026-10-15T00:00:00.000Z","type":2}]}
        ]}
        """.trimIndent()
    )!!.results!!

    @Test fun cinemaFromTheBoxRegionAndDigitalFromTheUsWhenTheRegionHasNone() {
        val entries = tmdbFilmReleaseEntries(releases, "AU")
        assertEquals(
            listOf(CalendarEntryKind.MOVIE_THEATRICAL to "2026-10-08", CalendarEntryKind.MOVIE_DIGITAL to "2026-12-01"),
            entries.map { it.kind to it.airDate }
        )
        assertTrue(entries.all { !it.timeKnown && it.source == CalendarSource.TMDB })
    }

    @Test fun limitedCinemaReleaseWhenThereIsNoWideOne() {
        val entries = tmdbFilmReleaseEntries(releases, "DE")
        assertEquals("2026-10-15", entries.first { it.kind == CalendarEntryKind.MOVIE_THEATRICAL }.airDate)
    }

    @Test fun anUnknownRegionUsesTheUs() {
        val entries = tmdbFilmReleaseEntries(releases, "NZ")
        assertEquals(listOf("2026-10-02", "2026-12-01"), entries.map { it.airDate })
    }

    @Test fun nextAndLastEpisodeToAirAreRead() {
        val details = moshi.adapter(TmdbDetailsResponse::class.java).fromJson(
            """
            {"id":30,"name":"Night Shift","status":"Returning Series","networks":[{"id":49,"name":"HBO"}],
             "last_episode_to_air":{"id":1,"name":"Static","air_date":"2026-10-01","episode_number":5,"season_number":2,"episode_type":"standard"},
             "next_episode_to_air":{"id":2,"name":"Graveyard","air_date":"2026-10-08","episode_number":6,"season_number":2,"episode_type":"finale"}}
            """.trimIndent()
        )!!
        assertEquals("Graveyard", details.nextEpisodeToAir?.name)
        val next = tmdbEpisodeEntry(details.nextEpisodeToAir!!)!!
        assertEquals("S2E6", next.slot)
        assertEquals("2026-10-08", next.airDate)
        assertNull(next.airInstantMs)
        assertEquals(CalendarEpisodeLabel.SEASON_FINALE, next.label)
        assertEquals(CalendarShowStatus.RUNNING, tmdbShowStatus(details.status))
    }

    @Test fun seasonEpisodeTypesAreRead() {
        val season = moshi.adapter(TmdbSeasonResponse::class.java).fromJson(
            """{"season_number":2,"episodes":[{"episode_number":4,"air_date":"2026-09-24","episode_type":"mid_season","season_number":2},
               {"episode_number":6,"air_date":null,"episode_type":"finale","season_number":2}]}"""
        )!!
        assertEquals("mid_season", season.episodes!!.first().episodeType)
        assertEquals(2, season.episodes!!.first().seasonNumber)
        assertNull(tmdbEpisodeEntry(TmdbEpisodeToAir(airDate = null, seasonNumber = 2, episodeNumber = 6)))
        assertEquals(
            CalendarEpisodeLabel.NEW_SHOW,
            tmdbEpisodeEntry(TmdbEpisodeToAir(airDate = "2026-11-01", seasonNumber = 1, episodeNumber = 1, episodeType = "standard"))!!.label
        )
    }

    @Test fun statuses() {
        assertEquals(CalendarShowStatus.IN_PRODUCTION, tmdbShowStatus("In Production"))
        assertEquals(CalendarShowStatus.CANCELLED, tmdbShowStatus("Canceled"))
        assertEquals(CalendarShowStatus.ENDED, tmdbShowStatus("Ended"))
    }
}
