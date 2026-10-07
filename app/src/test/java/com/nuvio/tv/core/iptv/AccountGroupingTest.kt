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
    @Test fun stalkerGroupsByPortalHostAndMac() {
        val groups = suggestAccountGroups(listOf(
            AccountGroupHint("a", false, "http://Portal.invalid/c/", "00:1a:79:00:00:01", stalker = true),
            AccountGroupHint("b", false, "http://portal.invalid:80/stalker_portal/c/", "00:1A:79:00:00:01", stalker = true),
            AccountGroupHint("c", false, "http://portal.invalid/c/", "00:1A:79:00:00:02", stalker = true),
            AccountGroupHint("d", false, "http://other.invalid/c/", "00:1A:79:00:00:01", stalker = true),
            AccountGroupHint("e", false, "http://portal.invalid/c/", "not a mac", stalker = true),
            AccountGroupHint("f", false, "http://portal.invalid/list.m3u", null),
        ))
        assertEquals(groups["a"], groups["b"]); assertNotEquals(groups["a"], groups["c"]); assertNotEquals(groups["a"], groups["d"])
        assertTrue(groups.getValue("a").startsWith("stb-")); assertEquals(DEFAULT_ACCOUNT_ID, groups["e"])
        assertNotEquals(groups["a"], groups["f"])
        assertTrue(groups.values.all { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) && "portal" !in it })
    }
    @Test fun suggestionsListOnlyGroupsNotYetShared() {
        val suggested = mapOf("a" to "xt-1", "b" to "xt-1", "c" to "xt-2", "d" to "xt-3", "e" to "xt-3", "f" to DEFAULT_ACCOUNT_ID, "g" to DEFAULT_ACCOUNT_ID)
        val current = mapOf("a" to "src-a", "b" to "src-b", "c" to "src-c", "d" to "grp-1", "e" to "grp-1", "f" to "src-f", "g" to "src-g")
        assertEquals(listOf("xt-1" to listOf("a", "b")), AccountGroups.suggestions(suggested, current))
    }
    @Test fun groupsAreSharedAccountsAndNewIdsAreValid() {
        assertTrue(AccountGroups.isGroup("src-a", 2)); assertFalse(AccountGroups.isGroup("src-a", 1)); assertFalse(AccountGroups.isGroup("src-a", 0))
        assertTrue(AccountGroups.isGroup("grp-1", 0)); assertTrue(AccountGroups.isGroup(DEFAULT_ACCOUNT_ID, 1)); assertFalse(AccountGroups.isGroup(DEFAULT_ACCOUNT_ID, 0))
        val id = AccountGroups.newId(java.util.Random(1))
        assertTrue(id.matches(Regex("grp-[0-9a-f]{12}")))
    }
    @Test fun moveItemReordersWithinBounds() {
        assertEquals(listOf("b", "c", "a"), moveItem(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), moveItem(listOf("a", "b", "c"), 2, 0))
        try { moveItem(listOf("a"), 0, 1); fail() } catch (_: IllegalArgumentException) { }
    }
}
