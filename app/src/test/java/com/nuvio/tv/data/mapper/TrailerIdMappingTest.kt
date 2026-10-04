package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.MetaTrailerDto
import com.nuvio.tv.data.remote.dto.TrailerStreamDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerIdMappingTest {

    @Test
    fun `addon trailer ids are reduced to youtube ids`() {
        val trailers = mapTrailers(
            trailers = listOf(
                MetaTrailerDto(source = "dQw4w9WgXcQ", type = "Trailer"),
                MetaTrailerDto(source = "https://www.youtube.com/watch?v=abcdefghijk&t=10"),
                MetaTrailerDto(source = "https://youtu.be/ABCDEFGHIJK"),
                MetaTrailerDto(source = "vi1234567890", name = "IMDb clip"),
                MetaTrailerDto(source = "https://vimeo.com/12345"),
                MetaTrailerDto(source = "not-an-id", ytId = "zyxwvutsrqp")
            ),
            trailerStreams = listOf(
                TrailerStreamDto(ytId = "dQw4w9WgXcQ"),
                TrailerStreamDto(ytId = "https://www.youtube.com/embed/0123456789a"),
                TrailerStreamDto(ytId = "tt0137523")
            )
        )

        assertEquals(
            listOf("dQw4w9WgXcQ", "abcdefghijk", "ABCDEFGHIJK", null, null, "zyxwvutsrqp", "0123456789a"),
            trailers.map { it.ytId }
        )
        assertEquals("vi1234567890", trailers[3].source)
        assertNull(trailers[4].ytId)
    }

    @Test
    fun `trailer id list keeps only playable ids`() {
        val ids = collectTrailerYtIds(
            trailers = listOf(
                MetaTrailerDto(source = "vi1234567890"),
                MetaTrailerDto(source = "https://youtu.be/ABCDEFGHIJK")
            ),
            trailerStreams = null
        )
        assertEquals(listOf("ABCDEFGHIJK"), ids)
    }
}
