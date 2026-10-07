package com.nuvio.tv.core.iptv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SetupBundleTest {
    private val bundle = SetupBundle(true,
        listOf(BundleSource("s0", "Lounge", SetupKind.XTREAM, "http://x.example:8080", "me", "secret", "a0", 2),
            BundleSource("s1", "List", SetupKind.M3U, "http://lists.example/get.php?username=me&password=secret", account = "a0"),
            BundleSource("s2", "Portal", SetupKind.STALKER, "http://portal.example/c/", "00:1A:79:00:00:01"),
            BundleSource("s3", "Free", SetupKind.M3U, "https://free.example/list.m3u")),
        listOf(BundleAccount("a0", "Family", 3)),
        listOf(BundleGuide("g0", "Guide", "https://epg.example/guide.xml.gz"), BundleGuide("g1", "Private", "https://epg.example/xmltv.php?username=me&password=secret")),
        listOf(BundleLinks("s0", listOf("g1", SetupBundles.PROVIDER, "g0"))),
        listOf(BundleOverlay("s0", ChannelMatch("BBC One", "101", SetupBundles.locatorDigest("http://x/1"), "bbc1.uk"), "One", 0, false, "hls", "g1", "bbc1"),
            BundleOverlay("s3", ChannelMatch("Two"), hidden = true)),
        BundleSettings(SetupSettings("hls", false, true, "favourites", "focus", "lightest", 5, 15), preview = false, stats = true, theme = "ocean"))

    @Test fun bundlesRoundTrip() {
        val decoded = SetupBundles.decode(SetupBundles.encode(bundle))
        assertEquals(bundle, decoded)
        assertEquals(SetupBundleSummary(4, 0, 2, 1, 2, true, true), decoded.summary())
        assertFalse(bundle.toString().contains("secret"))
        assertFalse(bundle.sources[0].toString().contains("me"))
    }

    @Test fun leavingOutLoginsDropsCredentialsAndGuidesThatCarryThem() {
        val stripped = bundle.withoutLogins()
        val text = SetupBundles.encode(stripped)
        assertFalse(text.contains("secret")); assertFalse(text.contains("00:1A:79"))
        assertFalse(stripped.logins)
        assertEquals(listOf(false, false, false, true), stripped.sources.map { it.complete })
        assertNull(stripped.sources[1].endpoint)
        assertEquals("http://x.example:8080", stripped.sources[0].endpoint)
        assertEquals(listOf("g0"), stripped.guides.map { it.key })
        assertEquals(listOf(SetupBundles.PROVIDER, "g0"), stripped.links.single().guides)
        assertNull(stripped.overlays[0].guideFeed); assertNull(stripped.overlays[0].guideChannel)
        assertEquals("One", stripped.overlays[0].customName)
        assertEquals(SetupBundleSummary(1, 3, 1, 1, 2, true, false), SetupBundles.decode(text).summary())
    }

    @Test fun malformedBundlesAreRefused() {
        val good = JSONObject(SetupBundles.encode(bundle))
        fun rejects(change: (JSONObject) -> Unit) {
            val json = JSONObject(good.toString()).also(change)
            try { SetupBundles.decode(json.toString()); fail(json.toString()) } catch (_: SetupInputException) { }
        }
        rejects { it.put("format", "other") }
        rejects { it.put("version", 2) }
        rejects { it.remove("logins") }
        rejects { it.getJSONArray("sources").getJSONObject(0).put("kind", "guide") }
        rejects { it.getJSONArray("sources").getJSONObject(0).put("endpoint", "http://x.example/player_api.php") }
        rejects { it.getJSONArray("sources").getJSONObject(0).put("account", "a9") }
        rejects { it.getJSONArray("sources").getJSONObject(0).put("connections", 9) }
        rejects { it.getJSONArray("sources").getJSONObject(1).put("key", "s0") }
        rejects { it.getJSONArray("sources").getJSONObject(1).put("label", " ") }
        rejects { it.getJSONArray("guides").getJSONObject(0).put("endpoint", "file:///sdcard/guide.xml") }
        rejects { it.getJSONArray("links").getJSONObject(0).getJSONArray("guides").put("g9") }
        rejects { it.getJSONArray("links").getJSONObject(0).getJSONArray("guides").put("g0") }
        rejects { it.getJSONArray("overlays").getJSONObject(0).put("source", "s9") }
        rejects { it.getJSONArray("overlays").getJSONObject(0).remove("guideChannel") }
        rejects { it.getJSONArray("overlays").getJSONObject(0).put("format", "rtsp") }
        rejects { it.getJSONArray("overlays").getJSONObject(0).put("favourite", -1) }
        rejects { it.getJSONObject("settings").put("startView", "nowhere") }
        rejects { it.getJSONObject("settings").put("recordLate", 7) }
        rejects { it.getJSONObject("settings").put("theme", "<b>") }
        try { SetupBundles.decode("not json"); fail() } catch (_: SetupInputException) { }
    }

    @Test fun loginsAreRecognisedInAddresses() {
        assertTrue(SetupBundles.carriesLogin("http://a.example/get.php?username=a&password=b"))
        assertTrue(SetupBundles.carriesLogin("http://user:pw@a.example/list.m3u"))
        assertFalse(SetupBundles.carriesLogin("https://a.example/list.m3u"))
        assertFalse(SetupBundles.carriesLogin(null))
        assertEquals(32, SetupBundles.locatorDigest("x").length)
    }
}
