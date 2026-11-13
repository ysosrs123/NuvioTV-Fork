package com.nuvio.tv.data.local

import com.nuvio.tv.domain.model.ScoutMix
import com.nuvio.tv.domain.model.ScoutMixShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoutMixStoreTest {
    @Test
    fun `a mix survives being saved`() {
        val mix = ScoutMix(
            id = "m1",
            name = "Sitcom shuffle",
            shows = listOf(
                ScoutMixShow(
                    id = "tt1", name = "Ted", poster = "p", backdrop = "b", type = "tv", imdbId = "tt1",
                    addonBaseUrl = "https://a", genres = listOf("Comedy", "Drama")
                ),
                ScoutMixShow(id = "kitsu:2", name = "Other", logoChecked = true)
            ),
            unwatchedOnly = false,
            keepPlaying = false,
            autoName = false
        )
        assertEquals(mix, decodeScoutMix(encodeScoutMix(mix)))
    }

    @Test
    fun `a ready-made mix keeps its recipe, date and chosen episodes`() {
        val mix = ScoutMix(
            id = "ready_year_christmas",
            name = "Christmas",
            shows = listOf(ScoutMixShow(id = "tt1", name = "A"), ScoutMixShow(id = "tt2", name = "B")),
            unwatchedOnly = false,
            autoName = false,
            recipe = "o.christmas.x",
            builtAt = 1_700_000_000_000L,
            only = mapOf("tt1" to setOf(2 to 10, 0 to 3), "tt2" to setOf(1 to 11))
        )
        val read = decodeScoutMix(encodeScoutMix(mix))
        assertEquals(mix, read)
        assertEquals(true, read?.readyMade)
        assertEquals(false, decodeScoutMix(encodeScoutMix(mix.copy(id = "abc")))?.readyMade)
    }

    @Test
    fun `an episode without links stays out for a week`() {
        val day = 20_000L
        val once = scoutNoSourcesAdd(null, "tt1" to "tt1:2:3", day)
        assertEquals(setOf("tt1" to "tt1:2:3"), scoutNoSourcesRead(once, day + 6))
        assertEquals(emptySet<Pair<String, String>>(), scoutNoSourcesRead(once, day + 7))
        val later = scoutNoSourcesAdd(once, "tt2" to "tt2:1:1", day + 8)
        assertEquals(setOf("tt2" to "tt2:1:1"), scoutNoSourcesRead(later, day + 8))
        assertEquals(emptySet<Pair<String, String>>(), scoutNoSourcesRead("not json", day))
    }

    @Test
    fun `damaged text is ignored`() {
        assertNull(decodeScoutMix("not json"))
        assertNull(decodeScoutMix("""{"id":"","name":"x","shows":[]}"""))
        assertNull(decodeScoutMix("""{"id":"a","name":"x"}"""))
    }

    @Test
    fun `defaults are on`() {
        val mix = decodeScoutMix("""{"id":"a","name":"x","shows":[]}""")!!
        assertEquals(true, mix.unwatchedOnly)
        assertEquals(true, mix.keepPlaying)
    }

    @Test
    fun `a saved copy remembers the ready-made mix it came from`() {
        val mix = ScoutMix(id = "m2", name = "American", shows = listOf(ScoutMixShow(id = "tt1", name = "A")), savedFrom = "ready_world_us")
        assertEquals("ready_world_us", decodeScoutMix(encodeScoutMix(mix))!!.savedFrom)
        assertNull(decodeScoutMix("""{"id":"a","name":"x","shows":[]}""")!!.savedFrom)
    }
}
