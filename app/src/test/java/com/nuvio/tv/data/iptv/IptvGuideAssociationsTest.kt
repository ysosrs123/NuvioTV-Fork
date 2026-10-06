package com.nuvio.tv.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class IptvGuideAssociationsTest {
    @Test fun removedFeedLeavesOrderAndPriorityOfTheOthers() {
        val linked = IptvGuideAssociations(listOf("a", "gone", "b"), listOf("gone", "b", "a"))
        assertEquals(IptvGuideAssociations(listOf("a", "b"), listOf("b", "a")), linked.without("gone"))
        assertEquals(linked, linked.without("unknown"))
        assertEquals(IptvGuideAssociations(), IptvGuideAssociations(listOf("gone"), listOf("gone")).without("gone"))
    }
}
