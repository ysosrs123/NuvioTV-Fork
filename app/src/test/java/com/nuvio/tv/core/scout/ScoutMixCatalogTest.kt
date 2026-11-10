package com.nuvio.tv.core.scout

import com.nuvio.tv.core.image.ScoutMixArt
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.ScoutMix
import com.nuvio.tv.domain.model.ScoutMixShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutMixCatalogTest {
    private val words = ScoutMixHomeWords(
        kind = "Mix",
        shows = { "$it shows" },
        about = { "From ${it.joinToString(" + ")}" }
    )

    @Test
    fun `each mix is a dealt card with its shows across the hero`() {
        val mix = ScoutMix(
            "m", "Sitcoms",
            listOf(
                ScoutMixShow("a", "A", poster = "pa", genres = listOf("Comedy", "Drama")),
                ScoutMixShow("b", "B", poster = "pb", backdrop = "bb", genres = listOf("Comedy"))
            )
        )
        val collection = scoutMixCollection("Mixes", listOf(mix), words)
        val folder = collection.folders.single()
        assertEquals(ScoutMixCatalogProvider.COLLECTION_ID, collection.id)
        assertEquals("m", folder.id)
        assertEquals("Sitcoms", folder.title)
        assertEquals(PosterShape.LANDSCAPE, folder.tileShape)
        assertEquals("deck", ScoutMixArt.kind(folder.coverImageUrl!!))
        assertEquals(listOf("l", "pa", "pa", "pb"), ScoutMixArt.pictures(folder.coverImageUrl!!))
        assertEquals("hero", ScoutMixArt.kind(folder.heroBackdropUrl!!))
        assertEquals(listOf("pa", "bb"), ScoutMixArt.pictures(folder.heroBackdropUrl!!))
        val tile = folder.scoutMix!!
        assertEquals("Mix", tile.kind)
        assertEquals("2 shows", tile.shows)
        assertEquals("From A + B", tile.about)
        assertEquals("Comedy", tile.genre)
        assertEquals(listOf("r", "pa", "pa", "pb"), ScoutMixArt.pictures(tile.deckMirrored!!))
    }

    @Test
    fun `the hero shows logos only when every show it names has one`() {
        val some = ScoutMix("m", "M", listOf(ScoutMixShow("a", "A", logo = "la"), ScoutMixShow("b", "B")))
        val all = some.copy(shows = some.shows.map { it.copy(logo = "l" + it.id) })
        assertTrue(scoutMixCollection("Mixes", listOf(some), words).folders.single().scoutMix!!.logos.isEmpty())
        assertEquals(listOf("la", "lb"), scoutMixCollection("Mixes", listOf(all), words).folders.single().scoutMix!!.logos)
    }

    @Test
    fun `picture address keeps every url intact and holds four at most`() {
        val urls = listOf("https://x/a b.jpg?w=1&h=2", "https://y/%20z.png", "https://x/c", "https://x/d", "https://x/e")
        val address = ScoutMixArt.url(urls)!!
        assertTrue(ScoutMixArt.handles(address))
        assertFalse(ScoutMixArt.handles(urls[0]))
        assertEquals(urls.take(4), ScoutMixArt.pictures(address))
        assertEquals(urls.take(3), ScoutMixArt.pictures(ScoutMixArt.hero(urls)!!))
        assertEquals(listOf("l", "-") + urls.take(4), ScoutMixArt.pictures(ScoutMixArt.deck(null, urls, mirrored = false)!!))
    }

    @Test
    fun `no artwork gives no address, and repeats count once`() {
        assertNull(ScoutMixArt.url(emptyList()))
        assertNull(ScoutMixArt.url(listOf("")))
        assertNull(ScoutMixArt.hero(emptyList()))
        assertNull(ScoutMixArt.deck(null, emptyList(), mirrored = false))
        assertEquals(listOf("a"), ScoutMixArt.pictures(ScoutMixArt.url(listOf("a", "a"))!!))
    }

    @Test
    fun `an empty mix still shows a tile`() {
        val folder = scoutMixCollection("Mixes", listOf(ScoutMix("m", "Empty")), words).folders.single()
        assertNull(folder.coverImageUrl)
        assertNull(folder.heroBackdropUrl)
        assertEquals("", folder.scoutMix!!.about)
    }
}
