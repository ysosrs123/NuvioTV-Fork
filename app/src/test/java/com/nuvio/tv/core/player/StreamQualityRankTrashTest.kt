package com.nuvio.tv.core.player

import com.nuvio.tv.core.debrid.DirectDebridStreamFilter
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamQualityRankTrashTest {

    @Test
    fun `preferred group ladder orders remux tiers`() {
        val tier3 = stream("Movie.2023.2160p.UHD.BluRay.REMUX.TrueHD.7.1-NTb.mkv")
        val tier2 = stream("Movie.2023.2160p.UHD.BluRay.REMUX.TrueHD.7.1-playBD.mkv")
        val tier1 = stream("Movie.2023.2160p.UHD.BluRay.REMUX.TrueHD.7.1-FraMeSToR.mkv")
        val untiered = stream("Movie.2023.2160p.UHD.BluRay.REMUX.TrueHD.7.1-RANDOMGRP.mkv")

        val ranked = StreamQualityRank.rank(listOf(untiered, tier3, tier2, tier1))

        assertEquals(listOf(tier1, tier2, tier3, untiered), ranked)
    }

    @Test
    fun `excluded groups are dropped even at higher resolution`() {
        val junk4k = stream("Movie.2023.2160p.BluRay.x265-YIFY.mkv")
        val goodWeb = stream("Movie.2023.1080p.WEB-DL.DDP5.1-FLUX.mkv")

        val ranked = StreamQualityRank.rank(listOf(junk4k, goodWeb))

        assertEquals(listOf(goodWeb), ranked)
    }

    @Test
    fun `all-excluded pool falls back to unfiltered ranking`() {
        val better = stream("Movie.2023.1080p.WEBRip.x264-YIFY.mkv")
        val worse = stream("Movie.2023.720p.HDTV.x264-MeGusta.mkv")

        val ranked = StreamQualityRank.rank(listOf(worse, better))

        assertEquals(2, ranked.size)
        assertEquals(better, ranked.first())
    }

    @Test
    fun `mkv outranks mp4 on otherwise equal streams`() {
        val mp4 = stream(name = "Movie 2160p WEB-DL DDP5.1", url = "https://example.com/a.mp4")
        val mkv = stream(name = "Movie 2160p WEB-DL DDP5.1", url = "https://example.com/a.mkv")

        val ranked = StreamQualityRank.rank(listOf(mp4, mkv))

        assertEquals(mkv, ranked.first())
    }

    @Test
    fun `release group extraction handles compound names and mid-name hyphens`() {
        assertEquals(
            "FLUX",
            DirectDebridStreamFilter.releaseGroupFromText("Movie.2023.2160p.WEB-DL.DDP5.1.Atmos-FLUX.mkv")
        )
        assertEquals(
            "D-Z0N3",
            DirectDebridStreamFilter.releaseGroupFromText("Movie.2010.1080p.BluRay.DTS.x264-D-Z0N3.mkv")
        )
        assertEquals(
            "YIFY",
            DirectDebridStreamFilter.releaseGroupFromText("Movie.2023.1080p.BluRay.x264-YIFY.mkv")
        )
        assertEquals(
            "",
            DirectDebridStreamFilter.releaseGroupFromText("Movie.2008.2160p.WEB-DL")
        )
    }

    private fun stream(
        filename: String? = null,
        name: String? = null,
        url: String? = "https://example.com/stream"
    ): Stream = Stream(
        name = name ?: filename,
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            filename = filename
        ),
        addonName = "TestAddon",
        addonLogo = null
    )
}
