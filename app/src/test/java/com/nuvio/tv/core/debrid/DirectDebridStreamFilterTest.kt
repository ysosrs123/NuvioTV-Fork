package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamAudioChannel
import com.nuvio.tv.domain.model.DebridStreamAudioTag
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamLanguage
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.DebridStreamSortCriterion
import com.nuvio.tv.domain.model.DebridStreamSortDirection
import com.nuvio.tv.domain.model.DebridStreamSortKey
import com.nuvio.tv.domain.model.DebridStreamVisualTag
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveParsed
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDebridStreamFilterTest {
    @Test
    fun `keeps only cached supported debrid streams and labels source as instant`() {
        val cachedTorbox = stream(
            name = "Direct 1080p",
            resolve = resolve(type = "debrid", service = "torbox", isCached = true)
        )
        val uncachedTorbox = stream(
            resolve = resolve(type = "debrid", service = "torbox", isCached = false)
        )
        val genericTorrent = stream(
            resolve = resolve(type = "torrent", service = null, isCached = null)
        )
        val unsupportedDebrid = stream(
            resolve = resolve(type = "debrid", service = "futurebox", isCached = true)
        )

        val result = DirectDebridStreamFilter.filterInstant(
            listOf(cachedTorbox, uncachedTorbox, genericTorrent, unsupportedDebrid)
        )

        assertEquals(1, result.size)
        assertEquals("Direct 1080p", result.single().name)
        assertEquals("Torbox Instant", result.single().addonName)
        assertTrue(result.single().isDirectDebrid())
        assertFalse(result.single().isTorrent())
    }

    @Test
    fun `uses provider instant name when source stream has no name`() {
        val result = DirectDebridStreamFilter.filterInstant(
            listOf(stream(name = null, resolve = resolve(type = "debrid", service = "realdebrid", isCached = true)))
        )

        assertEquals("Real-Debrid Instant", result.single().name)
    }

    @Test
    fun `preserves original order by default`() {
        val low = stream(name = "Low", resolve = resolve(resolution = "720p", size = 4))
        val large = stream(name = "Large", resolve = resolve(resolution = "2160p", size = 40))
        val mid = stream(name = "Mid", resolve = resolve(resolution = "1080p", size = 10))

        val result = DirectDebridStreamFilter.filterInstant(
            listOf(low, large, mid),
            DebridSettings(
                streamPreferences = DebridStreamPreferences(sortCriteria = DebridStreamSortCriterion.originalOrder)
            )
        )

        assertEquals(listOf("Low", "Large", "Mid"), result.map { it.name })
    }

    @Test
    fun `resolution rules keep a stream off the list but not out of the thumbnail copies`() {
        val uhd = stream(name = "UHD", resolve = resolve(resolution = "2160p", size = 40))
        val hd = stream(name = "HD", resolve = resolve(resolution = "720p", codec = "AVC", size = 4))
        val camHd = stream(name = "CAM", resolve = resolve(resolution = "720p", quality = "CAM", size = 3))
        val sgfHd = stream(name = "SGF", resolve = resolve(resolution = "720p", group = "SGF", size = 5))
        val sgfUhd = stream(name = "SGF UHD", resolve = resolve(resolution = "2160p", group = "SGF", size = 50))
        val preferences = unfiltered.copy(
            excludedResolutions = listOf(DebridStreamResolution.P720),
            excludedQualities = listOf(DebridStreamQuality.CAM),
            excludedReleaseGroups = listOf("SGF")
        )

        val presented = DirectDebridStreamFilter.presentWithCopies(
            listOf(uhd, hd, camHd, sgfHd, sgfUhd),
            DebridSettings(streamPreferences = preferences)
        )

        assertEquals(listOf("UHD"), presented.shown.map { it.name })
        assertEquals(listOf("HD"), presented.thumbnailCopies.map { it.name })
        assertEquals(
            presented.shown,
            DirectDebridStreamFilter.applyPreferences(listOf(uhd, hd, camHd, sgfHd, sgfUhd), DebridSettings(streamPreferences = preferences))
        )
    }

    @Test
    fun `a minimum resolution does not stop a thumbnail copy either`() {
        val fhd = stream(name = "FHD", resolve = resolve(resolution = "1080p", size = 10_000_000_000L))
        val hd = stream(name = "HD", resolve = resolve(resolution = "720p", size = 4_000_000_000L))
        val big = stream(name = "Big HD", resolve = resolve(resolution = "720p", size = 30_000_000_000L))

        val presented = DirectDebridStreamFilter.presentWithCopies(
            listOf(fhd, hd, big),
            DebridSettings(
                streamPreferences = unfiltered.copy(
                    requiredResolutions = listOf(DebridStreamResolution.P2160, DebridStreamResolution.P1080),
                    sizeMaxGb = 20
                )
            )
        )

        assertEquals(listOf("FHD"), presented.shown.map { it.name })
        assertEquals(listOf("HD"), presented.thumbnailCopies.map { it.name })
    }

    @Test
    fun `no resolution rule means no extra copies`() {
        val streams = listOf(
            stream(name = "HD", resolve = resolve(resolution = "720p", size = 4)),
            stream(name = "CAM", resolve = resolve(resolution = "720p", quality = "CAM", size = 3))
        )

        val presented = DirectDebridStreamFilter.presentWithCopies(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(excludedQualities = listOf(DebridStreamQuality.CAM)))
        )

        assertEquals(listOf("HD"), presented.shown.map { it.name })
        assertTrue(presented.thumbnailCopies.isEmpty())
    }

    @Test
    fun `limits and sorts streams by quality and size`() {
        val streams = listOf(
            stream(resolve = resolve(resolution = "1080p", size = 20)),
            stream(resolve = resolve(resolution = "2160p", size = 10)),
            stream(resolve = resolve(resolution = "2160p", size = 30)),
            stream(resolve = resolve(resolution = "720p", size = 40))
        )

        val result = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(
                streamPreferences = DebridStreamPreferences(
                    maxResults = 2,
                    sortCriteria = qualitySort
                )
            )
        )

        assertEquals(listOf(30L, 10L), result.map { it.clientResolve?.stream?.raw?.size })
    }

    @Test
    fun `filters minimum quality dv hdr and codec`() {
        val hdrHevc = stream(
            resolve = resolve(
                resolution = "2160p",
                hdr = listOf("HDR10"),
                codec = "HEVC",
                size = 10
            )
        )
        val dvHevc = stream(
            resolve = resolve(
                resolution = "2160p",
                hdr = listOf("DV", "HDR10"),
                codec = "HEVC",
                size = 20
            )
        )
        val sdrAvc = stream(
            resolve = resolve(
                resolution = "1080p",
                hdr = emptyList(),
                codec = "AVC",
                size = 30
            )
        )
        val hdHevc = stream(
            resolve = resolve(
                resolution = "720p",
                hdr = emptyList(),
                codec = "HEVC",
                size = 40
            )
        )

        val noDvHdrHevc4k = DirectDebridStreamFilter.filterInstant(
            listOf(hdrHevc, dvHevc, sdrAvc, hdHevc),
            DebridSettings(
                streamPreferences = DebridStreamPreferences(
                    requiredResolutions = listOf(DebridStreamResolution.P2160),
                    excludedVisualTags = DebridStreamPreferences().excludedVisualTags + dolbyVisionTags,
                    requiredVisualTags = hdrTags,
                    requiredEncodes = listOf(DebridStreamEncode.HEVC)
                )
            )
        )

        assertEquals(listOf(10L), noDvHdrHevc4k.map { it.clientResolve?.stream?.raw?.size })

        val dvOnly = DirectDebridStreamFilter.filterInstant(
            listOf(hdrHevc, dvHevc, sdrAvc, hdHevc),
            DebridSettings(streamPreferences = DebridStreamPreferences(requiredVisualTags = dolbyVisionTags))
        )

        assertEquals(listOf(20L), dvOnly.map { it.clientResolve?.stream?.raw?.size })
    }

    @Test
    fun `filters provided torbox response through dto mapper path`() {
        val response = Moshi.Builder()
            .build()
            .adapter(StreamResponseDto::class.java)
            .fromJson(torboxDemoFilmResponse)
            ?: error("response not parsed")
        val streams = response.streams.orEmpty()
            .map { it.toDomain(DirectDebridStreamFilter.FALLBACK_SOURCE_NAME, null) }

        assertEquals(7, streams.size)
        assertEquals(7, DirectDebridStreamFilter.filterInstant(streams, DebridSettings(streamPreferences = unfiltered)).size)

        val shippedDefaults = DirectDebridStreamFilter.filterInstant(streams, DebridSettings())

        assertEquals(6, shippedDefaults.size)
        assertTrue(shippedDefaults.none { it.clientResolve?.stream?.raw?.parsed?.group == "NOGRP" })

        val sizeDesc = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(
                streamPreferences = unfiltered.copy(
                    maxResults = 3,
                    sortCriteria = listOf(DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC))
                )
            )
        )

        assertEquals(listOf(24_201_782_274L, 22_719_933_168L, 4_571_523_542L), sizeDesc.map { it.streamSize() })

        val qualityTopTwo = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(
                streamPreferences = unfiltered.copy(
                    maxResults = 2,
                    sortCriteria = qualitySort
                )
            )
        )

        assertEquals(listOf("2160p", "1080p"), qualityTopTwo.map { it.clientResolve?.stream?.raw?.parsed?.resolution })
        assertEquals(4_571_523_542L, qualityTopTwo.first().streamSize())

        val dvOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(requiredVisualTags = dolbyVisionTags))
        )

        assertEquals(listOf(4_571_523_542L), dvOnly.map { it.streamSize() })

        val noDv = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(excludedVisualTags = dolbyVisionTags))
        )

        assertEquals(6, noDv.size)
        assertTrue(noDv.none { it.description.orEmpty().contains("DV", ignoreCase = true) })

        val hdrOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(requiredVisualTags = hdrTags))
        )

        assertEquals(listOf(4_571_523_542L), hdrOnly.map { it.streamSize() })

        val noHdr = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(excludedVisualTags = hdrTags))
        )

        assertEquals(6, noHdr.size)

        val hevcOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(requiredEncodes = listOf(DebridStreamEncode.HEVC)))
        )

        assertEquals(listOf(4_571_523_542L, 3_859_136_613L, 2_946_516_232L), hevcOnly.map { it.streamSize() })

        val avcOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(requiredEncodes = listOf(DebridStreamEncode.AVC)))
        )

        assertEquals(4, avcOnly.size)

        val minimum1080 = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(
                streamPreferences = unfiltered.copy(
                    requiredResolutions = listOf(
                        DebridStreamResolution.P2160,
                        DebridStreamResolution.P1440,
                        DebridStreamResolution.P1080
                    )
                )
            )
        )

        assertEquals(6, minimum1080.size)

        val minimum2160 = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(requiredResolutions = listOf(DebridStreamResolution.P2160)))
        )

        assertEquals(listOf(4_571_523_542L), minimum2160.map { it.streamSize() })
    }

    @Test
    fun `applies default stream preferences`() {
        val remuxAtmos = stream(
            resolve = resolve(
                resolution = "2160p",
                quality = "BluRay REMUX",
                hdr = listOf("HDR10"),
                codec = "HEVC",
                audio = listOf("Atmos", "TrueHD"),
                channels = listOf("7.1"),
                languages = listOf("en"),
                group = "GOOD",
                size = 40_000_000_000
            )
        )
        val webAac = stream(
            resolve = resolve(
                resolution = "2160p",
                quality = "WEB-DL",
                codec = "AVC",
                audio = listOf("AAC"),
                channels = listOf("2.0"),
                languages = listOf("en"),
                group = "NOPE",
                size = 4_000_000_000
            )
        )
        val blurayDts = stream(
            resolve = resolve(
                resolution = "1080p",
                quality = "BluRay",
                codec = "AVC",
                audio = listOf("DTS"),
                channels = listOf("5.1"),
                languages = listOf("hi"),
                group = "GOOD",
                size = 12_000_000_000
            )
        )
        val preferences = DebridStreamPreferences(
            maxResults = 2,
            maxPerResolution = 1,
            sizeMinGb = 5,
            requiredResolutions = listOf(DebridStreamResolution.P2160, DebridStreamResolution.P1080),
            excludedQualities = listOf(DebridStreamQuality.WEB_DL),
            requiredAudioChannels = listOf(DebridStreamAudioChannel.CH_7_1, DebridStreamAudioChannel.CH_5_1),
            excludedEncodes = listOf(DebridStreamEncode.UNKNOWN),
            excludedLanguages = listOf(DebridStreamLanguage.IT),
            requiredReleaseGroups = listOf("GOOD"),
            sortCriteria = listOf(
                DebridStreamSortCriterion(DebridStreamSortKey.AUDIO_TAG, DebridStreamSortDirection.DESC),
                DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.ASC)
            )
        )

        val result = DirectDebridStreamFilter.filterInstant(
            listOf(webAac, blurayDts, remuxAtmos),
            DebridSettings(streamPreferences = preferences)
        )

        assertEquals(listOf(40_000_000_000L, 12_000_000_000L), result.map { it.streamSize() })

        val noHdr = DirectDebridStreamFilter.filterInstant(
            listOf(webAac, blurayDts, remuxAtmos),
            DebridSettings(
                streamPreferences = DebridStreamPreferences(
                    requiredVisualTags = listOf(DebridStreamVisualTag.HDR_ONLY),
                    requiredAudioTags = listOf(DebridStreamAudioTag.ATMOS)
                )
            )
        )

        assertEquals(listOf(40_000_000_000L), noHdr.map { it.streamSize() })
    }

    @Test
    fun `applies every stream preference category to provided example movie response`() {
        val streams = exampleMovieStreams()

        assertEquals(22, streams.size)
        assertEquals(22, DirectDebridStreamFilter.filterInstant(streams, DebridSettings(streamPreferences = unfiltered)).size)
        assertEquals(
            16,
            DirectDebridStreamFilter.filterInstant(
                streams,
                DebridSettings(streamPreferences = DebridStreamPreferences(maxResults = 0))
            ).size
        )
        assertEquals(8, DirectDebridStreamFilter.filterInstant(streams, DebridSettings()).size)

        val sizeDesc = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(
                streamPreferences = unfiltered.copy(
                    maxResults = 3,
                    sortCriteria = listOf(DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC))
                )
            )
        )

        assertEquals(listOf(104_700_022_239L, 96_867_354_460L, 92_910_562_472L), sizeDesc.map { it.streamSize() })

        val perResolution = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(
                streamPreferences = unfiltered.copy(
                    maxPerResolution = 1,
                    sortCriteria = listOf(DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC))
                )
            )
        )

        assertEquals(
            listOf(104_700_022_239L, 51_056_367_324L, 14_559_208_901L, 13_916_786_260L, 5_371_431_742L, 1_468_475_610L),
            perResolution.map { it.streamSize() }
        )

        val sizeRange = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(sizeMinGb = 10, sizeMaxGb = 20))
        )

        assertEquals(listOf(14_559_208_901L, 13_916_786_260L), sizeRange.map { it.streamSize() })

        val required1440 = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(requiredResolutions = listOf(DebridStreamResolution.P1440)))
        )

        assertEquals(listOf(14_559_208_901L, 4_622_252_199L), required1440.map { it.streamSize() })

        val no720 = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(excludedResolutions = listOf(DebridStreamResolution.P720)))
        )

        assertEquals(17, no720.size)
        assertTrue(no720.none { it.description.orEmpty().contains("720p", ignoreCase = true) })

        val webDlOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(requiredQualities = listOf(DebridStreamQuality.WEB_DL)))
        )

        assertEquals(listOf(13_916_786_260L), webDlOnly.map { it.streamSize() })

        val noWebDl = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(excludedQualities = listOf(DebridStreamQuality.WEB_DL)))
        )

        assertEquals(21, noWebDl.size)

        val hdrDvOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(requiredVisualTags = listOf(DebridStreamVisualTag.HDR_DV)))
        )

        assertEquals(listOf(92_910_562_472L, 96_867_354_460L, 87_052_038_851L, 89_079_717_868L), hdrDvOnly.map { it.streamSize() })

        val dvOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(requiredVisualTags = listOf(DebridStreamVisualTag.DV_ONLY)))
        )

        assertEquals(listOf(89_100_999_892L), dvOnly.map { it.streamSize() })

        val hdrOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(requiredVisualTags = listOf(DebridStreamVisualTag.HDR_ONLY)))
        )

        assertEquals(listOf(14_559_208_901L, 4_622_252_199L), hdrOnly.map { it.streamSize() })

        val noImax = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(excludedVisualTags = listOf(DebridStreamVisualTag.IMAX)))
        )

        assertEquals(18, noImax.size)

        val ddpOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(requiredAudioTags = listOf(DebridStreamAudioTag.DD_PLUS)))
        )

        assertEquals(listOf(92_910_562_472L, 87_052_038_851L, 13_916_786_260L), ddpOnly.map { it.streamSize() })

        val fiveOneOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(requiredAudioChannels = listOf(DebridStreamAudioChannel.CH_5_1)))
        )

        assertTrue(fiveOneOnly.map { it.streamSize() }.contains(1_112_357_595L))

        val av1Only = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(requiredEncodes = listOf(DebridStreamEncode.AV1)))
        )

        assertEquals(listOf(14_559_208_901L), av1Only.map { it.streamSize() })

        val xvidOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(requiredEncodes = listOf(DebridStreamEncode.XVID)))
        )

        assertEquals(listOf(1_468_475_610L), xvidOnly.map { it.streamSize() })

        val polishOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(requiredLanguages = listOf(DebridStreamLanguage.PL)))
        )

        assertEquals(listOf(96_867_354_460L, 51_056_367_324L, 48_286_797_994L, 1_468_475_610L), polishOnly.map { it.streamSize() })

        val latinoOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(requiredLanguages = listOf(DebridStreamLanguage.LA)))
        )

        assertEquals(listOf(92_910_562_472L, 87_052_038_851L), latinoOnly.map { it.streamSize() })

        val sgfOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(requiredReleaseGroups = listOf("SGF")))
        )

        assertEquals(listOf(96_867_354_460L, 51_056_367_324L), sgfOnly.map { it.streamSize() })

        val noSgf = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = unfiltered.copy(excludedReleaseGroups = listOf("SGF")))
        )

        assertEquals(20, noSgf.size)

        val latinoFirst = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(
                streamPreferences = unfiltered.copy(
                    maxResults = 2,
                    preferredLanguages = listOf(DebridStreamLanguage.LA),
                    sortCriteria = listOf(DebridStreamSortCriterion(DebridStreamSortKey.LANGUAGE, DebridStreamSortDirection.DESC))
                )
            )
        )

        assertEquals(listOf(92_910_562_472L, 87_052_038_851L), latinoFirst.map { it.streamSize() })
    }

    private val unfiltered = DebridStreamPreferences(
        maxResults = 0,
        requiredResolutions = emptyList(),
        excludedQualities = emptyList(),
        excludedVisualTags = emptyList(),
        excludedEncodes = emptyList(),
        excludedReleaseGroups = emptyList(),
        sortCriteria = DebridStreamSortCriterion.originalOrder
    )

    private val qualitySort = listOf(
        DebridStreamSortCriterion(DebridStreamSortKey.RESOLUTION, DebridStreamSortDirection.DESC),
        DebridStreamSortCriterion(DebridStreamSortKey.QUALITY, DebridStreamSortDirection.DESC),
        DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC)
    )

    private val dolbyVisionTags = listOf(
        DebridStreamVisualTag.DV,
        DebridStreamVisualTag.DV_ONLY,
        DebridStreamVisualTag.HDR_DV
    )

    private val hdrTags = listOf(
        DebridStreamVisualTag.HDR,
        DebridStreamVisualTag.HDR10,
        DebridStreamVisualTag.HDR10_PLUS,
        DebridStreamVisualTag.HLG,
        DebridStreamVisualTag.HDR_ONLY,
        DebridStreamVisualTag.HDR_DV
    )
    @Test
    fun `reads languages from release name words when the stream has no parsed languages`() {
        fun languages(releaseName: String) = DirectDebridStreamFilter.facts(
            stream(resolve = resolve()).copy(description = releaseName),
            DebridSettings()
        ).languages

        assertEquals(
            listOf(DebridStreamLanguage.EN, DebridStreamLanguage.IT),
            languages("Movie.2023.1080p.WEB-DL.ITA.ENG.x264-GRP.mkv")
        )
        assertEquals(listOf(DebridStreamLanguage.FR), languages("Movie.2023.TRUEFRENCH.1080p.WEB-DL.x265-GRP.mkv"))
        assertEquals(listOf(DebridStreamLanguage.FR), languages("Movie.2023.FRENCH.1080p.WEB-DL.x265-GRP.mkv"))
        assertEquals(listOf(DebridStreamLanguage.DE), languages("Movie.2023.German.DL.1080p.BluRay.x264-GRP.mkv"))
        assertEquals(listOf(DebridStreamLanguage.ES), languages("Movie (2023) [BluRay][Castellano].mkv"))
        assertEquals(listOf(DebridStreamLanguage.MULTI), languages("Movie.2023.MULTI.1080p.BluRay.x264-GRP.mkv"))
        assertEquals(emptyList<DebridStreamLanguage>(), languages("Por.Siempre.2023.1080p.WEB-DL.x264-GRP.mkv"))
        assertEquals(emptyList<DebridStreamLanguage>(), languages("Movie.2023.1080p.WEB-DL.x264-GRP.mkv"))
    }

    @Test
    fun `accepts release name words in parsed languages`() {
        val facts = DirectDebridStreamFilter.facts(
            stream(resolve = resolve(languages = listOf("ita", "Eng", "klingon"))),
            DebridSettings()
        )

        assertEquals(listOf(DebridStreamLanguage.IT, DebridStreamLanguage.EN), facts.languages)
    }

    @Test
    fun `required language keeps streams whose release name only has the language word`() {
        val italian = stream(resolve = resolve(size = 1))
            .copy(description = "Movie.2023.1080p.WEB-DL.ITA.x264-GRP.mkv")
        val english = stream(resolve = resolve(size = 2))
            .copy(description = "Movie.2023.1080p.WEB-DL.ENG.x264-GRP.mkv")

        val result = DirectDebridStreamFilter.filterInstant(
            listOf(italian, english),
            DebridSettings(streamPreferences = DebridStreamPreferences(requiredLanguages = listOf(DebridStreamLanguage.IT)))
        )

        assertEquals(listOf(1L), result.map { it.streamSize() })
    }

    @Test
    fun `does not read title words as the stream language`() {
        fun languages(releaseName: String, title: String) = DirectDebridStreamFilter.facts(
            stream(resolve = resolve().copy(title = title)).copy(description = releaseName),
            DebridSettings()
        ).languages

        assertEquals(
            emptyList<DebridStreamLanguage>(),
            languages("The.French.Example.2021.1080p.BluRay.x264-GRP.mkv", "The French Example")
        )
        assertEquals(
            listOf(DebridStreamLanguage.EN),
            languages("The.Italian.Example.2003.1080p.BluRay.ENG.x264-GRP.mkv", "The Italian Example")
        )
        assertEquals(
            listOf(DebridStreamLanguage.IT),
            languages("The Italian Example (2003) 1080p BluRay ITA AC3.mkv", "The Italian Example")
        )
        assertEquals(listOf(DebridStreamLanguage.FR), languages("French.2020.FRENCH.1080p.WEB-DL.x264-GRP.mkv", "French"))
    }

    @Test
    fun `does not read the parsed title as the stream language`() {
        val resolve = resolve()
        val parsed = resolve.stream?.raw?.parsed?.copy(parsedTitle = "The French Example")
        val facts = DirectDebridStreamFilter.facts(
            stream(resolve = resolve.copy(stream = StreamClientResolveStream(resolve.stream?.raw?.copy(parsed = parsed))))
                .copy(description = "The.French.Example.2021.1080p.BluRay.ITA.x264-GRP.mkv"),
            DebridSettings()
        )

        assertEquals(listOf(DebridStreamLanguage.IT), facts.languages)
    }

    @Test
    fun `required language hides streams whose title only has the language word`() {
        val italianTitle = stream(resolve = resolve(size = 1).copy(title = "The Italian Example"))
            .copy(description = "The.Italian.Example.2003.1080p.BluRay.ENG.x264-GRP.mkv")
        val italianAudio = stream(resolve = resolve(size = 2).copy(title = "The Italian Example"))
            .copy(description = "The.Italian.Example.2003.1080p.BluRay.ITA.x264-GRP.mkv")

        val result = DirectDebridStreamFilter.filterInstant(
            listOf(italianTitle, italianAudio),
            DebridSettings(streamPreferences = DebridStreamPreferences(requiredLanguages = listOf(DebridStreamLanguage.IT)))
        )

        assertEquals(listOf(2L), result.map { it.streamSize() })
    }

    private fun Stream.streamSize(): Long? = clientResolve?.stream?.raw?.size ?: behaviorHints?.videoSize

    private fun exampleMovieStreams(): List<Stream> {
        val response = Moshi.Builder()
            .build()
            .adapter(StreamResponseDto::class.java)
            .fromJson(exampleMovieResponse)
            ?: error("response not parsed")
        return response.streams.orEmpty()
            .map { it.toDomain(DirectDebridStreamFilter.FALLBACK_SOURCE_NAME, null) }
    }

    private fun stream(
        name: String? = "Stream",
        resolve: StreamClientResolve?
    ): Stream = Stream(
        name = name,
        title = "Title",
        description = "Description",
        url = null,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Direct Debrid",
        addonLogo = null,
        clientResolve = resolve
    )

    private fun resolve(
        type: String? = "debrid",
        service: String? = "torbox",
        isCached: Boolean? = true,
        resolution: String? = null,
        quality: String? = null,
        hdr: List<String> = emptyList(),
        codec: String? = null,
        audio: List<String>? = null,
        channels: List<String>? = null,
        languages: List<String>? = null,
        group: String? = null,
        size: Long? = null
    ): StreamClientResolve = StreamClientResolve(
        type = type,
        infoHash = "abc${size ?: ""}",
        fileIdx = size?.toInt() ?: 1,
        magnetUri = "magnet:?xt=urn:btih:abc",
        sources = null,
        torrentName = "Torrent",
        filename = "video ${resolution.orEmpty()} ${quality.orEmpty()} ${codec.orEmpty()}.mkv",
        mediaType = "movie",
        mediaId = "tt1",
        mediaOnlyId = "tt1",
        title = "Title",
        season = null,
        episode = null,
        service = service,
        serviceIndex = 0,
        serviceExtension = null,
        isCached = isCached,
        stream = StreamClientResolveStream(
            raw = StreamClientResolveRaw(
                torrentName = "Torrent ${resolution.orEmpty()} ${quality.orEmpty()}",
                filename = "video ${resolution.orEmpty()} ${quality.orEmpty()} ${codec.orEmpty()}.mkv",
                size = size,
                folderSize = size,
                tracker = null,
                indexer = null,
                network = null,
                parsed = StreamClientResolveParsed(
                    rawTitle = null,
                    parsedTitle = null,
                    year = null,
                    resolution = resolution,
                    seasons = null,
                    episodes = null,
                    quality = quality,
                    hdr = hdr,
                    codec = codec,
                    audio = audio,
                    channels = channels,
                    languages = languages,
                    group = group,
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

    private val exampleMovieResponse = """
        {
          "streams": [
            {"name":"TB 2160p cached","description":"Example.Movie.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DTS-HD.Master.DDP5.1.DV.x265.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"0101010101010101010101010101010101010101","sources":[],"fileIdx":0,"filename":"Example.Movie.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DTS-HD.Master.DDP5.1.DV.x265.mkv","title":"Example Movie","torrentName":"Example.Movie.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DTS-HD.Master.DDP5.1.DV.x265.mkv","stream":{"raw":{"parsed":{"resolution":"2160p","quality":"REMUX","codec":"hevc","audio":["DTS Lossless","Dolby Digital Plus"],"channels":["5.1"],"hdr":["DV","HDR10+"],"languages":["multi","en","la","it"],"year":2023}}}},"behaviorHints":{"filename":"Example.Movie.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DTS-HD.Master.DDP5.1.DV.x265.mkv","videoSize":92910562472}},
            {"name":"TB 2160p cached","description":"Example.Movie.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.2160p.BluRay.Hybrid.Remux.DV.HDR.HEVC.DTS-HD.MA-SGF.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"0202020202020202020202020202020202020202","sources":[],"fileIdx":0,"filename":"Example.Movie.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.2160p.BluRay.Hybrid.Remux.DV.HDR.HEVC.DTS-HD.MA-SGF.mkv","title":"Example Movie","torrentName":"Example.Movie.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.2160p.BluRay.Hybrid.Remux.DV.HDR.HEVC.DTS-HD.MA-SGF.mkv","stream":{"raw":{"parsed":{"resolution":"2160p","quality":"BluRay REMUX","codec":"hevc","audio":["DTS Lossless"],"hdr":["DV","HDR"],"languages":["multi","en","fr","es","it","de","pl","cs"],"group":"SGF","year":2023}}}},"behaviorHints":{"filename":"Example.Movie.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.2160p.BluRay.Hybrid.Remux.DV.HDR.HEVC.DTS-HD.MA-SGF.mkv","videoSize":96867354460}},
            {"name":"TB 2160p cached","description":"Example.Movie.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DDP5.1.DV.x265.MP4-BEN.THE.MEN.mp4","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"0303030303030303030303030303030303030303","sources":[],"fileIdx":0,"filename":"Example.Movie.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DDP5.1.DV.x265.MP4-BEN.THE.MEN.mp4","title":"Example Movie","torrentName":"Example.Movie.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DDP5.1.DV.x265.MP4-BEN.THE.MEN.mp4","stream":{"raw":{"parsed":{"resolution":"2160p","quality":"REMUX","codec":"hevc","audio":["Dolby Digital Plus"],"channels":["5.1"],"hdr":["DV","HDR10+"],"languages":["multi","en","la","it"],"year":2023}}}},"behaviorHints":{"filename":"Example.Movie.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DDP5.1.DV.x265.MP4-BEN.THE.MEN.mp4","videoSize":87052038851}},
            {"name":"TB 2160p cached","description":"Example.Movie.2023.IMAX.4K.HDR.DV.2160p BDRemux Ita Eng x265-NAHOM.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"0404040404040404040404040404040404040404","sources":[],"fileIdx":0,"filename":"Example.Movie.2023.IMAX.4K.HDR.DV.2160p BDRemux Ita Eng x265-NAHOM.mkv","title":"Example Movie","torrentName":"Example.Movie.2023.IMAX.4K.HDR.DV.2160p BDRemux Ita Eng x265-NAHOM.mkv","stream":{"raw":{"parsed":{"resolution":"2160p","quality":"BluRay REMUX","codec":"hevc","hdr":["DV","HDR"],"languages":["multi","en","it"],"group":"NAHOM","year":2023}}}},"behaviorHints":{"filename":"Example.Movie.2023.IMAX.4K.HDR.DV.2160p BDRemux Ita Eng x265-NAHOM.mkv","videoSize":89079717868}},
            {"name":"TB 2160p cached","description":"Example.Movie.2023.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HEVC.HYBRID.REMUX-FraMeSToR.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"0505050505050505050505050505050505050505","sources":[],"fileIdx":0,"filename":"Example.Movie.2023.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HEVC.HYBRID.REMUX-FraMeSToR.mkv","title":"Example Movie","torrentName":"Example.Movie.2023.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HEVC.HYBRID.REMUX-FraMeSToR.mkv","stream":{"raw":{"parsed":{"resolution":"2160p","quality":"BluRay REMUX","codec":"hevc","audio":["DTS Lossless"],"channels":["5.1"],"hdr":["DV"],"group":"FraMeSToR","year":2023}}}},"behaviorHints":{"filename":"Example.Movie.2023.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HEVC.HYBRID.REMUX-FraMeSToR.mkv","videoSize":89100999892}},
            {"name":"TB 1440p cached","description":"Example.Movie.2023.1440p.HDR.DTS-HD.AV1.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"0606060606060606060606060606060606060606","sources":[],"fileIdx":0,"filename":"Example.Movie.2023.1440p.HDR.DTS-HD.AV1.mkv","title":"Example Movie","torrentName":"Example.Movie.2023.1440p.HDR.DTS-HD.AV1.mkv","stream":{"raw":{"parsed":{"resolution":"1440p","quality":"HDTV","codec":"av1","audio":["DTS Lossy"],"hdr":["HDR"],"year":2023}}}},"behaviorHints":{"filename":"Example.Movie.2023.1440p.HDR.DTS-HD.AV1.mkv","videoSize":14559208901}},
            {"name":"TB 1080p cached","description":"Example.Movie.2023.FANSUB.VOSTFR.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-Slay3R.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"0707070707070707070707070707070707070707","sources":[],"filename":"Example.Movie.2023.FANSUB.VOSTFR.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-Slay3R.mkv","title":"Example Movie","torrentName":"Example.Movie.2023.FANSUB.VOSTFR.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-Slay3R.mkv","stream":{"raw":{"parsed":{"resolution":"1080p","quality":"BluRay REMUX","codec":"avc","audio":["DTS Lossless"],"channels":["5.1"],"languages":["fr"],"group":"Slay3R","year":2023}}}},"behaviorHints":{"filename":"Example.Movie.2023.FANSUB.VOSTFR.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-Slay3R.mkv","videoSize":41891055382}},
            {"name":"TB 1080p cached","description":"Example.Movie.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.1080p.BluRay.Remux.AVC.DTS-HD.MA-SGF.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"0808080808080808080808080808080808080808","sources":[],"fileIdx":0,"filename":"Example.Movie.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.1080p.BluRay.Remux.AVC.DTS-HD.MA-SGF.mkv","title":"Example Movie","torrentName":"Example.Movie.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.1080p.BluRay.Remux.AVC.DTS-HD.MA-SGF.mkv","stream":{"raw":{"parsed":{"resolution":"1080p","quality":"BluRay REMUX","codec":"avc","audio":["DTS Lossless"],"languages":["multi","en","fr","es","it","de","pl","cs"],"group":"SGF","year":2023}}}},"behaviorHints":{"filename":"Example.Movie.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.1080p.BluRay.Remux.AVC.DTS-HD.MA-SGF.mkv","videoSize":51056367324}},
            {"name":"TB 1080p cached","description":"Example.Movie.2023.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-FraMeSToR.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"0909090909090909090909090909090909090909","sources":[],"fileIdx":0,"filename":"Example.Movie.2023.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-FraMeSToR.mkv","title":"Example Movie","torrentName":"Example.Movie.2023.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-FraMeSToR.mkv","stream":{"raw":{"parsed":{"resolution":"1080p","quality":"BluRay REMUX","codec":"avc","audio":["DTS Lossless"],"channels":["5.1"],"group":"FraMeSToR","year":2023}}}},"behaviorHints":{"filename":"Example.Movie.2023.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-FraMeSToR.mkv","videoSize":42601767435}},
            {"name":"TB 1080p cached","description":"Example.Movie.2023.CZ.EN.ES.PL.1080p.Blu-Ray.Remux.AVC.DTS-HD.MA 5.1.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"1010101010101010101010101010101010101010","sources":[],"fileIdx":0,"filename":"Example.Movie.2023.CZ.EN.ES.PL.1080p.Blu-Ray.Remux.AVC.DTS-HD.MA 5.1.mkv","title":"Example Movie","torrentName":"Example.Movie.2023.CZ.EN.ES.PL.1080p.Blu-Ray.Remux.AVC.DTS-HD.MA 5.1.mkv","stream":{"raw":{"parsed":{"resolution":"1080p","quality":"BluRay REMUX","codec":"avc","audio":["DTS Lossless"],"channels":["5.1"],"languages":["multi","en","es","pl","cs"],"year":2023}}}},"behaviorHints":{"filename":"Example.Movie.2023.CZ.EN.ES.PL.1080p.Blu-Ray.Remux.AVC.DTS-HD.MA 5.1.mkv","videoSize":48286797994}},
            {"name":"TB 1080p cached","description":"Example.Movie.2023.MULTI.VF2.1080p.BluRay.Remux.DTS-HD.MA.5.1.AVC-HYPERION.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"1111111111111111111111111111111111111111","sources":[],"fileIdx":0,"filename":"Example.Movie.2023.MULTI.VF2.1080p.BluRay.Remux.DTS-HD.MA.5.1.AVC-HYPERION.mkv","title":"Example Movie","torrentName":"Example.Movie.2023.MULTI.VF2.1080p.BluRay.Remux.DTS-HD.MA.5.1.AVC-HYPERION.mkv","stream":{"raw":{"parsed":{"resolution":"1080p","quality":"BluRay REMUX","codec":"avc","audio":["DTS Lossless"],"channels":["5.1"],"languages":["multi","fr"],"group":"HYPERION","year":2023}}}},"behaviorHints":{"filename":"Example.Movie.2023.MULTI.VF2.1080p.BluRay.Remux.DTS-HD.MA.5.1.AVC-HYPERION.mkv","videoSize":44082903325}},
            {"name":"TB 720p cached","description":"Example.Movie.2023.720p.MA.WEB-DL.DUAL.DD+5.1.H.264-TheBiscuitMan.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"1212121212121212121212121212121212121212","sources":[],"fileIdx":0,"filename":"Example.Movie.2023.720p.MA.WEB-DL.DUAL.DD+5.1.H.264-TheBiscuitMan.mkv","title":"Example Movie","torrentName":"Example.Movie.2023.720p.MA.WEB-DL.DUAL.DD+5.1.H.264-TheBiscuitMan.mkv","stream":{"raw":{"parsed":{"resolution":"720p","quality":"WEB-DL","codec":"avc","audio":["Dolby Digital Plus"],"channels":["5.1"],"languages":["multi"],"group":"TheBiscuitMan","year":2023}}}},"behaviorHints":{"filename":"Example.Movie.2023.720p.MA.WEB-DL.DUAL.DD+5.1.H.264-TheBiscuitMan.mkv","videoSize":13916786260}},
            {"name":"TB 720p cached","description":"Example.Movie.2023.720p.BluRay.x264.AAC-[YTS.MX].mp4","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"1313131313131313131313131313131313131313","sources":[],"fileIdx":0,"filename":"Example.Movie.2023.720p.BluRay.x264.AAC-[YTS.MX].mp4","title":"Example Movie","torrentName":"Example.Movie.2023.720p.BluRay.x264.AAC-[YTS.MX].mp4","stream":{"raw":{"parsed":{"resolution":"720p","quality":"BluRay","codec":"avc","audio":["AAC"],"year":2023}}}},"behaviorHints":{"filename":"Example.Movie.2023.720p.BluRay.x264.AAC-[YTS.MX].mp4","videoSize":1741975811}},
            {"name":"TB 720p cached","description":"Example.Movie.2023.720p.10bit.BluRay.6CH.x265.HEVC-PSA.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"1414141414141414141414141414141414141414","sources":[],"fileIdx":0,"filename":"Example.Movie.2023.720p.10bit.BluRay.6CH.x265.HEVC-PSA.mkv","title":"Example Movie","torrentName":"Example.Movie.2023.720p.10bit.BluRay.6CH.x265.HEVC-PSA.mkv","stream":{"raw":{"parsed":{"resolution":"720p","quality":"BluRay","codec":"hevc","group":"PSA","year":2023}}}},"behaviorHints":{"filename":"Example.Movie.2023.720p.10bit.BluRay.6CH.x265.HEVC-PSA.mkv","videoSize":1112357595}},
            {"name":"TB 720p cached","description":"[ Example.org ] Example.Movie.2023.VFQ.720p.BluRay.x264-Slay3R.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"1515151515151515151515151515151515151515","sources":[],"fileIdx":0,"filename":"[ Example.org ] Example.Movie.2023.VFQ.720p.BluRay.x264-Slay3R.mkv","title":"Example Movie","torrentName":"[ Example.org ] Example.Movie.2023.VFQ.720p.BluRay.x264-Slay3R.mkv","stream":{"raw":{"parsed":{"resolution":"720p","quality":"BluRay","codec":"avc","languages":["fr"],"group":"Slay3R","year":2023}}}},"behaviorHints":{"filename":"[ Example.org ] Example.Movie.2023.VFQ.720p.BluRay.x264-Slay3R.mkv","videoSize":8401763164}},
            {"name":"TB 720p cached","description":"Example Movie (2023) 720p h264 Ac3 5.1 Ita Eng Sub Ita Emg-MIRCrew.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"1616161616161616161616161616161616161616","sources":[],"fileIdx":0,"filename":"Example Movie (2023) 720p h264 Ac3 5.1 Ita Eng Sub Ita Emg-MIRCrew.mkv","title":"Example Movie","torrentName":"Example Movie (2023) 720p h264 Ac3 5.1 Ita Eng Sub Ita Emg-MIRCrew.mkv","stream":{"raw":{"parsed":{"resolution":"720p","codec":"avc","audio":["Dolby Digital"],"channels":["5.1"],"languages":["multi","en","it"],"group":"MIRCrew","year":2023}}}},"behaviorHints":{"filename":"Example Movie (2023) 720p h264 Ac3 5.1 Ita Eng Sub Ita Emg-MIRCrew.mkv","videoSize":3951560261}},
            {"name":"TB 480p cached","description":"Example.Movie.2023.PL.480p.IMAX.BDRip.XviD-K83.avi","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"1717171717171717171717171717171717171717","sources":[],"fileIdx":0,"filename":"Example.Movie.2023.PL.480p.IMAX.BDRip.XviD-K83.avi","title":"Example Movie","torrentName":"Example.Movie.2023.PL.480p.IMAX.BDRip.XviD-K83.avi","stream":{"raw":{"parsed":{"resolution":"480p","quality":"BDRip","codec":"xvid","languages":["pl"],"year":2023}}}},"behaviorHints":{"filename":"Example.Movie.2023.PL.480p.IMAX.BDRip.XviD-K83.avi","videoSize":1468475610}},
            {"name":"TB unknown cached","description":"Example.Movie.UHD.REMUX.MULTI.DTS.HD.MA-TABGPT.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"1818181818181818181818181818181818181818","sources":[],"filename":"Example.Movie.UHD.REMUX.MULTI.DTS.HD.MA-TABGPT.mkv","title":"Example Movie","torrentName":"Example.Movie.UHD.REMUX.MULTI.DTS.HD.MA-TABGPT.mkv","stream":{"raw":{"parsed":{"resolution":"unknown","quality":"REMUX","audio":["DTS Lossless"],"languages":["multi"],"group":"TABGPT"}}}},"behaviorHints":{"filename":"Example.Movie.UHD.REMUX.MULTI.DTS.HD.MA-TABGPT.mkv","videoSize":89021342424}},
            {"name":"TB unknown cached","description":"Example Movie (2023) - UHD BD-Remux by Wild_Cat.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"1919191919191919191919191919191919191919","sources":[],"fileIdx":0,"filename":"Example Movie (2023) - UHD BD-Remux by Wild_Cat.mkv","title":"Example Movie","torrentName":"Example Movie (2023) - UHD BD-Remux by Wild_Cat.mkv","stream":{"raw":{"parsed":{"resolution":"unknown","quality":"BluRay REMUX","year":2023}}}},"behaviorHints":{"filename":"Example Movie (2023) - UHD BD-Remux by Wild_Cat.mkv","videoSize":104700022239}},
            {"name":"TB unknown cached","description":"Example Movie (2023) [Bluray][Esp](example.com).avi","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"2020202020202020202020202020202020202020","sources":[],"fileIdx":0,"filename":"Example Movie (2023) [Bluray][Esp](example.com).avi","title":"Example Movie","torrentName":"Example Movie (2023) [Bluray][Esp](example.com).avi","stream":{"raw":{"parsed":{"resolution":"unknown","quality":"BluRay","languages":["es"],"year":2023}}}},"behaviorHints":{"filename":"Example Movie (2023) [Bluray][Esp](example.com).avi","videoSize":2163519374}},
            {"name":"TB unknown cached","description":"Example.Movie.2023.2K.HLG.Eng.Fre.Ger.Ita.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"2121212121212121212121212121212121212121","sources":[],"fileIdx":0,"filename":"Example.Movie.2023.2K.HLG.Eng.Fre.Ger.Ita.mkv","title":"Example Movie","torrentName":"Example.Movie.2023.2K.HLG.Eng.Fre.Ger.Ita.mkv","stream":{"raw":{"parsed":{"resolution":"unknown","languages":["multi","en","fr","it","de"],"year":2023}}}},"behaviorHints":{"filename":"Example.Movie.2023.2K.HLG.Eng.Fre.Ger.Ita.mkv","videoSize":4622252199}},
            {"name":"TB unknown cached","description":"Example.Movie.(2023).[tmdbId=12345].mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"2222222222222222222222222222222222222222","sources":[],"filename":"Example.Movie.(2023).[tmdbId=12345].mkv","title":"Example Movie","torrentName":"Example.Movie.(2023).[tmdbId=12345].mkv","stream":{"raw":{"parsed":{"resolution":"unknown","year":2023}}}},"behaviorHints":{"filename":"Example.Movie.(2023).[tmdbId=12345].mkv","videoSize":5371431742}}
          ],
          "resolveMode": "client"
        }
    """.trimIndent()

    private val torboxDemoFilmResponse = """
        {
          "streams": [
            {
              "name": "TB 2160p cached",
              "description": "Demo Film (2010) UpScaled 2160p H265 10 bit DV HDR10+ ita eng AC3 5.1 sub ita eng Licdom.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:2323232323232323232323232323232323232323&dn=Demo%20Film%20%282010%29%20UpScaled%202160p%20H265%2010%20bit%20DV%20HDR10%2B%20ita%20eng%20AC3%205.1%20sub%20ita%20eng%20Licdom.mkv",
                "infoHash": "2323232323232323232323232323232323232323",
                "sources": [],
                "fileIdx": 0,
                "filename": "Demo Film (2010) UpScaled 2160p H265 10 bit DV HDR10+ ita eng AC3 5.1 sub ita eng Licdom.mkv",
                "title": "Demo Film",
                "torrentName": "Demo Film (2010) UpScaled 2160p H265 10 bit DV HDR10+ ita eng AC3 5.1 sub ita eng Licdom.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "2160p",
                      "codec": "hevc",
                      "audio": ["Dolby Digital"],
                      "channels": ["5.1"],
                      "hdr": ["DV", "HDR10+"],
                      "languages": ["multi", "en", "it"],
                      "year": 2010,
                      "raw_title": "Demo Film (2010) UpScaled 2160p H265 10 bit DV HDR10+ ita eng AC3 5.1 sub ita eng Licdom.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Demo Film (2010) UpScaled 2160p H265 10 bit DV HDR10+ ita eng AC3 5.1 sub ita eng Licdom.mkv",
                "videoSize": 4571523542
              }
            },
            {
              "name": "TB 1080p cached",
              "description": "Demo.Film.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:2424242424242424242424242424242424242424&dn=Demo.Film.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv",
                "infoHash": "2424242424242424242424242424242424242424",
                "sources": [],
                "fileIdx": 0,
                "filename": "Demo.Film.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv",
                "title": "Demo Film",
                "torrentName": "Demo.Film.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "1080p",
                      "quality": "BluRay REMUX",
                      "codec": "avc",
                      "audio": ["TrueHD"],
                      "channels": ["7.1"],
                      "group": "FiBERHD",
                      "year": 2010,
                      "raw_title": "Demo.Film.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Demo.Film.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv",
                "videoSize": 24201782274
              }
            },
            {
              "name": "TB 1080p cached",
              "description": "Demo.Film.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:2525252525252525252525252525252525252525&dn=Demo.Film.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv",
                "infoHash": "2525252525252525252525252525252525252525",
                "sources": [],
                "fileIdx": 0,
                "filename": "Demo.Film.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv",
                "title": "Demo Film",
                "torrentName": "Demo.Film.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "1080p",
                      "quality": "BluRay REMUX",
                      "codec": "avc",
                      "audio": ["TrueHD"],
                      "channels": ["7.1"],
                      "group": "NOGRP",
                      "year": 2010,
                      "raw_title": "Demo.Film.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Demo.Film.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv",
                "videoSize": 22719933168
              }
            },
            {
              "name": "TB 1080p cached",
              "description": "Demo.Film.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-EXAMPLE.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:2626262626262626262626262626262626262626&dn=Demo.Film.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-EXAMPLE.mkv",
                "infoHash": "2626262626262626262626262626262626262626",
                "sources": [],
                "fileIdx": 0,
                "filename": "Demo.Film.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-EXAMPLE.mkv",
                "title": "Demo Film",
                "torrentName": "Demo.Film.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-EXAMPLE.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "1080p",
                      "quality": "WEB-DL",
                      "codec": "avc",
                      "audio": ["AAC"],
                      "channels": ["2.0"],
                      "group": "EXAMPLE",
                      "year": 2010,
                      "raw_title": "Demo.Film.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-EXAMPLE.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Demo.Film.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-EXAMPLE.mkv",
                "videoSize": 3079386380
              }
            },
            {
              "name": "TB 1080p cached",
              "description": "Demo Film (2010) 1080p 10bit Bluray x265 HEVC [Org DD 5.1 Hindi + DD 5.1 English] MSubs ~ TombDoc.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:2727272727272727272727272727272727272727&dn=Demo%20Film%20%282010%29%201080p%2010bit%20Bluray%20x265%20HEVC%20%5BOrg%20DD%205.1%20Hindi%20%2B%20DD%205.1%20English%5D%20MSubs%20~%20TombDoc.mkv",
                "infoHash": "2727272727272727272727272727272727272727",
                "sources": [],
                "fileIdx": 0,
                "filename": "Demo Film (2010) 1080p 10bit Bluray x265 HEVC [Org DD 5.1 Hindi + DD 5.1 English] MSubs ~ TombDoc.mkv",
                "title": "Demo Film",
                "torrentName": "Demo Film (2010) 1080p 10bit Bluray x265 HEVC [Org DD 5.1 Hindi + DD 5.1 English] MSubs ~ TombDoc.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "1080p",
                      "quality": "BluRay",
                      "codec": "hevc",
                      "audio": ["Dolby Digital"],
                      "channels": ["5.1"],
                      "languages": ["multi", "en", "hi"],
                      "year": 2010,
                      "raw_title": "Demo Film (2010) 1080p 10bit Bluray x265 HEVC [Org DD 5.1 Hindi + DD 5.1 English] MSubs ~ TombDoc.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Demo Film (2010) 1080p 10bit Bluray x265 HEVC [Org DD 5.1 Hindi + DD 5.1 English] MSubs ~ TombDoc.mkv",
                "videoSize": 3859136613
              }
            },
            {
              "name": "TB 1080p cached",
              "description": "Demo.Film.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:2828282828282828282828282828282828282828&dn=Demo.Film.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv",
                "infoHash": "2828282828282828282828282828282828282828",
                "sources": [],
                "fileIdx": 0,
                "filename": "Demo.Film.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv",
                "title": "Demo Film",
                "torrentName": "Demo.Film.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "1080p",
                      "quality": "BluRay",
                      "codec": "hevc",
                      "audio": ["Dolby Digital Plus"],
                      "channels": ["7.1"],
                      "group": "EDGE2020",
                      "year": 2010,
                      "raw_title": "Demo.Film.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Demo.Film.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv",
                "videoSize": 2946516232
              }
            },
            {
              "name": "TB 720p cached",
              "description": "Demo Film 2010 x264 720p Esub BluRay Dual Audio English Hindi GOPI SAHI.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:2929292929292929292929292929292929292929&dn=Demo%20Film%202010%20x264%20720p%20Esub%20BluRay%20Dual%20Audio%20English%20Hindi%20GOPI%20SAHI.mkv",
                "infoHash": "2929292929292929292929292929292929292929",
                "sources": [],
                "fileIdx": 0,
                "filename": "Demo Film 2010 x264 720p Esub BluRay Dual Audio English Hindi GOPI SAHI.mkv",
                "title": "Demo Film",
                "torrentName": "Demo Film 2010 x264 720p Esub BluRay Dual Audio English Hindi GOPI SAHI.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "720p",
                      "quality": "BluRay",
                      "codec": "avc",
                      "languages": ["multi", "en", "hi"],
                      "year": 2010,
                      "raw_title": "Demo Film 2010 x264 720p Esub BluRay Dual Audio English Hindi GOPI SAHI.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Demo Film 2010 x264 720p Esub BluRay Dual Audio English Hindi GOPI SAHI.mkv",
                "videoSize": 796639202
              }
            }
          ],
          "resolveMode": "client"
        }
    """.trimIndent()
}
