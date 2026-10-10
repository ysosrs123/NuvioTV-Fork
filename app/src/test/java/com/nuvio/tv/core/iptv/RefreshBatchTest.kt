package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshBatchTest {
    @Test fun countsFinishedAndFailed() {
        val states = mapOf("a" to RefreshOutcome.DONE, "b" to RefreshOutcome.FAILED, "c" to RefreshOutcome.RUNNING)
        val progress = refreshBatchProgress(listOf("a", "b", "c"), states::get)
        assertEquals(RefreshBatchProgress(3, 2, 1), progress)
        assertTrue(progress.running)
        assertEquals(1, progress.updated)
    }

    @Test fun cancelledOrRemovedKeysCountAsFinished() {
        val progress = refreshBatchProgress(listOf("a", "a", "gone")) { if (it == "a") RefreshOutcome.DONE else null }
        assertEquals(RefreshBatchProgress(2, 2, 0), progress)
        assertFalse(progress.running)
    }

    @Test fun emptyBatchIsFinished() {
        assertFalse(refreshBatchProgress(emptyList()) { RefreshOutcome.RUNNING }.running)
    }

    @Test fun accountKeysShareProviderAccountsOnly() {
        assertEquals(refreshAccountKey(1, "grp", "s1"), refreshAccountKey(1, "grp", "s2"))
        assertTrue(refreshAccountKey(1, "grp", "s1") != refreshAccountKey(2, "grp", "s1"))
        assertTrue(refreshAccountKey(1, "", "s1") != refreshAccountKey(1, "", "s2"))
    }
}
