package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class AccountGroupingTest {
    @Test fun xtreamGroupsByHostPortAndUserWhileM3uGroupsByHost() {
        val groups = suggestAccountGroups(listOf(
            AccountGroupHint("a", true, "https://Provider.invalid/", "alice"),
            AccountGroupHint("b", true, "https://provider.invalid:443/other/", "alice"),
            AccountGroupHint("c", true, "https://provider.invalid/", "bob"),
            AccountGroupHint("d", true, "http://provider.invalid/", "alice"),
            AccountGroupHint("e", false, "https://lists.invalid/a.m3u", null),
            AccountGroupHint("f", false, "https://LISTS.invalid/b.m3u?token=1", null),
            AccountGroupHint("g", false, "not a url", null),
            AccountGroupHint("h", true, "https://provider.invalid/", ""),
        ))
        assertEquals(groups["a"], groups["b"]); assertNotEquals(groups["a"], groups["c"]); assertNotEquals(groups["a"], groups["d"])
        assertEquals(groups["e"], groups["f"]); assertTrue(groups.getValue("a").startsWith("xt-")); assertTrue(groups.getValue("e").startsWith("m3u-"))
        assertEquals(DEFAULT_ACCOUNT_ID, groups["g"]); assertEquals(DEFAULT_ACCOUNT_ID, groups["h"])
        assertTrue(groups.values.all { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) })
        assertFalse(groups.values.any { "alice" in it || "provider" in it })
    }
    @Test fun moveItemReordersWithinBounds() {
        assertEquals(listOf("b", "c", "a"), moveItem(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), moveItem(listOf("a", "b", "c"), 2, 0))
        try { moveItem(listOf("a"), 0, 1); fail() } catch (_: IllegalArgumentException) { }
    }
}
