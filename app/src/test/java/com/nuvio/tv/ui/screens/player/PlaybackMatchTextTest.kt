package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.streams.StreamBadgeFilter
import com.nuvio.tv.core.streams.StreamBadgeImport
import com.nuvio.tv.core.streams.StreamBadgeMatcher
import com.nuvio.tv.core.streams.StreamBadgeRules
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackMatchTextTest {
    private val filters = StreamBadgeMatcher.compile(
        StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "https://example.com/badges.json",
                    filters = listOf(
                        filter("4K", "(?i)\\b(2160p|4k)\\b"),
                        filter("WebDL", "(?i)\\bweb[-. ]?dl\\b"),
                        filter("Vision", "(?i)\\b(dv|dovi|dolby[ .]?vision)\\b"),
                        filter("HDR", "(?i)\\bhdr(10)?\\b"),
                        filter("10bit", "(?i)\\b10[ -]?bit\\b"),
                        filter("HEVC", "(?i)\\b(hevc|[xh][ .]?265)\\b"),
                        filter("Atmos", "(?i)\\batmos\\b"),
                        filter("English", "(?i)\\b(eng|english)\\b")
                    )
                )
            )
        )
    )

    private fun filter(name: String, pattern: String) =
        StreamBadgeFilter(name = name, pattern = pattern, imageURL = "https://example.com/$name.png")

    private fun matchedNames(info: PlayerMediaInfo, languages: List<String> = emptyList()): List<String> =
        StreamBadgeMatcher.matchedBadges(listOf(playbackMatchText(info, languages)), filters).map { it.name }

    @Test
    fun `matches what is playing`() {
        val info = PlayerMediaInfo(
            videoCodec = "HEVC",
            videoWidth = 3840,
            videoHeight = 1600,
            audioCodec = "E-AC-3-JOC",
            audioChannels = 6,
            hdr = listOf("DV", "HDR10"),
            bitDepth = 10
        )
        assertEquals(
            listOf("4K", "Vision", "10bit", "HEVC", "Atmos", "English"),
            matchedNames(info, listOf("eng"))
        )
    }

    @Test
    fun `skips badges the playback does not have`() {
        val info = PlayerMediaInfo(videoCodec = "AVC", videoWidth = 1920, videoHeight = 1080, audioCodec = "AAC", audioChannels = 2)
        assertEquals(emptyList<String>(), matchedNames(info))
    }

    @Test
    fun `reads atmos from mpv audio profile`() {
        val info = PlayerMediaInfo(audioCodec = "TrueHD", audioProfiles = listOf("Dolby TrueHD + Dolby Atmos"))
        assertEquals(listOf("Atmos"), matchedNames(info))
    }

    @Test
    fun `uses the pack chosen for the player`() {
        val streamPack = StreamBadgeImport(
            sourceUrl = "https://example.com/stream.json",
            filters = listOf(filter("Stream4K", "\u2063\u200b\u2063"))
        )
        val playerPack = StreamBadgeImport(
            sourceUrl = "https://example.com/player.json",
            filters = listOf(filter("Player4K", "(?i)\\b2160p\\b"))
        )
        val rules = StreamBadgeRules(imports = listOf(streamPack, playerPack)).normalized()
        val text = playbackMatchText(PlayerMediaInfo(videoWidth = 3840, videoHeight = 2160), emptyList())
        fun namesFor(source: String) =
            StreamBadgeMatcher.matchedBadges(listOf(text), StreamBadgeMatcher.compile(rules.forPlayer(source))).map { it.name }

        assertEquals(emptyList<String>(), namesFor(""))
        assertEquals(listOf("Player4K"), namesFor("https://example.com/player.json"))
        assertEquals(emptyList<String>(), namesFor("https://example.com/removed.json"))
    }

    @Test
    fun `ExoPlayer takes DTS-HD MA and Atmos from the release name like the built-in pictures`() {
        val release = "Film.2023.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC.TrueHD.Atmos.7.1-GRP Film DTS-HD.MA.5.1"
        val truehd = playbackMatchText(PlayerMediaInfo(audioCodec = "TrueHD"), emptyList(), release)
        assertEquals(true, " Atmos" in " $truehd")
        val dts = playbackMatchText(PlayerMediaInfo(audioCodec = "DTS-HD"), emptyList(), release)
        assertEquals(true, "DTS-HD MA" in dts)
        val aac = playbackMatchText(PlayerMediaInfo(audioCodec = "AAC"), emptyList(), release)
        assertEquals(false, "Atmos" in aac || "DTS-HD MA" in aac)
        val dtsCore = playbackMatchText(PlayerMediaInfo(audioCodec = "DTS"), emptyList(), release)
        assertEquals(false, "DTS-HD MA" in dtsCore)
    }

    private val filenamePack = StreamBadgeMatcher.compile(
        StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "https://example.com/filenames.json",
                    filters = listOf(
                        filter("DDP Atmos", "(?i)DDP[ .]?5\\.1[ .]?Atmos"),
                        filter("TrueHD Atmos", "(?i)TrueHD[ .]?(7\\.1[ .]?)?Atmos"),
                        filter("DV HDR", "(?i)\\b(DV|DoVi)[ .]HDR"),
                        filter("DV Only", "DV Only"),
                        filter("HDR Only", "HDR Only"),
                        filter("SDR", "\\bSDR\\b"),
                        filter("DTS-HD MA", "(?i)DTS[-. ]?HD[-. ]?MA"),
                        filter("DTS-X", "(?i)DTS[-:. ]?X\\b"),
                        filter("AC3", "(?i)\\b(DD|AC3)\\b(?!\\+)"),
                        filter("Stereo", "(?i)\\bstereo\\b"),
                        filter("French", "\\b(FRE|FRA|French)\\b")
                    )
                )
            )
        )
    )

    private fun filenameNames(info: PlayerMediaInfo, language: String? = null, release: String = ""): List<String> =
        StreamBadgeMatcher.matchedBadges(listOf(playbackMatchText(info, playbackLanguageTokens(language), release)), filenamePack)
            .map { it.name }

    @Test
    fun `matches packs written for release names`() {
        val info = PlayerMediaInfo(
            videoCodec = "HEVC", videoWidth = 3840, videoHeight = 2160,
            audioCodec = "E-AC-3-JOC", audioChannels = 6, hdr = listOf("DV", "HDR10")
        )
        assertEquals(listOf("DDP Atmos"), filenameNames(info))
    }

    @Test
    fun `a dolby vision file shown as hdr10 reads no dolby vision`() {
        val info = PlayerMediaInfo(videoCodec = "HEVC", videoWidth = 3840, videoHeight = 2160, audioCodec = "TrueHD", audioChannels = 8, hdr = listOf("HDR10"), hdrKnown = true)
        assertEquals(
            listOf("TrueHD Atmos"),
            filenameNames(info, release = "Film.2160p.REMUX.DV.HDR.TrueHD.7.1.Atmos-GRP")
        )
        assertEquals(listOf("DV Only"), filenameNames(PlayerMediaInfo(hdr = listOf("DV"), hdrKnown = true)))
    }

    @Test
    fun `sdr stereo and the track language`() {
        val info = PlayerMediaInfo(videoCodec = "AVC", videoWidth = 1920, videoHeight = 800, audioCodec = "AAC", audioChannels = 2, hdrKnown = true)
        assertEquals(listOf("SDR", "Stereo", "French"), filenameNames(info, "fr"))
    }

    @Test
    fun `dts-hd takes ma and x from the release name on exoplayer and from the profile on mpv`() {
        val exo = PlayerMediaInfo(audioCodec = "DTS-HD", audioChannels = 8)
        assertEquals(listOf("DTS-HD MA", "DTS-X"), filenameNames(exo, release = "Film.BluRay.DTS-HD.MA.7.1.DTS-X-GRP"))
        assertEquals(emptyList<String>(), filenameNames(exo))
        val mpv = PlayerMediaInfo(audioCodec = "DTS", audioProfiles = listOf("DTS-HD MA + DTS:X"), audioChannels = 8)
        assertEquals(listOf("DTS-HD MA", "DTS-X"), filenameNames(mpv))
    }

    @Test
    fun `dolby digital plus does not read as dolby digital`() {
        assertEquals(emptyList<String>(), filenameNames(PlayerMediaInfo(audioCodec = "E-AC-3", audioChannels = 6)))
        assertEquals(listOf("AC3"), filenameNames(PlayerMediaInfo(audioCodec = "AC-3", audioChannels = 6)))
    }

    @Test
    fun `release-name spellings`() {
        val text = playbackMatchText(
            PlayerMediaInfo(videoCodec = "HEVC", audioCodec = "E-AC-3-JOC", audioChannels = 6), emptyList()
        ).split(' ')
        listOf("DDP", "DDP5.1", "DDP.5.1", "DDP5.1.Atmos", "DDPA5.1", "H.265", "x265").forEach {
            assertEquals(it, true, it in text)
        }
        assertEquals(false, text.any { "AC3" in it.uppercase() || "AC-3" in it.uppercase() || "DD+" in it })
    }

    @Test
    fun `language tokens carry both iso 639-2 codes`() {
        assertEquals(listOf("de", "DE", "deu", "ger", "German"), playbackLanguageTokens("de").take(5))
        assertEquals("🇩🇪", playbackLanguageTokens("de").last())
        assertEquals(true, "🇬🇧" in playbackLanguageTokens("eng"))
        assertEquals(true, playbackLanguageTokens("pt-BR").containsAll(listOf("PT", "PT-BR", "🇧🇷")))
        assertEquals(true, "🇵🇹" in playbackLanguageTokens("pt"))
        assertEquals(emptyList<String>(), playbackLanguageTokens("und"))
        assertEquals(emptyList<String>(), playbackLanguageTokens(null))
    }

    private val main = TrackInfo(index = 0, name = "English", language = "en", trackId = "2", codec = "E-AC-3-JOC", channelCount = 6)
    private val commentary = TrackInfo(index = 1, name = "Commentary", language = "en", trackId = "3", codec = "E-AC-3", channelCount = 2)
    private val french = TrackInfo(index = 2, name = "French", language = "fr", trackId = "4", codec = "AC-3", channelCount = 6)
    private val described = TrackInfo(index = 3, name = "AD", language = "en", trackId = "5", codec = "AC-3", role = TrackRole.AUDIO_DESCRIPTION)
    private val tracks = listOf(main, commentary, french, described)

    @Test
    fun `the release name speaks only for the first track of its codec family`() {
        assertEquals(true, releaseDescribesTrack(tracks, main))
        assertEquals(false, releaseDescribesTrack(tracks, commentary))
        assertEquals(true, releaseDescribesTrack(tracks, french))
        assertEquals(false, releaseDescribesTrack(tracks, described))
        assertEquals(true, releaseDescribesTrack(emptyList(), null))
    }

    @Test
    fun `a second e-ac-3 track does not take atmos from the release name`() {
        val release = "Film.2160p.WEB-DL.DDP5.1.Atmos.H.265-GRP"
        val info = PlayerMediaInfo(audioCodec = "E-AC-3", audioChannels = 2, audioTrackId = "3")
        val playing = playingAudioTrack(tracks, info)
        assertEquals(commentary, playing)
        val text = playbackMatchText(info, emptyList(), release.takeIf { releaseDescribesTrack(tracks, playing) }.orEmpty())
        assertEquals(false, "Atmos" in text)
    }

    @Test
    fun `badges follow the playing track while the selection runs ahead`() {
        val switching = tracks.map { it.copy(isSelected = it == french) }
        assertEquals(main, playingAudioTrack(switching, PlayerMediaInfo(audioTrackId = "2")))
        assertEquals(french.copy(isSelected = true), playingAudioTrack(switching, PlayerMediaInfo()))
        assertEquals(french.copy(isSelected = true), playingAudioTrack(switching, PlayerMediaInfo(audioTrackId = "99")))
    }

    @Test
    fun `audio badges hold through the gap in a switch`() {
        val before = PlayerMediaInfo(videoWidth = 1920, audioCodec = "E-AC-3", audioChannels = 6, audioTrackId = "2", audioLanguage = "en")
        val gap = PlayerMediaInfo(videoWidth = 1920)
        assertEquals(before, gap.keepingAudioOf(before, audioSelected = true))
        assertEquals(gap, gap.keepingAudioOf(before, audioSelected = false))
        val reopening = before.copy(audioChannels = null)
        assertEquals(before, reopening.keepingAudioOf(before, audioSelected = true))
        val after = PlayerMediaInfo(videoWidth = 1920, audioCodec = "AC-3", audioTrackId = "4", audioLanguage = "fr")
        assertEquals(after, after.keepingAudioOf(before, audioSelected = true))
    }

    @Test
    fun `nothing says sdr or dv only before the transfer is known`() {
        // mpv before its first frame: size and DV from the file, no transfer yet.
        val early = PlayerMediaInfo(videoCodec = "HEVC", videoWidth = 3840, videoHeight = 2160, hdr = listOf("DV"))
        assertEquals(emptyList<String>(), filenameNames(early))
        assertEquals(emptyList<String>(), filenameNames(early.copy(hdr = emptyList())))
        assertEquals(emptyList<String>(), filenameNames(early.copy(hdr = listOf("DV", "HDR10"), hdrKnown = true)))
    }

    @Test
    fun `complete once picture size and audio with channels are in`() {
        assertEquals(false, PlayerMediaInfo(videoWidth = 1920, audioCodec = "AAC").isComplete)
        assertEquals(true, PlayerMediaInfo(videoWidth = 1920, audioCodec = "AAC", audioChannels = 2).isComplete)
    }

    @Test
    fun `shows nothing before playback info loads`() {
        assertEquals(emptyList<String>(), matchedNames(PlayerMediaInfo()))
    }

    @Test
    fun `DTS-HD MA not triggered by German label`() {
        // Real ExoPlayer input: DTS-HD HRA track with German label.
        // Before fix: "German" contains "ma", producing a false DTS-HD MA profile.
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
            .setLabel("German DTS-HD HRA 7.1")
            .build()
        val profiles = exoAudioProfiles(format)
        assertFalse("DTS-HD MA should not appear for German DTS-HD HRA", profiles.contains("DTS-HD MA"))
        assertTrue("DTS-HD should appear", profiles.contains("DTS-HD"))
    }

    @Test
    fun `DTS-HD Master Audio label produces MA profile`() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
            .setLabel("English DTS-HD Master Audio 7.1")
            .build()
        val profiles = exoAudioProfiles(format)
        assertTrue("DTS-HD MA should appear for Master Audio label", profiles.contains("DTS-HD MA"))
    }

    @Test
    fun `DTS-HD MA and DTS-X both appear`() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
            .setLabel("DTS-HD MA DTS:X 7.1")
            .build()
        val profiles = exoAudioProfiles(format)
        assertTrue("DTS-HD MA should appear", profiles.contains("DTS-HD MA"))
        assertTrue("DTS:X should appear", profiles.contains("DTS:X"))
    }

    @Test
    fun `pcm_s24le maps to PCM via mpv`() {
        val info = mpvMediaInfo { name ->
            when (name) {
                "current-tracks/audio/codec" -> "pcm_s24le"
                else -> null
            }
        }
        assertEquals("PCM", info.audioCodec)
    }

    @Test
    fun `group exclusion skips filename language when decoder matches`() {
        val langGroup = "Language"
        val sourceGroup = "Source"
        val playerFilters = StreamBadgeMatcher.compile(
            StreamBadgeRules(
                imports = listOf(
                    StreamBadgeImport(
                        sourceUrl = "https://example.com/badges.json",
                        filters = listOf(
                            StreamBadgeFilter(name = "English", pattern = "(?i)\\benglish\\b", imageURL = "eng.png", groupId = langGroup),
                            StreamBadgeFilter(name = "Polish", pattern = "(?i)\\bpol\\b", imageURL = "pol.png", groupId = langGroup),
                            StreamBadgeFilter(name = "WebDL", pattern = "(?i)\\bweb[-.]?dl\\b", imageURL = "webdl.png", groupId = sourceGroup)
                        )
                    )
                )
            )
        )
        val playback = "DD+ 5.1 English"
        val filename = listOf("Movie.POL.ENG.WebDL.mkv")
        val badges = StreamBadgeMatcher.matchedPlayerBadges(playback, filename, playerFilters).map { it.name }
        assertTrue("English should appear (decoder match)", "English" in badges)
        assertFalse("Polish should NOT appear (group covered by decoder)", "Polish" in badges)
        assertTrue("WebDL should appear (different group)", "WebDL" in badges)
    }

    @Test
    fun `hdr10+ comes from the file name only while hdr10 plays`() {
        val hdr10 = PlayerMediaInfo(videoWidth = 3840, videoHeight = 2160, hdr = listOf("HDR10"), hdrKnown = true)
        val file = "Movie.2160p.HDR10+.HEVC.mkv"
        assertTrue("HDR10+" in playerBadgePlayingText(hdr10, emptyList(), file, file, audioFromRelease = true).split(" "))
        val sdr = hdr10.copy(hdr = emptyList())
        assertFalse("HDR10+" in playerBadgePlayingText(sdr, emptyList(), file, file, audioFromRelease = true).split(" "))
    }

    @Test
    fun `a multi-language file name shows only the playing language`() {
        val filters = StreamBadgeMatcher.compile(
            StreamBadgeRules(
                imports = listOf(
                    StreamBadgeImport(
                        sourceUrl = "https://example.com/badges.json",
                        filters = listOf(
                            StreamBadgeFilter(name = "English", pattern = "(?i)\\benglish\\b", imageURL = "eng.png", groupId = "Language"),
                            StreamBadgeFilter(name = "German", pattern = "(?i)\\bgerman\\b", imageURL = "ger.png", groupId = "Language"),
                            StreamBadgeFilter(name = "Remux", pattern = "(?i)\\bremux\\b", imageURL = "remux.png", groupId = "Source")
                        )
                    )
                )
            )
        )
        val info = PlayerMediaInfo(audioCodec = "E-AC-3", audioChannels = 6)
        val file = "Movie.2160p.German.English.Remux.mkv"
        val languages = playbackLanguageTokens("en")
        val playing = playerBadgePlayingText(info, languages, file, file, audioFromRelease = true)
        val text = playerBadgeMatchText(info, languages, file, file, audioFromRelease = true)
        val badges = StreamBadgeMatcher.matchedPlayerBadges(playing, listOf(text), filters).map { it.name }
        assertEquals(listOf("English", "Remux"), badges)
    }

    @Test
    fun `order restored to pack filter order with shared imageURL`() {
        val filters = StreamBadgeMatcher.compile(
            StreamBadgeRules(
                imports = listOf(
                    StreamBadgeImport(
                        sourceUrl = "https://example.com/badges.json",
                        filters = listOf(
                            StreamBadgeFilter(name = "Remux", pattern = "(?i)\\bremux\\b", imageURL = "remux.png"),
                            StreamBadgeFilter(name = "HEVC", pattern = "(?i)\\bhevc\\b", imageURL = "hevc.png"),
                            StreamBadgeFilter(name = "HEVC-alt", pattern = "(?i)\\bh\\.?265\\b", imageURL = "hevc.png")
                        )
                    )
                )
            )
        )
        // HEVC matches decoder (phase 1), Remux matches filename (phase 2).
        // Pack order is Remux first, so result should keep Remux before HEVC.
        val badges = StreamBadgeMatcher.matchedPlayerBadges("HEVC 2160p", listOf("Movie.Remux.mkv"), filters)
        assertEquals("Remux", badges[0].name)
        assertEquals("HEVC", badges[1].name)
    }
}
