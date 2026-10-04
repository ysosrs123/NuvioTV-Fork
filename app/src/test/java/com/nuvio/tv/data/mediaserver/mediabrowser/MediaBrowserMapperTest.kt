package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.domain.model.ContentType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaBrowserMapperTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val mapper = MediaBrowserMapper("https://media.example.com/jellyfin", "cabc")

    private val movie = json.decodeFromString(
        BaseItem.serializer(),
        """
        {
          "Id": "f1c9a0",
          "Name": "Director's Cut",
          "Type": "Movie",
          "ProductionYear": 2007,
          "CommunityRating": 7.84,
          "RunTimeTicks": 72000000000,
          "ProviderIds": {"Imdb": "tt0111161", "Tmdb": "278", "Tvdb": null},
          "ImageTags": {"Primary": "p1"},
          "BackdropImageTags": ["b1"],
          "MediaSources": [
            {"Id": "ms1", "Name": "4K Remux", "Path": "/media/movie.mkv", "Container": "mkv", "Size": 50000000000,
             "MediaStreams": [{"Type": "Video", "Width": 3840, "Height": 1600, "DisplayTitle": "4K HEVC HDR"}]},
            {"Id": "ms2", "Name": "1080p", "Path": "/media/movie-1080p.mp4", "Container": "mp4"}
          ]
        }
        """
    )

    @Test
    fun mapsPreviewWithNativeIdentityAndUnauthenticatedArtwork() {
        val preview = mapper.preview(movie)!!
        assertEquals(ServerItemRef("cabc", "f1c9a0"), ServerItemRef.parse(preview.id))
        assertEquals(ContentType.MOVIE, preview.type)
        assertEquals("Director's Cut", preview.name)
        assertEquals("2007", preview.releaseInfo)
        assertEquals(7.8f, preview.imdbRating)
        assertTrue(preview.poster!!.startsWith("https://media.example.com/jellyfin/Items/f1c9a0/Images/Primary?tag=p1"))
        assertFalse(preview.poster!!.contains("api_key"))
    }

    @Test
    fun keepsExternalIdsSeparateFromNativeId() {
        val ids = movie.externalIds()
        assertEquals("tt0111161", ids.imdb)
        assertEquals(278L, ids.tmdb)
        assertNull(ids.tvdb)
        assertEquals("tt0111161", mapper.details(movie, emptyList()).imdbId)
    }

    @Test
    fun listsEveryVersionAsCandidate() {
        val candidates = mapper.candidates(movie)
        assertEquals(listOf("ms1", "ms2"), candidates.map { it.target.mediaSourceId })
        assertEquals(listOf("2160p", "1080p"), candidates.map { it.title })
        assertEquals(listOf("4K Remux", "1080p"), candidates.map { it.versionName })
        assertEquals("movie.mkv", candidates.first().filename)
        assertEquals(listOf("MKV", "MP4"), candidates.map { it.container })
    }

    private fun source(body: String) = json.decodeFromString(MediaSource.serializer(), body)

    private fun stream(body: String) = json.decodeFromString(MediaStream.serializer(), body)

    @Test
    fun describesTheVideoAndTheDefaultAudioTrack() {
        val item = json.decodeFromString(
            BaseItem.serializer(),
            """{"Id": "m1", "Type": "Movie", "RunTimeTicks": 88353570000, "MediaSources": [
                 {"Id": "ms1", "Name": "Apocalypse Now", "Container": "mkv", "Size": 57508742418, "Bitrate": 52071460,
                  "Path": "/mnt/movies/Apocalypse Now 1979 Theatrical Cut UHD BluRay 2160p TrueHD Atmos 7 1 DV HEVC REMUX-FraMeSToR.mkv",
                  "DefaultAudioStreamIndex": 2, "MediaStreams": [
                   {"Type": "Video", "Index": 0, "Codec": "hevc", "Width": 3840, "Height": 2160, "BitRate": 45831460,
                    "VideoRange": "HDR", "VideoRangeType": "DOVIWithHDR10", "DvProfile": 7, "DvBlSignalCompatibilityId": 6,
                    "DisplayTitle": "4K HEVC Dolby Vision Profile 7.6 (HDR10)"},
                   {"Type": "Audio", "Index": 1, "Codec": "ac3", "ChannelLayout": "stereo", "Channels": 2, "IsDefault": true},
                   {"Type": "Audio", "Index": 2, "Codec": "truehd", "Profile": "Dolby TrueHD + Dolby Atmos",
                    "ChannelLayout": "7.1", "Channels": 8, "BitRate": 5600000}]}]}"""
        )
        val candidate = mapper.candidates(item).single()
        assertEquals("2160p", candidate.title)
        assertEquals("HEVC • DV P7.6 (HDR10)", candidate.video)
        assertEquals("TrueHD Atmos • 7.1", candidate.audio)
        assertEquals("MKV", candidate.container)
        assertEquals(57_508_742_418L, candidate.sizeBytes)
        assertEquals(52_071_460L, candidate.bitrateBps)
        assertEquals(
            "Apocalypse Now 1979 Theatrical Cut UHD BluRay 2160p TrueHD Atmos 7 1 DV HEVC REMUX-FraMeSToR.mkv",
            candidate.filename
        )
        val withoutIndex = item.copy(mediaSources = listOf(item.mediaSources.single().copy(defaultAudioStreamIndex = null)))
        assertEquals("AC3 • 2.0", mapper.candidates(withoutIndex).single().audio)
    }

    @Test
    fun labelsResolutionsFromTheVideoSize() {
        fun label(width: Int, height: Int) = resolutionLabel(stream("""{"Type": "Video", "Width": $width, "Height": $height}"""))
        assertEquals("2160p", label(3840, 1600))
        assertEquals("1080p", label(1920, 800))
        assertEquals("720p", label(1280, 720))
        assertEquals("576p", label(720, 576))
        assertEquals("480p", label(720, 480))
        assertEquals("360p", label(640, 360))
        assertNull(resolutionLabel(stream("""{"Type": "Video"}""")))
    }

    @Test
    fun namesTheDynamicRange() {
        fun video(fields: String) = videoLabel(stream("""{"Type": "Video", "Codec": "hevc"$fields}"""))
        assertEquals("HEVC • HDR10", video(""", "VideoRange": "HDR", "VideoRangeType": "HDR10""""))
        assertEquals("HEVC • HDR10+", video(""", "VideoRange": "HDR", "VideoRangeType": "HDR10Plus""""))
        assertEquals("HEVC • HLG", video(""", "VideoRangeType": "HLG""""))
        assertEquals("HEVC • SDR", video(""", "VideoRange": "SDR", "VideoRangeType": "SDR""""))
        assertEquals("HEVC • HDR", video(""", "VideoRange": "HDR""""))
        assertEquals("HEVC • DV P5", video(""", "VideoRangeType": "DOVI", "DvProfile": 5, "DvBlSignalCompatibilityId": 0"""))
        assertEquals("HEVC • DV P8.1 (HDR10)", video(""", "VideoRangeType": "DOVIWithHDR10", "DvProfile": 8, "DvBlSignalCompatibilityId": 1"""))
        assertEquals("HEVC • DV P8.4 (HLG)", video(""", "VideoRangeType": "DOVIWithHLG", "DvProfile": 8, "DvBlSignalCompatibilityId": 4"""))
        assertEquals("HEVC • DV P7.6 (HDR10)", video(""", "VideoRangeType": "DOVIWithEL", "DvProfile": 7, "DvBlSignalCompatibilityId": 6"""))
        assertEquals("HEVC • DV P8.1 (HDR10)", video(""", "ExtendedVideoType": "DolbyVision", "ExtendedVideoSubType": "DoviProfile81""""))
        assertEquals("HEVC • HDR10+", video(""", "ExtendedVideoType": "Hdr10Plus""""))
        assertEquals("HEVC", video(""))
        assertEquals("AVC • SDR", videoLabel(stream("""{"Type": "Video", "Codec": "h264", "VideoRange": "SDR"}""")))
        assertNull(videoLabel(stream("""{"Type": "Video"}""")))
    }

    @Test
    fun namesTheAudioCodecAtmosAndChannels() {
        fun audio(fields: String) = audioLabel(stream("""{"Type": "Audio"$fields}"""))
        assertEquals("DTS-HD MA • 5.1", audio(""", "Codec": "dts", "Profile": "DTS-HD MA", "ChannelLayout": "5.1(side)""""))
        assertEquals("DTS:X • 7.1", audio(""", "Codec": "dts", "Profile": "DTS-HD MA + DTS:X", "Channels": 8"""))
        assertEquals("DTS • 5.1", audio(""", "Codec": "dts", "Channels": 6"""))
        assertEquals("EAC3 Atmos • 5.1", audio(""", "Codec": "eac3", "Profile": "Dolby Digital Plus + Dolby Atmos", "ChannelLayout": "5.1""""))
        assertEquals("TrueHD Atmos • 7.1", audio(""", "Codec": "truehd", "DisplayTitle": "English - Dolby TrueHD + Dolby Atmos - 7.1", "Channels": 8"""))
        assertEquals("AAC • 2.0", audio(""", "Codec": "aac", "ChannelLayout": "stereo""""))
        assertEquals("FLAC • 2.0", audio(""", "Codec": "flac", "Channels": 2"""))
        assertEquals("AC3", audio(""", "Codec": "ac3""""))
        assertEquals("5.1", audio(""", "Channels": 6"""))
        assertNull(audio(""))
    }

    @Test
    fun readsTheContainerOfTheFile() {
        assertEquals("MKV", containerLabel("mkv", "a.mkv"))
        assertEquals("MP4", containerLabel("mov,mp4,m4a,3gp,3g2,mj2", "a.mp4"))
        assertEquals("MOV", containerLabel("mov,mp4,m4a", null))
        assertNull(containerLabel(null, "a.mkv"))
        assertNull(containerLabel(" ", "a.mkv"))
    }

    @Test
    fun worksOutTheBitrateWhenTheServerDoesNotSendIt() {
        assertEquals(
            52_071_460L,
            sourceBitrate(source("""{"Id": "a", "Size": 57508742418, "RunTimeTicks": 88353570000}"""))
        )
        assertEquals(
            52_071_460L,
            sourceBitrate(source("""{"Id": "a", "Size": 57508742418}"""), itemRunTimeTicks = 88353570000)
        )
        assertEquals(
            14_799_399L,
            sourceBitrate(source("""{"Id": "a", "MediaStreams": [{"Type": "Video", "BitRate": 14351399},
                {"Type": "Audio", "BitRate": 448000}, {"Type": "Subtitle"}]}"""))
        )
        assertNull(sourceBitrate(source("""{"Id": "a", "MediaStreams": [{"Type": "Audio", "BitRate": 448000}]}""")))
    }

    @Test
    fun leavesTheBitrateOutWhenNothingSensibleIsKnown() {
        listOf(0L, 10L, 2_000_000_000L).forEach { value ->
            assertNull(sourceBitrate(source("""{"Id": "a", "Bitrate": $value}""")))
        }
        val item = movie.copy(mediaSources = listOf(source("""{"Id": "ms1", "Container": "mkv"}""")))
        assertEquals("MKV", mapper.candidates(item).single().container)
        assertNull(mapper.candidates(item).single().bitrateBps)
        assertEquals(
            8_000_000L,
            sourceBitrate(source("""{"Id": "a", "Bitrate": 2000000000, "Size": 1000000000, "RunTimeTicks": 10000000000}"""))
        )
    }

    @Test
    fun formatsBitrates() {
        assertEquals("850 kbps", formatBitrate(850_000L))
        assertEquals("8.4 Mbps", formatBitrate(8_400_000L))
        assertEquals("52 Mbps", formatBitrate(52_071_460L))
        assertEquals("120 Mbps", formatBitrate(120_000_000L))
    }

    @Test
    fun decodesASourceWithoutBitrate() {
        val silo = source("""{"Id": "v1", "Container": "mkv", "Size": 1000}""")
        assertNull(silo.bitrate)
        assertNull(silo.runTimeTicks)
    }

    @Test
    fun missingItemsHaveNoCandidates() {
        assertTrue(mapper.candidates(movie.copy(locationType = "Virtual")).isEmpty())
    }

    @Test
    fun mapsEpisodesWithNativeIdsAndAvailability() {
        val episode = json.decodeFromString(
            BaseItem.serializer(),
            """{"Id": "e42", "Name": "Pilot", "Type": "Episode", "ParentIndexNumber": 0, "IndexNumber": 1, "LocationType": "Virtual"}"""
        )
        val video = mapper.video(episode)
        assertEquals(ServerItemRef("cabc", "e42").encode(), video.id)
        assertEquals(0, video.season)
        assertEquals(1, video.episode)
        assertEquals(false, video.available)
    }

    @Test
    fun mapsCollectionsAndCollectionLibraries() {
        assertEquals("collection", mapper.preview(movie.copy(type = "BoxSet"))!!.apiType)
        assertEquals("collection", mapper.preview(movie.copy(type = "Folder"))!!.apiType)
        assertEquals(ServerMediaKind.COLLECTION, libraryKind("boxsets"))
        assertNull(libraryKind("music"))
    }

    @Test
    fun readsEveryKnownProviderId() {
        val ids = movie.copy(
            providerIds = mapOf("Imdb" to "tt1", "AniList" to "21", "Kitsu" to "7442", "MyAnimeList" to "5114", "Zap2It" to "EP1")
        ).externalIds()
        assertEquals("tt1", ids.imdb)
        assertEquals(21L, ids.anilist)
        assertEquals(7442L, ids.kitsu)
        assertEquals(5114L, ids.mal)
        assertNull(ids.trakt)
    }

    @Test
    fun readsADateThatEndsInZ() {
        assertEquals(1791101553000L, serverDateEpochMs("2026-10-04T08:12:33.0000000Z"))
        assertEquals(1791101553000L, serverDateEpochMs("2026-10-04T08:12:33Z"))
    }

    @Test
    fun readsADateWithAnOffset() {
        assertEquals(1791101553000L, serverDateEpochMs("2026-10-04T08:12:33.0000000+00:00"))
        assertEquals(1791101553000L, serverDateEpochMs("2026-10-04T10:12:33+02:00"))
    }

    @Test
    fun readsADateWithoutAZoneAsUtc() {
        assertEquals(1791101553000L, serverDateEpochMs("2026-10-04T08:12:33.0000000"))
        assertNull(serverDateEpochMs("yesterday"))
    }

    @Test
    fun aLastPlayedDateWithAnOffsetReachesTheUserState() {
        val played = json.decodeFromString(
            BaseItem.serializer(),
            """{"Id": "m1", "Type": "Movie", "RunTimeTicks": 72000000000,
                "UserData": {"PlaybackPositionTicks": 12000000000, "Played": false, "LastPlayedDate": "2026-10-04T08:12:33.0000000+00:00"}}"""
        )
        assertEquals(1791101553000L, mapper.userState(played)!!.lastPlayedEpochMs)
    }

    @Test
    fun writesTheYearsOfASeriesWithAPlainHyphen() {
        fun years(fields: String) = mapper.details(
            json.decodeFromString(
                BaseItem.serializer(),
                """{"Id": "s1", "Name": "Show", "Type": "Series", "ProductionYear": 2011$fields}"""
            ),
            emptyList()
        ).releaseInfo

        assertEquals("2011-2019", years(", \"EndDate\": \"2019-05-19T00:00:00.0000000Z\", \"Status\": \"Ended\""))
        assertEquals("2011-", years(", \"Status\": \"Continuing\""))
        assertEquals("2011", years(""))
    }

    @Test
    fun ignoresUnsupportedItemTypes() {
        assertNull(mapper.preview(movie.copy(type = "MusicAlbum")))
    }
}
