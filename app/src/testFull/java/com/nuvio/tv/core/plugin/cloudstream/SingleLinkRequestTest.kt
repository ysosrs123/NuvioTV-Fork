package com.nuvio.tv.core.plugin.cloudstream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleLinkRequestTest {

    @Test
    fun `a film request can use a single link`() {
        assertTrue(singleLinkFitsRequest(season = null, episode = null))
    }

    @Test
    fun `an episode request cannot use a film page's link`() {
        assertFalse(singleLinkFitsRequest(season = 2, episode = 5))
        assertFalse(singleLinkFitsRequest(season = null, episode = 5))
    }
}
