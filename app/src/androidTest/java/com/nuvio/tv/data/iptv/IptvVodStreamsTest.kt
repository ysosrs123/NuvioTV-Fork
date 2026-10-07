package com.nuvio.tv.data.iptv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.iptv.AcquisitionKey
import com.nuvio.tv.core.iptv.ConsumerReservation
import com.nuvio.tv.core.iptv.DeviceAdmissionLimits
import com.nuvio.tv.core.iptv.LiveAdmissionResult
import com.nuvio.tv.core.iptv.LiveConsumerRole
import com.nuvio.tv.core.iptv.LiveSessionAdmission
import com.nuvio.tv.core.iptv.PlaylistCatalogueParser
import com.nuvio.tv.core.iptv.VodEpisode
import com.nuvio.tv.core.iptv.VodKind
import com.nuvio.tv.core.iptv.VodMovie
import com.nuvio.tv.core.iptv.VodRef
import com.nuvio.tv.core.iptv.VodSeries
import com.nuvio.tv.core.iptv.VodStreams
import java.io.StringReader
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IptvVodStreamsTest {
    private class PlainBox : IptvSecretBox {
        override fun seal(context: String, plaintext: String) = "$context\n$plaintext".toByteArray()
        override fun open(context: String, ciphertext: ByteArray): String = String(ciphertext).also { require(it.startsWith("$context\n")) }.substringAfter('\n')
    }

    @Test fun matchesByIdThenTitleAndBuildsReferenceStreams() = fixture { vod, catalogue, repository ->
        val xtream = catalogue.createSource(1, "Panel", IptvSourceKind.XTREAM, "a", IptvSourceConnection("https://panel.invalid", "user", "secret")).ref
        vod.beginImport(xtream).apply {
            movie(VodMovie("603", "EN - The Matrix (1999) [4K]", extension = "mkv", tmdbId = "603"))
            movie(VodMovie("949", "Heat (1995)", extension = "mp4"))
            series(VodSeries("1396", "|UK| Breaking Bad FHD", 2008, tmdbId = "1396"))
            assertTrue(publish())
        }
        val streams = IptvVodStreams(repository, catalogue, vod)
        assertEquals(listOf(xtream), streams.sources(1, VodKind.MOVIE).map { it.ref })
        assertEquals(listOf(xtream), streams.sources(1, VodKind.SERIES).map { it.ref })
        assertTrue(streams.sources(2, VodKind.MOVIE).isEmpty())
        val source = streams.sources(1, VodKind.MOVIE).single()

        val matrix = streams.direct(1, VodKind.MOVIE, "603", null)
        assertEquals(listOf("603"), matrix.map { it.ref.id })
        val movie = runBlocking { streams.items(source, VodStreams.request("movie", "tmdb:603")!!, matrix) }.single()
        assertEquals("iptv-vod:1:${xtream.sourceId}:movie:603", movie.ref.format())
        assertFalse(movie.ref.format().contains("secret"))
        assertEquals("4K • MKV", movie.text.description)
        assertEquals("The Matrix (1999).mkv", movie.text.filename)

        assertTrue(streams.direct(1, VodKind.MOVIE, "949", null).isEmpty())
        assertEquals(listOf("949"), streams.byTitles(1, VodKind.MOVIE, listOf("Heat", "Heat"), 1995, "949").map { it.ref.id })
        assertTrue(streams.byTitles(1, VodKind.MOVIE, listOf("Heat"), 2010, null).isEmpty())

        val series = VodRef(1, xtream.sourceId, VodKind.SERIES, "1396")
        assertTrue(vod.saveEpisodes(series, listOf(VodEpisode("101", 1, 2, "Cat's in the Bag", "mkv"))))
        val request = VodStreams.request("series", "tt0903747:1:2")!!
        val episode = runBlocking { streams.items(source, request, streams.direct(1, VodKind.SERIES, "1396", null)) }.single()
        assertEquals(VodKind.EPISODE, episode.ref.kind)
        assertEquals("|UK| Breaking Bad FHD · S01E02 · Cat's in the Bag", episode.text.title)
        assertTrue(runBlocking { streams.items(source, VodStreams.request("series", "tt0903747:1:3")!!, streams.direct(1, VodKind.SERIES, "1396", null)) }.isEmpty())

        val before = streams.revision(1)
        repository.setEnabled(xtream, false)
        assertNotEquals(VodStreams.revision(true, before), VodStreams.revision(true, streams.revision(1)))
        assertTrue(streams.sources(1, VodKind.MOVIE).isEmpty())
    }

    @Test fun playlistExtensionsComeFromTheSealedAddress() = fixture { vod, catalogue, repository ->
        val list = catalogue.createSource(2, "List", IptvSourceKind.M3U, "b", IptvSourceConnection("https://lists.invalid/l.m3u")).ref
        val parsed = PlaylistCatalogueParser().parse(StringReader("#EXTM3U\n#EXTINF:-1 group-title=\"VOD\",Heat (1995)\nhttps://p.invalid/movie/u/p/949.m3u8?t=1\n"))
        repository.savePlaylist(list, parsed.vod)
        val streams = IptvVodStreams(repository, catalogue, vod)
        val source = catalogue.sources(2).single()
        val heat = repository.byTitle(2, VodKind.MOVIE, "Heat", 1995)
        val item = runBlocking { streams.items(source, VodStreams.request("movie", "tt0113277")!!, heat) }.single()
        assertEquals("Heat (1995).m3u8", item.text.filename)
        assertFalse(item.toString().contains("p.invalid"))
    }

    @Test fun resolverRefusesWhenTheConnectionsAreInUse() = fixture { vod, catalogue, repository ->
        val ref = catalogue.createSource(1, "Panel", IptvSourceKind.XTREAM, "a", IptvSourceConnection("https://panel.invalid", "user", "secret")).ref
        vod.beginImport(ref).apply { movie(VodMovie("603", "The Matrix", extension = "mkv")); assertTrue(publish()) }
        val admission = LiveSessionAdmission(DeviceAdmissionLimits(4, 1L shl 30, 0))
        val resolver = IptvVodResolver(repository, catalogue, admission)
        val movie = VodRef(1, ref.sourceId, VodKind.MOVIE, "603")
        val ready = runBlocking { resolver.resolve(movie) } as IptvVodResolution.Ready
        assertEquals("https://panel.invalid/movie/user/secret/603.mkv", ready.playback.url)
        assertFalse(ready.toString().contains("secret"))
        val source = catalogue.sources(1).single()
        val live = admission.acquire(AcquisitionKey(admissionAccount(1, source.accountId), "channel", "main", 0), 0,
            ConsumerReservation(LiveConsumerRole.VIEWER, 1, 0))
        assertTrue(live is LiveAdmissionResult.Admitted)
        assertEquals(IptvVodResolution.Busy("Panel"), runBlocking { resolver.resolve(movie) })
        catalogue.saveAccount(1, source.accountId, "Panel", 2)
        assertTrue(runBlocking { resolver.resolve(movie) } is IptvVodResolution.Ready)
        assertEquals(IptvVodResolution.Unavailable, runBlocking { resolver.resolve(VodRef(1, ref.sourceId, VodKind.MOVIE, "999")) })
        assertEquals(IptvVodResolution.Unavailable, runBlocking { resolver.resolve(VodRef(1, "gone", VodKind.MOVIE, "603")) })
    }

    private fun fixture(block: (IptvVodStore, IptvCatalogueStore, IptvVodRepository) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val vodName = "iptv-vod-streams-$id.db"
        val catalogueName = "iptv-vod-streams-catalogue-$id.db"
        try {
            IptvVodStore(context, vodName, PlainBox(), now = { 1234L }, chunkRows = 2).use { vod ->
                IptvCatalogueStore(context, catalogueName, PlainBox()).use { catalogue ->
                    catalogue.addRemovalListener(vod)
                    block(vod, catalogue, IptvVodRepository(vod, catalogue, now = { 1234L }))
                }
            }
        } finally {
            context.deleteDatabase(vodName)
            context.deleteDatabase(catalogueName)
        }
    }
}
