package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class VodTitlesTest {
    private fun title(raw: String, year: Int? = null) = VodTitles.parse(raw, year)

    @Test fun providerPrefixesQualityTagsAndBracketedYearsAreRemoved() {
        val cases = mapOf(
            "EN - The Matrix (1999)" to Triple("The Matrix", "matrix", 1999),
            "|AU| Mad Max: Fury Road 2015 FHD" to Triple("Mad Max: Fury Road", "mad max fury road", 2015),
            "4K-NF - Stranger Things" to Triple("Stranger Things", "stranger things", null),
            "[EN] Dune: Part Two (2024) [4K] (Multi-Sub)" to Triple("Dune: Part Two", "dune part two", 2024),
            "FR: Amélie (2001) HEVC" to Triple("Amélie", "amelie", 2001),
            "UK | The Office (UK) (2001)" to Triple("The Office", "office", 2001),
            "EN-US - Oppenheimer (2023) UHD HDR" to Triple("Oppenheimer", "oppenheimer", 2023),
            "Inception [2010] [1080p] [Dubbed]" to Triple("Inception", "inception", 2010),
        )
        for ((raw, expected) in cases) {
            val parsed = title(raw)
            assertEquals(raw, expected.first, parsed.display)
            assertEquals(raw, expected.second, parsed.key)
            assertEquals(raw, expected.third, parsed.year)
        }
    }

    @Test fun titlesThatLookLikeDecorationsSurvive() {
        assertEquals("(500) Days of Summer", title("(500) Days of Summer (2009)").display)
        assertEquals(2009, title("(500) Days of Summer (2009)").year)
        assertEquals("1917", title("1917 (2019)").display)
        assertEquals(2019, title("1917 (2019)").year)
        assertEquals("2012", title("2012").display)
        assertNull(title("2012").year)
        assertEquals("Blade Runner 2049", title("Blade Runner 2049 (2017)").display)
        assertEquals("Wonder Woman 1984", title("Wonder Woman 1984", 2020).display)
        assertEquals("Ant-Man and the Wasp", title("Ant-Man and the Wasp").display)
        assertEquals("WALL-E", title("WALL-E (2008)").display)
        assertEquals("Thor: Ragnarok", title("Thor: Ragnarok").display)
        assertEquals("Tunnel Vision", title("Tunnel Vision HD").display)
        assertEquals("Up", title("EN - Up (2009)").display)
    }

    @Test fun normalisationFoldsAccentsArticlesAndPunctuation() {
        assertEquals("amelie", VodTitles.normalise("Amélie"))
        assertEquals("pokemon detective pikachu", VodTitles.normalise("Pokémon: Detective Pikachu"))
        assertEquals("matrix", VodTitles.normalise("Matrix, The"))
        assertEquals("fast and furious", VodTitles.normalise("Fast & Furious"))
        assertEquals("oceans eleven", VodTitles.normalise("Ocean's Eleven"))
        assertEquals("smorgasbord", VodTitles.normalise("Smörgåsbord"))
        assertEquals("strasse", VodTitles.normalise("Straße"))
        assertEquals("the", VodTitles.normalise("The"))
        assertEquals("spidermannowayhome", title("EN - Spider-Man: No Way Home (2021) 4K").matchKey)
        assertEquals(title("Spiderman No Way Home").matchKey, title("Spider-Man: No Way Home").matchKey)
    }

    @Test fun parsingIsIdempotentOnItsOwnOutput() {
        for (raw in listOf("EN - The Matrix (1999)", "|AU| Mad Max: Fury Road 2015 FHD", "Amélie", "The Office (US)")) {
            val once = title(raw)
            val twice = title(once.display)
            assertEquals(once.key, twice.key)
            assertEquals(once.key, VodTitles.normalise(once.key))
        }
    }

    @Test fun yearIsTakenFromProviderFieldsOrName() {
        assertEquals(1999, title("The Matrix", 1999).year)
        assertEquals(1999, VodTitles.yearOf("1999-03-31"))
        assertNull(VodTitles.yearOf("12345"))
        assertEquals(2015, title("Mad Max Fury Road - 2015").year)
    }

    @Test fun episodeMarkersAreRecognised() {
        val one = VodTitles.episode("EN - Breaking Bad S01E02 - Cat's in the Bag")!!
        assertEquals(VodEpisodeMarker("Breaking Bad", 1, 2, "Cat's in the Bag"), one)
        assertEquals(VodEpisodeMarker("The Bear", 2, 7, null), VodTitles.episode("The Bear S02 E07"))
        assertEquals(VodEpisodeMarker("Bluey", 3, 12, null), VodTitles.episode("Bluey 3x12"))
        assertEquals(VodEpisodeMarker("Severance", 1, 1, "Good News About Hell"), VodTitles.episode("Severance - Season 1 Episode 1 - Good News About Hell"))
        assertNull(VodTitles.episode("The Matrix (1999)"))
        assertNull(VodTitles.episode("S01E02"))
        assertNull(VodTitles.episode("Mass Effect"))
    }

    @Test fun rankingPrefersExactYearThenNeighbouringYearAndRejectsOthers() {
        val candidates = listOf(
            VodTitleCandidate("remake", "EN - Dune (2021) 4K", null, sourcePosition = 0),
            VodTitleCandidate("original", "EN - Dune (1984)", null, sourcePosition = 0),
            VodTitleCandidate("late", "|AU| Dune 2022", null, sourcePosition = 1),
            VodTitleCandidate("unknown", "Dune", null, sourcePosition = 0),
            VodTitleCandidate("other", "Dune: Part Two (2024)", null, sourcePosition = 0),
        )
        assertEquals(listOf("remake", "late", "unknown"), VodTitles.rank("Dune", 2021, candidates))
        assertEquals(listOf("original", "unknown"), VodTitles.rank("Dune", 1984, candidates))
        assertEquals(listOf("other"), VodTitles.rank("Dune: Part Two", 2024, candidates))
    }

    @Test fun rankingUsesTmdbToSeparateSameNamedTitles() {
        val candidates = listOf(
            VodTitleCandidate("uk", "The Office (UK) (2001)", null, tmdbId = "2996"),
            VodTitleCandidate("us", "The Office (US) (2005)", null, tmdbId = "2316"),
            VodTitleCandidate("plain", "The Office", null),
        )
        assertEquals(listOf("us", "plain"), VodTitles.rank("The Office", 2005, candidates, tmdbId = "2316"))
        assertEquals(listOf("uk", "plain"), VodTitles.rank("The Office", 2001, candidates))
    }

    @Test fun rankingPrefersEarlierSourcesAndCleanerNames() {
        val candidates = listOf(
            VodTitleCandidate("second", "Heat (1995)", 1995, sourcePosition = 1),
            VodTitleCandidate("decorated", "EN - Heat (1995) [4K] (Multi-Sub)", 1995, sourcePosition = 0),
            VodTitleCandidate("clean", "Heat", 1995, sourcePosition = 0),
        )
        assertEquals(listOf("clean", "decorated", "second"), VodTitles.rank("Heat", 1995, candidates))
        assertTrue(VodTitles.rank("", 1995, candidates).isEmpty())
    }
}
