package com.nuvio.tv.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionUtilsTest {
    @Test
    fun `stable release is newer than its prerelease`() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0", "1.1.0-rc.2"))
    }

    @Test
    fun `beta identifiers use numeric ordering`() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0-beta.10", "1.1.0-beta.9"))
    }

    @Test
    fun `prerelease for a later minor is newer than current stable`() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0-beta.1", "1.0.3"))
    }

    @Test
    fun `stable channel patch is not newer than later minor beta`() {
        assertFalse(VersionUtils.isRemoteNewer("1.0.4", "1.1.0-beta.2"))
    }

    @Test
    fun `version prefix and build metadata do not affect precedence`() {
        assertFalse(VersionUtils.isRemoteNewer("v1.0.0+18", "1.0.0+17"))
    }

    @Test
    fun `invalid remote version is not offered`() {
        assertFalse(VersionUtils.isRemoteNewer("latest", "1.0.0"))
    }

    @Test
    fun `current beta naming is recognized as prerelease`() {
        assertTrue(VersionUtils.isPrerelease("0.8.12-beta"))
    }
    @Test fun `fork numbered suffixes and rebuild codes order correctly`() {
        assertTrue(VersionUtils.isRemoteNewer("0.9.4-beta-nt10", "0.9.4-beta-nt9"))
        assertFalse(VersionUtils.isRemoteNewer("0.9.4-beta-nt2", "0.9.4-beta-nt10"))
        assertTrue(VersionUtils.isRemoteNewer("0.9.4-beta-nt1", "0.9.4-beta-nt1", 1374, 1373))
        assertFalse(VersionUtils.isRemoteNewer("0.9.5-beta-nt1", "0.9.4-beta-nt1", 1300, 1373))
        assertFalse(VersionUtils.isRemoteNewer("0.9.4-beta-nt1", "0.9.4-beta-nt1", 1373, 1373))
    }
}
