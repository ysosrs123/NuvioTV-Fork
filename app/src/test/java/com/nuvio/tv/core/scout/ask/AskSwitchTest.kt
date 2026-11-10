package com.nuvio.tv.core.scout.ask

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.deeplink.DeepLinkParser
import com.nuvio.tv.domain.deeplink.AppDeepLink
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

class AskSwitchTest {
    @Test
    fun `switched off, Home gets no rows and the link does nothing`() = runTest {
        assumeFalse(BuildConfig.SCOUT_ENABLED)
        val provider = AskCatalogProvider(mockk(), mockk())
        assertTrue(provider.addons.first().isEmpty())
        assertEquals(null, DeepLinkParser.parse("nuvio://scout-ask"))
    }

    @Test
    fun `switched on, the link opens Ask`() {
        assumeTrue(BuildConfig.SCOUT_ENABLED)
        assertEquals(AppDeepLink.ScoutAsk, DeepLinkParser.parse("nuvio://scout-ask"))
    }
}
