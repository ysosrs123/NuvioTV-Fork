package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamRowTextTest {
    private val facts = "4K · WEB-DL · DV · HEVC · DD+ · Atmos · 5.1"

    private fun stream(name: String?, title: String?, description: String?) = Stream(
        name = name,
        title = title,
        description = description,
        url = "https://example.com/v.mkv",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "AIOStreams",
        addonLogo = null
    )

    private fun rowText(stream: Stream, factsLine: String = facts) = streamRowText(
        displayName = stream.getDisplayNameOrNull(),
        displayDescription = stream.getDisplayDescription(),
        factsLine = factsLine,
        fallbackTitle = "AIOStreams - FLUX"
    )

    @Test fun showsTheAddonsOwnNameAndDescription() {
        val text = rowText(stream("[TB+] AIO\n4K DV", null, "Show.S01E01.2160p.mkv\nEnglish / Italian"))
        assertEquals("[TB+] AIO\n4K DV", text.title)
        assertEquals("Show.S01E01.2160p.mkv\nEnglish / Italian", text.subtitle)
    }

    @Test fun usesTheLegacyTitleFieldAsDescriptionLikeTheSourcesPage() {
        val text = rowText(stream("Tortoise\n4k", "Movie.2160p.mkv\n👤 12 💾 5 GB\nMulti Audio", null))
        assertEquals("Tortoise\n4k", text.title)
        assertEquals("Movie.2160p.mkv\n👤 12 💾 5 GB\nMulti Audio", text.subtitle)
    }

    @Test fun factsLineOnlyWhenTheAddonGivesNoDescription() {
        assertEquals(facts, rowText(stream("Addon 4K", null, null)).subtitle)
        assertEquals(facts, rowText(stream("Addon 4K", null, "   ")).subtitle)
    }

    @Test fun descriptionThatRepeatsTheNameFallsBackToFacts() {
        val text = rowText(stream(null, null, "Only a description"))
        assertEquals("Only a description", text.title)
        assertEquals(facts, text.subtitle)
    }

    @Test fun blankNameUsesTheFallbackTitle() {
        val text = rowText(stream(" ", null, "Details"))
        assertEquals("AIOStreams - FLUX", text.title)
        assertEquals("Details", text.subtitle)
        assertEquals("AIOStreams - FLUX", rowText(stream(null, null, null)).title)
    }

    @Test fun noDescriptionAndNoFactsLeavesNoSubtitle() {
        assertNull(rowText(stream("Addon", null, null), factsLine = "").subtitle)
    }

    @Test fun trimsSurroundingBlankLines() {
        val text = rowText(stream("Addon\n", null, "\nLine one\nLine two\n"))
        assertEquals("Addon", text.title)
        assertEquals("Line one\nLine two", text.subtitle)
    }
}
