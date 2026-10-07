package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class SourceConnectionsTest {
    @Test fun providerLimitIsClampedAndMissingValuesAreIgnored() {
        assertEquals(1, SourceConnections.automaticLimit(1))
        assertEquals(2, SourceConnections.automaticLimit(2))
        assertEquals(4, SourceConnections.automaticLimit(4))
        assertEquals(4, SourceConnections.automaticLimit(12))
        assertNull(SourceConnections.automaticLimit(0))
        assertNull(SourceConnections.automaticLimit(-3))
        assertNull(SourceConnections.automaticLimit(null))
    }

    @Test fun ownAccountKeepsSourceAndChangesOnlyWhenNeeded() {
        assertNull(SourceConnections.change("a", "src-a", 1, 2, 2))
        assertEquals(ConnectionChange("src-a", 3, false), SourceConnections.change("a", "src-a", 1, 2, 3))
        assertEquals(ConnectionChange("own", 1, false), SourceConnections.change("a", "own", 1, 4, 1))
    }

    @Test fun sharedAndLegacyAccountsMoveToTheirOwn() {
        assertEquals(ConnectionChange("src-a", 2, true), SourceConnections.change("a", "xt-group", 2, 2, 2))
        assertEquals(ConnectionChange("src-a", 1, true), SourceConnections.change("a", DEFAULT_ACCOUNT_ID, 1, 1, 1))
        assertEquals("src-" + "x".repeat(76), SourceConnections.ownAccount("x".repeat(90)))
        assertThrows(IllegalArgumentException::class.java) { SourceConnections.change("a", "src-a", 1, 1, 5) }
        assertThrows(IllegalArgumentException::class.java) { SourceConnections.change("a", "src-a", 1, 1, 0) }
    }
}
