package com.nuvio.tv.core.iptv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SetupAssignmentsTest {
    private val listing = SetupListing(listOf(SetupListingItem("s1", "Lounge", SetupKind.XTREAM, null, true)),
        listOf(SetupListingItem("g1", "Guide", SetupKind.GUIDE, null, true), SetupListingItem("g2", "Provider", SetupKind.GUIDE, null, false)),
        profile = 1, profiles = listOf(SetupProfile(1, "Me", false), SetupProfile(2, "Kids", true), SetupProfile(3, "Guest", false)),
        links = mapOf("s1" to listOf("g2", "g1")))

    private fun rejects(field: String, block: () -> Unit) { try { block(); fail() } catch (error: SetupInputException) { assertEquals(field, error.field) } }

    @Test fun guideLinksAreParsedAndChecked() {
        val change = SetupAssignments.parseLinks(JSONObject().put("source", "s1").put("guides", JSONArray(listOf("g1", "g2"))).toString())
        assertEquals(listOf("g1", "g2"), change.feeds)
        assertNull(SetupAssignments.checkLinks(change, listing))
        assertEquals("unchanged", SetupAssignments.checkLinks(SetupGuideLinks("s1", listOf("g2", "g1")), listing))
        assertEquals("missing", SetupAssignments.checkLinks(SetupGuideLinks("s1", listOf("g9")), listing))
        assertEquals("missing", SetupAssignments.checkLinks(SetupGuideLinks("s9", emptyList()), listing))
        assertNull(SetupAssignments.checkLinks(SetupGuideLinks("s1", emptyList()), listing))
        rejects("guides") { SetupAssignments.parseLinks(JSONObject().put("source", "s1").put("guides", JSONArray(listOf("g1", "g1"))).toString()) }
        rejects("guides") { SetupAssignments.parseLinks(JSONObject().put("source", "s1").put("guides", JSONArray((0..16).map { "g$it" })).toString()) }
        rejects("guides") { SetupAssignments.parseLinks(JSONObject().put("source", "s1").put("guides", "g1").toString()) }
        rejects("source") { SetupAssignments.parseLinks(JSONObject().put("source", "../x").put("guides", JSONArray()).toString()) }
        rejects("body") { SetupAssignments.parseLinks("{\"source\":\"s1\",\"guides\":[[[[[\"x\"]]]]]}") }
    }

    @Test fun channelGuidesNeedALinkedGuideOrAutomatic() {
        val current = SetupChannel("c1", "BBC One", null, null)
        val set = SetupAssignments.parseChannelGuide(JSONObject().put("source", "s1").put("channel", "c1").put("feed", "g1").put("guide", "bbc1.uk").put("guideName", "BBC One").toString())
        assertEquals("bbc1.uk", set.guideId); assertFalse(set.automatic)
        assertNull(SetupAssignments.checkChannelGuide(set, listing, current))
        assertEquals("missing", SetupAssignments.checkChannelGuide(set, listing, null))
        assertEquals("missing", SetupAssignments.checkChannelGuide(SetupChannelGuide("s1", "c1", "g3", "x", null), listing, current))
        val clear = SetupAssignments.parseChannelGuide(JSONObject().put("source", "s1").put("channel", "c1").toString())
        assertTrue(clear.automatic)
        assertEquals("unchanged", SetupAssignments.checkChannelGuide(clear, listing, current))
        assertNull(SetupAssignments.checkChannelGuide(clear, listing, SetupChannel("c1", "BBC One", "g1", "bbc1.uk")))
        rejects("guide") { SetupAssignments.parseChannelGuide(JSONObject().put("source", "s1").put("channel", "c1").put("feed", "g1").toString()) }
        rejects("channel") { SetupAssignments.parseChannelGuide(JSONObject().put("source", "s1").toString()) }
        rejects("guideName") { SetupAssignments.parseChannelGuide(JSONObject().put("source", "s1").put("channel", "c1").put("feed", "g1").put("guide", "x").put("guideName", "a\u0000").toString()) }
    }

    @Test fun lockedProfilesCannotBeChosenFromThePhone() {
        assertNull(SetupAssignments.checkProfile(SetupAssignments.parseProfile("{\"profile\":3}"), listing))
        assertEquals("locked", SetupAssignments.checkProfile(SetupProfileChoice(2), listing))
        assertEquals("unchanged", SetupAssignments.checkProfile(SetupProfileChoice(1), listing))
        assertEquals("missing", SetupAssignments.checkProfile(SetupProfileChoice(6), listing))
        rejects("profile") { SetupAssignments.parseProfile("{\"profile\":\"2\"}") }
        rejects("profile") { SetupAssignments.parseProfile("{\"profile\":-1}") }
    }

    @Test fun searchQueriesAreBounded() {
        assertEquals("bbc", SetupAssignments.query("  bbc "))
        assertEquals("", SetupAssignments.query(null))
        assertNull(SetupAssignments.query("x".repeat(65)))
        assertNull(SetupAssignments.query("a‮b"))
    }

    @Test fun listingCarriesProfilesAndLinksButNoAddresses() {
        val json = JSONObject(listing.toJson(pending = false))
        assertEquals(1, json.getInt("profile"))
        assertEquals(3, json.getJSONArray("profiles").length())
        assertTrue(json.getJSONArray("profiles").getJSONObject(1).getBoolean("locked"))
        assertEquals("g2", json.getJSONArray("sources").getJSONObject(0).getJSONArray("guides").getString(0))
        assertFalse(json.getJSONArray("guides").getJSONObject(0).has("guides"))
        val channels = JSONObject(SetupAssignments.channelsJson(listOf(SetupChannel("c1", "One", "g1", "x")))).getJSONArray("channels").getJSONObject(0)
        assertEquals("g1", channels.getString("feed"))
        assertEquals("Name", JSONObject(SetupAssignments.guideChannelsJson(listOf(SetupGuideChannel("n", "Name")))).getJSONArray("channels").getJSONObject(0).getString("name"))
    }
}
