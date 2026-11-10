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
}
