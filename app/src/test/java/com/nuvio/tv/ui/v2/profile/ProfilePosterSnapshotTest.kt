package com.nuvio.tv.ui.v2.profile

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ProfilePosterSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `snapshots remain profile scoped and missing profile stays empty`() {
        val cache = temporary.newFolder("cw_enrichment")
        cache.resolve("inprogress_1.json").writeText("""[{"poster":"one"},{"poster":"one"},{"poster":null}]""")
        cache.resolve("nextup_2.json").writeText("""[{"poster":"two"}]""")
        assertEquals(listOf("one"), readProfilePosters(temporary.root, 1))
        assertEquals(listOf("two"), readProfilePosters(temporary.root, 2))
        assertTrue(readProfilePosters(temporary.root, 3).isEmpty())
        assertEquals(2, cache.listFiles()!!.size)
    }

    @Test fun `large snapshots stop after 24 unique posters`() {
        val json = (1..1000).joinToString(prefix = "[", postfix = "]") { """{"poster":"$it","extra":{"ignored":true}}""" }
        assertEquals((1..24).map(Int::toString), readPosterSnapshot(json.reader()))
    }

    @Test fun `empty entries are bounded and corrupt cache safely falls back`() {
        val json = List(192) { """{"poster":null}""" }.plus("""{"poster":"too late"}""").joinToString(prefix = "[", postfix = "]")
        assertTrue(readPosterSnapshot(json.reader()).isEmpty())
        val cache = temporary.newFolder("cw_enrichment")
        cache.resolve("inprogress_1.json").writeText("invalid")
        assertTrue(readProfilePosters(temporary.root, 1).isEmpty())
    }
    @Test fun `watch history maps provide posters without loading another profile`() {
        assertEquals(listOf("poster-one", "poster-two"), readPosterSnapshot(
            """{"id1":{"poster":"poster-one","name":"One"},"id2":{"poster":"poster-two"}}""".reader()))
    }

    @Test fun `cached TMDB bucket alternatives retain the same artwork`() {
        val candidates = posterCacheCandidates("https://image.tmdb.org/t/p/original/abc.jpg")
        assertEquals("https://image.tmdb.org/t/p/w342/abc.jpg", candidates.first())
        assertTrue(candidates.contains("https://image.tmdb.org/t/p/w342/abc.jpg"))
        assertTrue(candidates.contains("https://image.tmdb.org/t/p/w500/abc.jpg"))
        assertTrue(candidates.all { it.endsWith("/abc.jpg") })
        assertEquals(listOf("https://other.example/poster"), posterCacheCandidates("https://other.example/poster"))
    }
}
