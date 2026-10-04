package com.nuvio.tv.core.di

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The redaction is security-adjacent and fails open when wrong - a bad pattern
 * silently logs the credential rather than throwing - so the cases below pin
 * the shape of a real BASIC-level log line, not just the happy path.
 */
class CredentialRedactionTest {

    @Test
    fun `mdblist api key is masked in a request line`() {
        val line = "--> GET https://api.mdblist.com/sync/playback?apikey=abc123DEF http/1.1"
        assertEquals(
            "--> GET https://api.mdblist.com/sync/playback?apikey=REDACTED http/1.1",
            redactCredentialQueryParams(line)
        )
    }

    @Test
    fun `a key in a later position is masked and neighbouring params survive`() {
        val line = "<-- 200 OK https://api.mdblist.com/rating/movie/tt1?provider=imdb&apikey=SECRET&fmt=json (312ms)"
        assertEquals(
            "<-- 200 OK https://api.mdblist.com/rating/movie/tt1?provider=imdb&apikey=REDACTED&fmt=json (312ms)",
            redactCredentialQueryParams(line)
        )
    }

    @Test
    fun `other credential names are covered and casing is ignored`() {
        assertEquals(
            "--> GET https://x/y?api_key=REDACTED http/1.1",
            redactCredentialQueryParams("--> GET https://x/y?api_key=tmdbkey http/1.1")
        )
        assertEquals(
            "--> GET https://x/y?token=REDACTED http/1.1",
            redactCredentialQueryParams("--> GET https://x/y?token=torboxtoken http/1.1")
        )
        assertEquals(
            "--> GET https://x/y?ApiKey=REDACTED http/1.1",
            redactCredentialQueryParams("--> GET https://x/y?ApiKey=mixed http/1.1")
        )
    }

    @Test
    fun `params that merely contain key are left intact`() {
        // A substring test would corrupt this TMDB discover call and destroy
        // information the log exists to carry.
        val line = "--> GET https://x/discover?with_keywords=210024&page=2 http/1.1"
        assertEquals(line, redactCredentialQueryParams(line))
        val noQuery = "--> GET https://x/sync/playback http/1.1"
        assertEquals(noQuery, redactCredentialQueryParams(noQuery))
    }
}
