package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 3.9. Pure-JVM tests of the lossless tier classifier and the default picker.
 * Language matching in these tests is exact lowercase equality; the production path
 * uses PlayerSubtitleUtils.matchesLanguageCode, which is tolerant of 639-1/639-2.
 */
class LosslessAudioTrackDefaultTest {

    private fun track(
        index: Int,
        codec: String?,
        name: String,
        language: String? = "en",
        channels: Int? = 6,
        selected: Boolean = false
    ) = TrackInfo(
        index = index,
        name = name,
        language = language,
        trackId = index.toString(),
        codec = codec,
        channelCount = channels,
        isSelected = selected
    )

    private val exactMatch: (String?, String) -> Boolean = { lang, target ->
        lang != null && lang.lowercase() == target.lowercase()
    }

    // --- classifier ---

    @Test
    fun `exo vocabulary ranks all four tiers`() {
        assertEquals(LosslessAudioTrackDefault.TIER_TRUEHD, LosslessAudioTrackDefault.losslessTier("TrueHD", "English (TrueHD 7.1)"))
        assertEquals(LosslessAudioTrackDefault.TIER_DTS_HD_MA, LosslessAudioTrackDefault.losslessTier("DTS-HD", "English (DTS-HD 5.1)"))
        assertEquals(LosslessAudioTrackDefault.TIER_FLAC, LosslessAudioTrackDefault.losslessTier("FLAC", "English (FLAC Stereo)"))
        assertEquals(LosslessAudioTrackDefault.TIER_PCM, LosslessAudioTrackDefault.losslessTier("PCM", "English (PCM 5.1)"))
    }

    @Test
    fun `mpv vocabulary ranks truehd mlp flac and pcm variants`() {
        assertEquals(LosslessAudioTrackDefault.TIER_TRUEHD, LosslessAudioTrackDefault.losslessTier("truehd", "English"))
        assertEquals(LosslessAudioTrackDefault.TIER_TRUEHD, LosslessAudioTrackDefault.losslessTier("mlp", "English"))
        assertEquals(LosslessAudioTrackDefault.TIER_FLAC, LosslessAudioTrackDefault.losslessTier("flac", "English"))
        assertEquals(LosslessAudioTrackDefault.TIER_PCM, LosslessAudioTrackDefault.losslessTier("pcm_s24le", "English"))
    }

    @Test
    fun `mpv plain dts is unranked without an MA hint`() {
        assertNull(LosslessAudioTrackDefault.losslessTier("dts", "English (DTS 5.1)"))
        assertEquals(
            LosslessAudioTrackDefault.TIER_DTS_HD_MA,
            LosslessAudioTrackDefault.losslessTier("dts", "English DTS-HD MA 7.1")
        )
        assertEquals(
            LosslessAudioTrackDefault.TIER_DTS_HD_MA,
            LosslessAudioTrackDefault.losslessTier("dts", "Master Audio 5.1")
        )
    }

    @Test
    fun `lossy codecs are unranked`() {
        assertNull(LosslessAudioTrackDefault.losslessTier("AC-3", "English (AC-3 5.1)"))
        assertNull(LosslessAudioTrackDefault.losslessTier("E-AC-3", "English"))
        assertNull(LosslessAudioTrackDefault.losslessTier("AAC", "English"))
        assertNull(LosslessAudioTrackDefault.losslessTier("eac3", "English"))
        assertNull(LosslessAudioTrackDefault.losslessTier(null, "English"))
    }

    @Test
    fun `commentary detection`() {
        assertTrue(LosslessAudioTrackDefault.isCommentaryLike("Director's Commentary (TrueHD)"))
        assertTrue(LosslessAudioTrackDefault.isCommentaryLike("English - Descriptive Audio"))
        assertFalse(LosslessAudioTrackDefault.isCommentaryLike("English (TrueHD 7.1)"))
        assertFalse(LosslessAudioTrackDefault.isCommentaryLike(null))
    }

    // --- picker ---

    @Test
    fun `truehd beats dts-hd ma and lossy default`() {
        val tracks = listOf(
            track(0, "AC-3", "English (AC-3 5.1)", selected = true),
            track(1, "DTS-HD", "English (DTS-HD 7.1)", channels = 8),
            track(2, "TrueHD", "English (TrueHD 5.1)", channels = 6)
        )
        assertEquals(2, LosslessAudioTrackDefault.pickDefaultIndex(tracks, listOf("en"), exactMatch))
    }

    @Test
    fun `channel count breaks ties within a tier, then index`() {
        val tracks = listOf(
            track(0, "TrueHD", "English (TrueHD 5.1)", channels = 6),
            track(1, "TrueHD", "English (TrueHD 7.1)", channels = 8),
            track(2, "TrueHD", "English (TrueHD 7.1) alt", channels = 8)
        )
        assertEquals(1, LosslessAudioTrackDefault.pickDefaultIndex(tracks, listOf("en"), exactMatch))
    }

    @Test
    fun `commentary lossless never becomes the default`() {
        val onlyCommentaryLossless = listOf(
            track(0, "AC-3", "English (AC-3 5.1)", selected = true),
            track(1, "TrueHD", "Commentary (TrueHD 2.0)", channels = 2)
        )
        assertNull(LosslessAudioTrackDefault.pickDefaultIndex(onlyCommentaryLossless, listOf("en"), exactMatch))

        val cleanLowerTierWins = listOf(
            track(0, "TrueHD", "Director's Commentary (TrueHD)"),
            track(1, "FLAC", "English (FLAC 5.1)")
        )
        assertEquals(1, LosslessAudioTrackDefault.pickDefaultIndex(cleanLowerTierWins, listOf("en"), exactMatch))
    }

    @Test
    fun `language pool is never overridden by foreign lossless`() {
        val tracks = listOf(
            track(0, "AC-3", "English (AC-3 5.1)", language = "en", selected = true),
            track(1, "TrueHD", "Deutsch (TrueHD 7.1)", language = "de", channels = 8)
        )
        // English pool exists and has no lossless member -> engine default stands.
        assertNull(LosslessAudioTrackDefault.pickDefaultIndex(tracks, listOf("en"), exactMatch))
    }

    @Test
    fun `whole set is the pool only when no preferred language matches`() {
        val tracks = listOf(
            track(0, "AC-3", "Italiano (AC-3 5.1)", language = "it", selected = true),
            track(1, "TrueHD", "Deutsch (TrueHD 7.1)", language = "de", channels = 8)
        )
        assertEquals(1, LosslessAudioTrackDefault.pickDefaultIndex(tracks, listOf("en"), exactMatch))
    }

    @Test
    fun `preferred language order is respected`() {
        val tracks = listOf(
            track(0, "TrueHD", "Deutsch (TrueHD 7.1)", language = "de", channels = 8),
            track(1, "FLAC", "English (FLAC 5.1)", language = "en")
        )
        // "en" is first preference and has a track -> the English pool wins even
        // though the German track is a higher tier.
        assertEquals(1, LosslessAudioTrackDefault.pickDefaultIndex(tracks, listOf("en", "de"), exactMatch))
    }

    @Test
    fun `no preferences means whole set`() {
        val tracks = listOf(
            track(0, "AC-3", "English (AC-3 5.1)", selected = true),
            track(1, "DTS-HD", "English (DTS-HD 5.1)")
        )
        assertEquals(1, LosslessAudioTrackDefault.pickDefaultIndex(tracks, emptyList(), exactMatch))
    }

    @Test
    fun `all-lossy track list changes nothing`() {
        val tracks = listOf(
            track(0, "AC-3", "English (AC-3 5.1)", selected = true),
            track(1, "AAC", "English (AAC Stereo)", channels = 2)
        )
        assertNull(LosslessAudioTrackDefault.pickDefaultIndex(tracks, listOf("en"), exactMatch))
    }

    @Test
    fun `empty track list is a no-op`() {
        assertNull(LosslessAudioTrackDefault.pickDefaultIndex(emptyList(), listOf("en"), exactMatch))
    }
}
