package com.nuvio.tv.data.trailer

import org.junit.Assert.*
import org.junit.Test

class ImdbTrailerDataTest {
    private fun title(encodings: String) = """{"props":{"pageProps":{"aboveTheFoldData":{
        "id":"tt1234567","primaryVideos":{"edges":[{"node":{"id":"vi123456789",
        "playbackURLs":[$encodings]}}]}}}}}"""
    private fun encoding(def: String, mime: String = "MP4", extension: String = "mp4") =
        """{"videoDefinition":"$def","videoMimeType":"$mime","url":"https://imdb-video.media-imdb.com/$def.$extension"}"""

    @Test fun `4K wins over 1080p and 720p regardless of provider order`() {
        val parsed = ImdbTrailerData.parse(title(listOf("DEF_1080p", "DEF_2160p", "DEF_720p").joinToString(",") { encoding(it) }), true)
        val video = (parsed as ImdbTrailerData.Result.Found).video
        assertEquals(2160, ImdbTrailerData.bestMp4(video)?.height)
    }

    @Test fun `SD and unknown resolutions never qualify for IMDb playback`() {
        for (definition in listOf("DEF_576p", "DEF_480p", "SD", "AUTO", "")) {
            val video = (ImdbTrailerData.parse(title(encoding(definition)), true) as ImdbTrailerData.Result.Found).video
            assertNull(ImdbTrailerData.bestMp4(video))
        }
    }

    @Test fun `720p qualifies and an adaptive playlist is not treated as progressive MP4`() {
        val video = (ImdbTrailerData.parse(title(encoding("DEF_720p") + "," + encoding("DEF_2160p", "M3U8", "m3u8")), true) as ImdbTrailerData.Result.Found).video
        assertEquals(720, ImdbTrailerData.bestMp4(video)?.height)
    }

    @Test fun `hero without inline URLs is ready to open the video page`() {
        val parsed = ImdbTrailerData.parse(title(""), true) as ImdbTrailerData.Result.Found
        assertEquals("vi123456789", parsed.video.id)
        assertTrue(parsed.video.encodings.isEmpty())
    }

    @Test fun `explicit empty hero is definitive but challenge or changed schema is not`() {
        assertEquals(ImdbTrailerData.Result.Missing,
            ImdbTrailerData.parse("""{"props":{"primaryVideos":{"edges":[]}}}""", true))
        for (json in listOf(null, "pending", "{}", "<html>challenge</html>")) {
            assertEquals(ImdbTrailerData.Result.Pending, ImdbTrailerData.parse(json, true))
        }
    }

    @Test fun `compact browser projection and full video page select the same rendition`() {
        val node = """{"id":"vi123456789","playbackURLs":[${encoding("UHD")},${encoding("HD")}]}"""
        val compact = ImdbTrailerData.parse("""{"status":"found","video":$node}""", true)
        val page = ImdbTrailerData.parse("""{"props":{"pageProps":{"videoPlaybackData":{"video":$node}}}}""", false)
        assertEquals(compact, page)
        assertEquals(2160, ImdbTrailerData.bestMp4((page as ImdbTrailerData.Result.Found).video)?.height)
    }
}
