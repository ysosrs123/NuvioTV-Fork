package com.nuvio.tv.data.mediaserver.plex

import com.nuvio.tv.core.tracking.TrackingExternalIds
import org.junit.Assert.assertEquals
import org.junit.Test

class PlexLegacyGuidTest {

    @Test
    fun `older movie agents give their imdb or tmdb id`() {
        assertEquals(TrackingExternalIds(imdb = "tt0133093"), item("com.plexapp.agents.imdb://tt0133093?lang=en").externalIds())
        assertEquals(TrackingExternalIds(tmdb = 603), item("com.plexapp.agents.themoviedb://603?lang=en").externalIds())
    }

    @Test
    fun `older show agents give their tvdb or tmdb id`() {
        assertEquals(TrackingExternalIds(tvdb = "121361"), item("com.plexapp.agents.thetvdb://121361?lang=en").externalIds())
        assertEquals(TrackingExternalIds(tvdb = "121361"), item("com.plexapp.agents.thetvdb://121361/1/1?lang=en").externalIds())
        assertEquals(TrackingExternalIds(tmdb = 1399), item("com.plexapp.agents.themoviedb://1399?lang=en").externalIds())
    }

    @Test
    fun `hama anime ids are read by their source`() {
        assertEquals(TrackingExternalIds(anidb = 23), item("com.plexapp.agents.hama://anidb-23?lang=en").externalIds())
        assertEquals(TrackingExternalIds(tvdb = "81797"), item("com.plexapp.agents.hama://tvdb-81797?lang=en").externalIds())
        assertEquals(TrackingExternalIds(tvdb = "81797"), item("com.plexapp.agents.hama://tvdb3-81797?lang=en").externalIds())
        assertEquals(TrackingExternalIds(tmdb = 37854), item("com.plexapp.agents.hama://tmdb-37854?lang=en").externalIds())
        assertEquals(TrackingExternalIds(imdb = "tt0388629"), item("com.plexapp.agents.hama://imdb-tt0388629?lang=en").externalIds())
    }

    @Test
    fun `the guid list wins and the older field only fills gaps`() {
        val both = MetadataWire(
            guid = "com.plexapp.agents.thetvdb://999?lang=en",
            guids = listOf(TagWire(id = "imdb://tt0944947"), TagWire(id = "tvdb://121361"))
        )
        assertEquals(TrackingExternalIds(imdb = "tt0944947", tvdb = "121361"), both.externalIds())
    }

    @Test
    fun `new agent, local and unknown guids give no ids`() {
        assertEquals(TrackingExternalIds(), item("plex://movie/5d776826880197001ec90c39").externalIds())
        assertEquals(TrackingExternalIds(), item("local://12345").externalIds())
        assertEquals(TrackingExternalIds(), item("com.plexapp.agents.none://abc").externalIds())
        assertEquals(TrackingExternalIds(), item("com.plexapp.agents.hama://anidb-abc").externalIds())
        assertEquals(TrackingExternalIds(), item(null).externalIds())
    }

    private fun item(guid: String?) = MetadataWire(guid = guid)
}
