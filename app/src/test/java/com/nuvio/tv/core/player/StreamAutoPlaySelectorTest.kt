package com.nuvio.tv.core.player

import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamAutoPlaySelectorTest {

    private val baseInputs = AutoPlaySelection.Inputs(
        mode = StreamAutoPlayMode.FIRST_STREAM,
        regexPattern = "",
        source = StreamAutoPlaySource.ALL_SOURCES,
        installedAddonNames = setOf("AddonA", "AddonB"),
        selectedAddons = emptySet(),
        selectedPlugins = emptySet(),
        preferredBingeGroup = null
    )

    @Test
    fun `AutoPlaySelection matches the argument set the stream screen used to pass`() {
        val first = stream(addonName = "AddonA", url = "https://example.com/a.mkv", name = "1080p")
        val second = stream(addonName = "AddonB", url = "https://example.com/b.mkv", name = "720p")
        val streams = listOf(first, second)

        val viaSelector = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = streams,
            mode = baseInputs.mode,
            regexPattern = baseInputs.regexPattern,
            source = baseInputs.source,
            installedAddonNames = baseInputs.installedAddonNames,
            selectedAddons = baseInputs.selectedAddons,
            selectedPlugins = baseInputs.selectedPlugins,
            preferredBingeGroup = baseInputs.preferredBingeGroup,
            preferBingeGroupInSelection = baseInputs.preferredBingeGroup != null,
            debridStreamPreferences = null
        )
        val viaExtraction = AutoPlaySelection.select(
            streams = streams,
            inputs = baseInputs,
            debridStreamPreferences = null
        )

        assertEquals(viaSelector, viaExtraction)
        assertEquals(first, viaExtraction)
    }

    @Test
    fun `AutoPlaySelection derives binge preference from the inputs`() {
        val other = stream(
            addonName = "AddonA",
            url = "https://example.com/other.mkv",
            bingeGroup = "other-group"
        )
        val preferred = stream(
            addonName = "AddonB",
            url = "https://example.com/preferred.mkv",
            bingeGroup = "same-group"
        )

        // preferredBingeGroup set -> preferBingeGroupInSelection is derived true,
        // and a binge match outranks the mode even in MANUAL.
        val withGroup = AutoPlaySelection.select(
            streams = listOf(other, preferred),
            inputs = baseInputs.copy(
                mode = StreamAutoPlayMode.MANUAL,
                preferredBingeGroup = "same-group"
            ),
            debridStreamPreferences = null
        )
        assertEquals(preferred, withGroup)

        // preferredBingeGroup null -> derived false, MANUAL yields the picker.
        val withoutGroup = AutoPlaySelection.select(
            streams = listOf(other, preferred),
            inputs = baseInputs.copy(mode = StreamAutoPlayMode.MANUAL),
            debridStreamPreferences = null
        )
        assertNull(withoutGroup)
    }

    @Test
    fun `AutoPlaySelection bingeGroupOnly does not fall back to the mode`() {
        val only = stream(
            addonName = "AddonA",
            url = "https://example.com/only.mkv",
            bingeGroup = "other-group"
        )

        val eager = AutoPlaySelection.select(
            streams = listOf(only),
            inputs = baseInputs.copy(preferredBingeGroup = "missing-group"),
            debridStreamPreferences = null,
            bingeGroupOnly = true
        )
        assertNull(eager)

        // Same inputs without the flag fall back to FIRST_STREAM.
        val fallback = AutoPlaySelection.select(
            streams = listOf(only),
            inputs = baseInputs.copy(preferredBingeGroup = "missing-group"),
            debridStreamPreferences = null
        )
        assertEquals(only, fallback)
    }

    @Test
    fun `orderAddonStreams follows installed addon order and leaves plugins last`() {
        val plugin = addonStreams("Plugin")
        val addonB = addonStreams("AddonB")
        val addonA = addonStreams("AddonA")
        val unknown = addonStreams("UnknownPlugin")

        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            streams = listOf(plugin, addonB, addonA, unknown),
            installedOrder = listOf("AddonA", "AddonB")
        )

        assertEquals(listOf(addonA, addonB, plugin, unknown), ordered)
    }

    @Test
    fun `bingeGroup-first selects matching stream before first stream mode`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "1080p",
            bingeGroup = "other-group"
        )
        val preferred = stream(
            addonName = "AddonB",
            url = "https://example.com/preferred.m3u8",
            name = "720p",
            bingeGroup = "same-group"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, preferred),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(preferred, selected)
    }

    @Test
    fun `falls back to normal mode when no bingeGroup match exists`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "First",
            bingeGroup = "group-a"
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            name = "Second",
            bingeGroup = "group-b"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "missing-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(first, selected)
    }

    @Test
    fun `bingeGroup-first respects source and addon plugin filters`() {
        val filteredOutAddonMatch = stream(
            addonName = "AddonFilteredOut",
            url = "https://example.com/addon-match.m3u8",
            bingeGroup = "same-group"
        )
        val allowedPluginMatch = stream(
            addonName = "PluginAllowed",
            url = "https://example.com/plugin-match.m3u8",
            bingeGroup = "same-group"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(filteredOutAddonMatch, allowedPluginMatch),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
            installedAddonNames = setOf("AddonFilteredOut"),
            selectedAddons = emptySet(),
            selectedPlugins = setOf("PluginAllowed"),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true
        )

        if (AppFeaturePolicy.pluginsEnabled) {
            assertEquals(allowedPluginMatch, selected)
        } else {
            assertEquals(filteredOutAddonMatch, selected)
        }
    }

    @Test
    fun `regex mode still works when bingeGroup missing or no match`() {
        val nonMatch = stream(
            addonName = "AddonA",
            url = "https://example.com/a.m3u8",
            name = "720p"
        )
        val regexMatch = stream(
            addonName = "AddonB",
            url = "https://example.com/b.m3u8",
            name = "2160p Remux"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(nonMatch, regexMatch),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "2160p|Remux",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "unmatched-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(regexMatch, selected)
    }

    @Test
    fun `blank preferredBingeGroup behaves as disabled`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            bingeGroup = "group-a"
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            bingeGroup = "group-b"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "   ",
            preferBingeGroupInSelection = true
        )

        assertEquals(first, selected)
    }

    @Test
    fun `manual mode auto-selects matching bingeGroup when prefer enabled`() {
        // Binge-group continuity is intentionally allowed in MANUAL mode so
        // next-episode / resume can skip the picker when a group was locked in.
        val matched = stream(
            addonName = "AddonA",
            url = "https://example.com/match.m3u8",
            bingeGroup = "same-group"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(matched),
            mode = StreamAutoPlayMode.MANUAL,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(matched, selected)
    }

    @Test
    fun `first stream skips checking and not cached local debrid streams`() {
        val checking = stream(
            addonName = "AddonA",
            name = "Checking",
            infoHash = "abc123",
            cacheState = StreamDebridCacheState.CHECKING
        )
        val notCached = stream(
            addonName = "AddonA",
            name = "Not cached",
            infoHash = "def456",
            cacheState = StreamDebridCacheState.NOT_CACHED
        )
        val unknown = stream(
            addonName = "AddonA",
            name = "Unknown",
            infoHash = "unknown",
            cacheState = StreamDebridCacheState.UNKNOWN
        )
        val cached = stream(
            addonName = "AddonA",
            name = "Cached",
            infoHash = "ghi789",
            cacheState = StreamDebridCacheState.CACHED
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(checking, notCached, unknown, cached),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )

        assertEquals(cached, selected)
    }

    @Test
    fun `first stream selects a stream that only has a YouTube id`() {
        // The two streams an addon such as Newsio returns for a YouTube video: the bare ytId,
        // then the watch page as an external link.
        val youTube = stream(addonName = "AddonA", name = "Play video in app", ytId = "dQw4w9WgXcQ")
        val watchPage = stream(
            addonName = "AddonA",
            name = "Open in YouTube app",
            externalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(youTube, watchPage),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )

        if (AppFeaturePolicy.inAppTrailerPlaybackEnabled) {
            assertEquals(youTube, selected)
        } else {
            // Without in-app playback the video would open externally, like the watch page.
            assertNull(selected)
        }
    }

    @Test
    fun `first stream skips a blank YouTube id`() {
        val blank = stream(addonName = "AddonA", name = "Blank", ytId = "  ")
        val direct = stream(addonName = "AddonA", url = "https://example.com/direct.m3u8")

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(blank, direct),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )

        assertEquals(direct, selected)
    }

    @Test
    fun `regex mode selects a matching stream that only has a YouTube id`() {
        val direct = stream(addonName = "AddonA", url = "https://example.com/direct.m3u8", name = "Mirror")
        val youTube = stream(addonName = "AddonA", name = "YouTube", ytId = "dQw4w9WgXcQ")

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(direct, youTube),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "youtube",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )

        if (AppFeaturePolicy.inAppTrailerPlaybackEnabled) {
            assertEquals(youTube, selected)
        } else {
            assertNull(selected)
        }
    }

    @Test
    fun `orderAddonStreams keeps cached local torrent groups in installed addon order`() {
        val regular = addonStreams(
            "AddonA",
            stream(
                addonName = "AddonA",
                url = "https://example.com/regular.m3u8"
            )
        )
        val cachedDebrid = addonStreams(
            "AddonB",
            stream(
                addonName = "AddonB",
                infoHash = "abc123",
                cacheState = StreamDebridCacheState.CACHED
            )
        )

        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            streams = listOf(regular, cachedDebrid),
            installedOrder = listOf("AddonA", "AddonB")
        )

        assertEquals(listOf(regular, cachedDebrid), ordered)
    }

    @Test
    fun `quality rank prefers lossless remux over lossy web-dl`() {
        val lossyWebDl = stream(
            addonName = "AddonA",
            url = "https://example.com/webdl.mp4",
            name = "Movie 2160p WEB-DL DD+ 5.1"
        )
        val losslessRemux = stream(
            addonName = "AddonB",
            url = "https://example.com/remux.mkv",
            name = "Movie 2160p Remux TrueHD Atmos"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(lossyWebDl, losslessRemux),
            mode = StreamAutoPlayMode.QUALITY_RANK,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )

        assertEquals(losslessRemux, selected)
    }

    @Test
    fun `quality rank resolution wins outright over audio`() {
        val fourKLossy = stream(
            addonName = "AddonA",
            url = "https://example.com/a.mkv",
            name = "Movie 2160p WEB-DL AAC"
        )
        val fullHdLossless = stream(
            addonName = "AddonA",
            url = "https://example.com/b.mkv",
            name = "Movie 1080p Remux TrueHD"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(fullHdLossless, fourKLossy),
            mode = StreamAutoPlayMode.QUALITY_RANK,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )

        assertEquals(fourKLossy, selected)
    }

    @Test
    fun `a null binge group makes preferBingeGroupInSelection inert`() {
        // playNextEpisode passed preferBingeGroupInSelection as the SETTING,
        // while AutoPlaySelection derives it from preferredBingeGroup != null.
        // Those disagree in exactly one case: the setting on with no binge
        // group known. This proves the disagreement cannot change the pick --
        // selectAutoPlayStream gates the binge branch on
        // targetBingeGroup.isNotEmpty(), which is false either way.
        val first = stream(addonName = "AddonA", url = "https://example.com/a.mkv", name = "1080p")
        val second = stream(addonName = "AddonB", url = "https://example.com/b.mkv", name = "720p")
        val streams = listOf(first, second)

        fun select(prefer: Boolean): Stream? = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = streams,
            mode = baseInputs.mode,
            regexPattern = baseInputs.regexPattern,
            source = baseInputs.source,
            installedAddonNames = baseInputs.installedAddonNames,
            selectedAddons = baseInputs.selectedAddons,
            selectedPlugins = baseInputs.selectedPlugins,
            preferredBingeGroup = null,
            preferBingeGroupInSelection = prefer,
            debridStreamPreferences = null
        )

        assertEquals(select(true), select(false))
        assertEquals(first, select(true))
    }

    @Test
    fun `server groups are listed before debrid, addons and plugins`() {
        val plugin = addonStreams("Plugin", stream("Plugin", url = "https://example.com/p.mkv"))
        val addonA = addonStreams("AddonA", stream("AddonA", url = "https://example.com/a.mkv"))
        val server = addonStreams("Jellyfin · Den", serverStream("Jellyfin · Den", "4K"))
        val second = addonStreams("Emby · Attic", serverStream("Emby · Attic", "1080p", item = "i2"))

        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            streams = listOf(plugin, addonA, server, second),
            installedOrder = listOf("AddonA", "AddonB")
        )
        assertEquals(listOf(server, second, addonA, plugin), ordered)

        val preferred = StreamAutoPlaySelector.orderAddonStreams(
            streams = listOf(plugin, addonA, server, second),
            installedOrder = listOf("AddonA", "AddonB"),
            preferredNames = setOf("Emby · Attic")
        )
        assertEquals(listOf(second, server, addonA, plugin), preferred)
    }

    @Test
    fun `auto-play treats server streams like any other source`() {
        val addon = stream(addonName = "AddonA", url = "https://example.com/a.mkv", name = "2160p REMUX")
        val server = serverStream("Jellyfin · Den", "1080p")

        assertEquals(
            addon,
            AutoPlaySelection.select(
                streams = listOf(addon, server),
                inputs = baseInputs.copy(mode = StreamAutoPlayMode.FIRST_STREAM),
                debridStreamPreferences = null
            )
        )
        assertEquals(
            addon,
            AutoPlaySelection.select(
                streams = listOf(server, addon),
                inputs = baseInputs.copy(mode = StreamAutoPlayMode.REGEX_MATCH, regexPattern = "2160p"),
                debridStreamPreferences = null
            )
        )
        assertEquals(
            server,
            AutoPlaySelection.select(
                streams = listOf(server, addon),
                inputs = baseInputs.copy(mode = StreamAutoPlayMode.FIRST_STREAM),
                debridStreamPreferences = null
            )
        )
        assertNull(
            AutoPlaySelection.select(listOf(server, addon), baseInputs.copy(mode = StreamAutoPlayMode.MANUAL), debridStreamPreferences = null)
        )
    }

    @Test
    fun `auto-play picks the matching server version when a pattern is set`() {
        val low = serverStream("Jellyfin · Den", "1080p", source = "v1")
        val high = serverStream("Jellyfin · Den", "4K", source = "v2")

        val selected = AutoPlaySelection.select(
            streams = listOf(low, high),
            inputs = baseInputs.copy(mode = StreamAutoPlayMode.REGEX_MATCH, regexPattern = "4K"),
            debridStreamPreferences = null
        )
        assertEquals(high, selected)
    }

    @Test
    fun `server streams stay eligible when auto-play is limited to chosen addons`() {
        val addon = stream(addonName = "AddonA", url = "https://example.com/a.mkv")
        val server = serverStream("Jellyfin · Den", "1080p")

        val selected = AutoPlaySelection.select(
            streams = listOf(server, addon),
            inputs = baseInputs.copy(source = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY, selectedAddons = setOf("AddonA")),
            debridStreamPreferences = null
        )
        assertEquals(server, selected)
    }

    @Test
    fun `a binge group in progress still continues on its addon`() {
        val addon = stream(addonName = "AddonA", url = "https://example.com/a.mkv", bingeGroup = "same-group")
        val server = serverStream("Jellyfin · Den", "1080p")

        val selected = AutoPlaySelection.select(
            streams = listOf(server, addon),
            inputs = baseInputs.copy(preferredBingeGroup = "same-group"),
            debridStreamPreferences = null
        )
        assertEquals(addon, selected)
    }

    private fun serverStream(addonName: String, name: String, item: String = "i1", source: String = "ms1"): Stream =
        stream(addonName = addonName, name = name).copy(
            serverTarget = ServerPlaybackTarget(ServerItemRef("c1", item), mediaSourceId = source)
        )

    private fun stream(
        addonName: String,
        url: String? = null,
        name: String? = null,
        bingeGroup: String? = null,
        infoHash: String? = null,
        cacheState: StreamDebridCacheState? = null,
        ytId: String? = null,
        externalUrl: String? = null
    ): Stream = Stream(
        name = name,
        title = null,
        description = null,
        url = url,
        ytId = ytId,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = externalUrl,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = bingeGroup,
            countryWhitelist = null,
            proxyHeaders = null
        ),
        addonName = addonName,
        addonLogo = null,
        debridCacheStatus = cacheState?.let {
            StreamDebridCacheStatus(
                providerId = "torbox",
                providerName = "Torbox",
                state = it
            )
        }
    )

    private fun addonStreams(
        addonName: String,
        vararg streams: Stream
    ): AddonStreams = AddonStreams(
        addonName = addonName,
        addonLogo = null,
        streams = streams.toList()
    )
}
