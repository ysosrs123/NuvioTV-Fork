package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.player.StreamQualityRank
import com.nuvio.tv.domain.model.DebridStreamAudioTag
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.DebridStreamVisualTag
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveParsed
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ranking cost on a list the size of a busy title (about a thousand sources). */
class StreamFactsSpeedTest {

    private val preferences = DebridStreamPreferences(
        preferredResolutions = listOf(DebridStreamResolution.P2160, DebridStreamResolution.P1080),
        preferredVisualTags = listOf(DebridStreamVisualTag.HDR_DV, DebridStreamVisualTag.DV),
        preferredAudioTags = listOf(DebridStreamAudioTag.ATMOS, DebridStreamAudioTag.TRUEHD),
        preferredEncodes = listOf(DebridStreamEncode.HEVC)
    )

    @Test
    fun `facts and ranking for a thousand sources`() {
        repeat(3) { round -> sources(400, seed = 100 + round).forEach { DirectDebridStreamFilter.factsFor(it, preferences) } }

        val firstMs = mutableListOf<Long>()
        val againMs = mutableListOf<Long>()
        val rankMs = mutableListOf<Long>()
        val rankAgainMs = mutableListOf<Long>()
        var firstBytes = 0L
        repeat(5) { round ->
            val list = sources(SOURCES, seed = round)
            val before = allocated()
            firstMs += timed { list.forEach { DirectDebridStreamFilter.factsFor(it, preferences) } }
            firstBytes = allocated() - before
            againMs += timed { list.forEach { DirectDebridStreamFilter.factsFor(it, preferences) } }
            val other = sources(SOURCES, seed = 50 + round)
            rankMs += timed { StreamQualityRank.rank(other, preferences) }
            rankAgainMs += timed { StreamQualityRank.rank(other, preferences) }
        }
        val digest = factsDigest(sources(SOURCES, seed = 7))
        println(
            "STREAM_FACTS_BENCH sources=$SOURCES first=${median(firstMs)}ms again=${median(againMs)}ms " +
                "rank=${median(rankMs)}ms rankAgain=${median(rankAgainMs)}ms " +
                "firstAllocMb=${firstBytes / 1_000_000} digest=$digest"
        )
    }

    @Test
    fun `facts are the same on a second read of the same source`() {
        val list = sources(200, seed = 3)
        val first = list.map { DirectDebridStreamFilter.factsFor(it, preferences) }
        val copies = list.map { it.copy() }
        assertEquals(first, copies.map { DirectDebridStreamFilter.factsFor(it, preferences) })
        assertEquals(first, list.map { DirectDebridStreamFilter.factsFor(it, preferences) })
    }

    @Test
    fun `cached facts still follow the preferences of each call`() {
        val stream = sources(1, seed = 11).single()
        val plain = DirectDebridStreamFilter.factsFor(stream, DebridStreamPreferences(preferredResolutions = emptyList()))
        val ranked = DirectDebridStreamFilter.factsFor(stream, preferences)
        assertEquals(plain.resolution, ranked.resolution)
        assertEquals(plain.visualTags, ranked.visualTags)
        assertEquals(Int.MAX_VALUE, plain.resolutionRank)
        assertEquals(preferences.preferredResolutions.indexOf(ranked.resolution), ranked.resolutionRank)
    }

    private fun factsDigest(list: List<Stream>): String {
        val text = list.joinToString("\n") {
            DirectDebridStreamFilter.factsFor(it, preferences).toString() + "|" +
                DirectDebridStreamFilter.factsFor(it, DebridStreamPreferences()).toString()
        }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return sha.take(6).joinToString("") { "%02x".format(it) }
    }

    private fun timed(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    private fun allocated(): Long = runCatching {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val method = Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        method.invoke(bean, Thread.currentThread().id) as Long
    }.getOrDefault(0L)

    companion object {
        private const val SOURCES = 1042

        private val resolutions = listOf("2160p", "1080p", "720p", "4K", "2160p", "1080p")
        private val qualities = listOf("BluRay REMUX", "WEB-DL", "WEBRip", "BluRay", "HDTV", "BDRip")
        private val visuals = listOf("DV HDR10", "HDR10+", "DV", "SDR", "", "HDR", "DoVi HDR10 10bit")
        private val audios = listOf("TrueHD Atmos 7.1", "DDP5.1 Atmos", "DTS-HD MA 5.1", "AAC 2.0", "EAC3 5.1", "DTS 5.1", "FLAC 2.0")
        private val codecs = listOf("HEVC", "x265", "AVC", "AV1", "H264", "x264")
        private val groups = listOf("FraMeSToR", "NTb", "FLUX", "CiNEPHiLES", "SPHD", "D-Z0N3", "TEPES", "playWEB")
        private val languages = listOf("English", "Multi", "ITA ENG", "", "French", "English | Spanish")
        private val titles = listOf("The Long Road Home", "Night Harbour", "The Italian Job", "Small Hours", "Glass Coast")

        fun sources(count: Int, seed: Int): List<Stream> = (0 until count).map { index ->
            val n = index * 7 + seed * 13
            val title = titles[n % titles.size]
            val res = resolutions[n % resolutions.size]
            val quality = qualities[(n / 3) % qualities.size]
            val visual = visuals[(n / 5) % visuals.size]
            val audio = audios[(n / 2) % audios.size]
            val codec = codecs[(n / 7) % codecs.size]
            val group = groups[(n / 11) % groups.size]
            val language = languages[(n / 4) % languages.size]
            val sizeGb = 2 + (n % 70)
            val dotted = title.replace(' ', '.')
            val filename = "$dotted.2023.$res.${quality.replace(' ', '.')}.${visual.replace(' ', '.')}.$codec." +
                "${audio.replace(' ', '.')}-$group.mkv"
            val withParsed = index % 3 == 0
            Stream(
                name = "[TB+] AIOStreams $res $seed-$index",
                title = null,
                description = "🎞️ $quality | $visual | $codec\n🎧 $audio\n" +
                    "📦 $sizeGb.4 GB 👥 ${n % 300}\n🔍 $filename\n🌐 $language",
                url = "https://example.invalid/$seed/$index/$filename",
                ytId = null,
                infoHash = null,
                fileIdx = null,
                externalUrl = null,
                behaviorHints = StreamBehaviorHints(
                    notWebReady = null,
                    bingeGroup = "aio|$res|$group",
                    countryWhitelist = null,
                    proxyHeaders = null,
                    videoSize = sizeGb * 1_000_000_000L,
                    filename = filename
                ),
                addonName = "AIOStreams",
                addonLogo = null,
                clientResolve = if (withParsed) resolve(title, filename, res, quality, visual, codec, audio, group, language) else null
            )
        }

        private fun resolve(
            title: String,
            filename: String,
            res: String,
            quality: String,
            visual: String,
            codec: String,
            audio: String,
            group: String,
            language: String
        ) = StreamClientResolve(
            type = "debrid",
            infoHash = filename.hashCode().toString(16),
            fileIdx = 0,
            magnetUri = null,
            sources = null,
            torrentName = filename.removeSuffix(".mkv"),
            filename = filename,
            mediaType = "movie",
            mediaId = "tt1",
            mediaOnlyId = "tt1",
            title = title,
            season = null,
            episode = null,
            service = "torbox",
            serviceIndex = 0,
            serviceExtension = null,
            isCached = true,
            stream = StreamClientResolveStream(
                raw = StreamClientResolveRaw(
                    torrentName = filename.removeSuffix(".mkv"),
                    filename = filename,
                    size = null,
                    folderSize = null,
                    tracker = null,
                    indexer = null,
                    network = null,
                    parsed = StreamClientResolveParsed(
                        rawTitle = filename,
                        parsedTitle = title,
                        year = 2023,
                        resolution = res,
                        seasons = null,
                        episodes = null,
                        quality = quality,
                        hdr = visual.split(' ').filter { it.isNotBlank() },
                        codec = codec,
                        audio = audio.split(' ').filter { it.isNotBlank() && !it[0].isDigit() },
                        channels = audio.split(' ').filter { it.isNotBlank() && it[0].isDigit() },
                        languages = language.split(' ', '|').filter { it.isNotBlank() },
                        group = if (group.length % 2 == 0) group else null,
                        network = null,
                        edition = null,
                        duration = null,
                        bitDepth = null,
                        extended = null,
                        theatrical = null,
                        remastered = null,
                        unrated = null
                    )
                )
            )
        )
    }
}
