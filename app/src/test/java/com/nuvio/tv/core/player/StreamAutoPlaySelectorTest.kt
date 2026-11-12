package com.nuvio.tv.core.player

import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.pluginBingeGroup
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
    fun `bingeGroup-first follows the group outside the source filter`() {
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

        assertEquals(filteredOutAddonMatch, selected)
    }

    @Test
    fun `without a binge group match the source filter still decides`() {
        val filteredOutAddon = stream(
            addonName = "AddonFilteredOut",
            url = "https://example.com/addon.m3u8",
            bingeGroup = "other-group"
        )
        val allowedPlugin = stream(
            addonName = "PluginAllowed",
            url = "https://example.com/plugin.m3u8"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(filteredOutAddon, allowedPlugin),
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
            assertEquals(allowedPlugin, selected)
        } else {
            assertEquals(filteredOutAddon, selected)
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
    fun `regex mode sees the file name of a media server source`() {
        val server = stream(addonName = "Jellyfin · Home", name = "Jellyfin · 2160p").copy(
            description = "HEVC • DV P7.6 (HDR10)\nTrueHD Atmos • 7.1\nMKV • 52 Mbps • 53.6 GB • Home",
            behaviorHints = StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                filename = "Film 2160p UHD BluRay REMUX TrueHD.mkv"
            ),
            serverTarget = ServerPlaybackTarget(ServerItemRef("c1", "m1"), "ms1")
        )
        fun pick(pattern: String) = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(server),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = pattern,
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Jellyfin · Home"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = null,
            preferBingeGroupInSelection = false
        )

        assertEquals(server, pick("BluRay"))
        assertNull(pick("WEB-DL"))
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
    fun `plugin binge group is selected when the autoplay source only allows addons`() {
        val plugin = stream(
            addonName = "ExamplePlugin",
            url = "https://example.com/e6.mkv",
            name = "480p",
            bingeGroup = "local-plugin-example-plugin|480"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(plugin),
            mode = StreamAutoPlayMode.MANUAL,
            regexPattern = "",
            source = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
            installedAddonNames = setOf("ExampleAddon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "local-plugin-example-plugin|480",
            preferBingeGroupInSelection = true
        )

        assertEquals(plugin, selected)
    }

    @Test
    fun `plugin binge group selects the played quality instead of the first stream`() {
        val fullHd = stream(
            addonName = "Example",
            url = "https://example.com/1080.m3u8",
            name = "1080p",
            bingeGroup = "local-plugin-example|1080p"
        )
        val hd = stream(
            addonName = "Example",
            url = "https://example.com/720.m3u8",
            name = "720p",
            bingeGroup = "local-plugin-example|720p"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(fullHd, hd),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = emptySet(),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "local-plugin-example|720p",
            preferBingeGroupInSelection = true
        )

        assertEquals(hd, selected)
    }

    @Test
    fun `plugin binge group keeps the source and label when the episode title and host change`() {
        val saved = pluginBingeGroup(
            scraperId = "src",
            url = "https://cdn-a.example.net/ep1.m3u8",
            name = "ExampleHost",
            title = "Episode 1 Dual Audio",
            quality = "720p"
        )
        val otherLabel = pluginBingeGroup(
            scraperId = "src",
            url = "https://cdn-b.example.net/ep2.m3u8",
            name = "ExampleHost",
            title = "Episode 2 Original",
            quality = "720p"
        )
        val sameLabel = pluginBingeGroup(
            scraperId = "src",
            url = "https://cdn-c.example.net/ep2.m3u8",
            name = "ExampleHost",
            title = "Episode 2 Dual Audio",
            quality = "720p"
        )
        val original = stream(
            addonName = "Example",
            url = "https://example.com/original.m3u8",
            bingeGroup = otherLabel
        )
        val dual = stream(
            addonName = "Example",
            url = "https://example.com/dual.m3u8",
            bingeGroup = sameLabel
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(original, dual),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = emptySet(),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = saved,
            preferBingeGroupInSelection = true
        )

        assertEquals(dual, selected)
    }

    @Test
    fun `a different plugin quality falls back when that variant is missing`() {
        val fullHd = stream(
            addonName = "Example",
            url = "https://example.com/1080.m3u8",
            bingeGroup = "local-plugin-example|1080p"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(fullHd),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = emptySet(),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "local-plugin-example|720p",
            preferBingeGroupInSelection = true
        )

        assertEquals(fullHd, selected)
    }

    @Test
    fun `legacy plugin binge group still matches the same provider`() {
        val other = stream(
            addonName = "Other",
            url = "https://example.com/other.m3u8",
            bingeGroup = "local-plugin-other|1080p"
        )
        val provider = stream(
            addonName = "Example",
            url = "https://example.com/1080.m3u8",
            bingeGroup = "local-plugin-example|1080p"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(other, provider),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = emptySet(),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "local-plugin-example",
            preferBingeGroupInSelection = true
        )

        assertEquals(provider, selected)
    }

    @Test
    fun `addon binge group still matches a torrent while its debrid check is running`() {
        val nextFilename = "Show.S01E02.1080p.mkv"
        val checking = stream(
            addonName = "ExampleAddon",
            name = "1080p",
            infoHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            bingeGroup = "example-addon|1080p",
            cacheState = StreamDebridCacheState.CHECKING,
            filename = nextFilename
        )
        val otherQuality = stream(
            addonName = "ExampleAddon",
            name = "720p",
            infoHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            bingeGroup = "example-addon|720p",
            cacheState = StreamDebridCacheState.CACHED,
            filename = "Show.S01E02.720p.mkv"
        )

        val filenameMatches = listOf(otherQuality, checking).filter { candidate ->
            FilenameEpisodeMatcher.matches(candidate.behaviorHints?.filename, nextFilename)
        }
        val selected = StreamAutoPlaySelector.selectEarlyBingeGroupMatch(
            filenameMatches,
            "example-addon|1080p"
        )

        assertEquals(checking, selected)
        assertNull(
            StreamAutoPlaySelector.selectEarlyBingeGroupMatch(
                filenameMatches,
                "example-addon|720p"
            )
        )
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

    @Test
    fun `best quality picks the largest of otherwise equal files whatever the addon order`() {
        val small = aioStream("small", "1.44 GiB", sizeBytes = gib(1.44))
        val mid = aioStream("mid", "5.71 GiB", sizeBytes = gib(5.71))
        val large = aioStream("large", "5.9 GiB", sizeBytes = gib(5.9))

        assertEquals(large, selectBest(listOf(small, mid, large)))
        assertEquals(large, selectBest(listOf(large, mid, small)))
        assertEquals(mid, selectBest(listOf(small, mid)))
    }

    @Test
    fun `best quality reads GiB sizes from the text when no size field is sent`() {
        val small = aioStream("small", "1.44 GiB", sizeBytes = null)
        val large = aioStream("large", "5.71 GiB", sizeBytes = null)

        assertEquals(large, selectBest(listOf(small, large)))
    }

    @Test
    fun `best quality with a binge group picks the best file inside that group`() {
        val groupSmall = aioStream("g-small", "1.44 GiB", gib(1.44), bingeGroup = "aio|FLUX")
        val outsider = aioStream("outsider", "9.8 GiB", gib(9.8), bingeGroup = "aio|other")
        val groupLarge = aioStream("g-large", "5.71 GiB", gib(5.71), bingeGroup = "aio|FLUX")

        assertEquals(groupLarge, selectBest(listOf(groupSmall, outsider, groupLarge), bingeGroup = "aio|FLUX"))
        assertEquals(
            groupLarge,
            selectBest(listOf(groupSmall, outsider, groupLarge), bingeGroup = "aio|FLUX", bingeGroupOnly = true)
        )
    }

    @Test
    fun `best quality with a binge group skips files in the group that cannot play`() {
        val groupSmall = aioStream("g-small", "1.44 GiB", gib(1.44), bingeGroup = "aio|FLUX")
        val groupLargeUncached = aioStream("g-large", "5.71 GiB", gib(5.71), bingeGroup = "aio|FLUX").copy(
            debridCacheStatus = StreamDebridCacheStatus(
                providerId = "torbox",
                providerName = "Torbox",
                state = StreamDebridCacheState.NOT_CACHED
            )
        )

        assertEquals(groupSmall, selectBest(listOf(groupSmall, groupLargeUncached), bingeGroup = "aio|FLUX"))
    }

    @Test
    fun `first stream with a binge group still takes the first match in list order`() {
        val groupSmall = aioStream("g-small", "1.44 GiB", gib(1.44), bingeGroup = "aio|FLUX")
        val groupLarge = aioStream("g-large", "5.71 GiB", gib(5.71), bingeGroup = "aio|FLUX")

        assertEquals(
            groupSmall,
            selectBest(listOf(groupSmall, groupLarge), bingeGroup = "aio|FLUX", mode = StreamAutoPlayMode.FIRST_STREAM)
        )
        assertEquals(
            groupSmall,
            selectBest(listOf(groupSmall, groupLarge), bingeGroup = "aio|FLUX", mode = StreamAutoPlayMode.MANUAL)
        )
    }

    @Test
    fun `best quality inside a binge group still drops excluded files`() {
        val excludedLarge = aioStream("g-large", "5.71 GiB", gib(5.71), bingeGroup = "aio|FLUX")
        val keptSmall = aioStream("g-small", "1.44 GiB", gib(1.44), bingeGroup = "aio|FLUX").copy(
            description = "Testshow S01E06 2160p WEB-DL DV HDR DDP5.1 Atmos HEVC-NTb\n1.44 GiB"
        )

        assertEquals(
            keptSmall,
            selectBest(
                listOf(excludedLarge, keptSmall),
                bingeGroup = "aio|FLUX",
                preferences = DebridStreamPreferences(excludedReleaseGroups = listOf("FLUX"))
            )
        )
    }

    @Test
    fun `regex mode with a binge group still takes the first match in list order`() {
        val groupSmall = aioStream("g-small", "1.44 GiB", gib(1.44), bingeGroup = "aio|FLUX")
        val groupLarge = aioStream("g-large", "5.71 GiB", gib(5.71), bingeGroup = "aio|FLUX")

        assertEquals(
            groupSmall,
            AutoPlaySelection.select(
                streams = listOf(groupSmall, groupLarge),
                inputs = baseInputs.copy(
                    mode = StreamAutoPlayMode.REGEX_MATCH,
                    regexPattern = "2160p",
                    installedAddonNames = setOf("AIOStreams"),
                    preferredBingeGroup = "aio|FLUX"
                ),
                debridStreamPreferences = null
            )
        )
    }

    private fun selectBest(
        streams: List<Stream>,
        bingeGroup: String? = null,
        mode: StreamAutoPlayMode = StreamAutoPlayMode.QUALITY_RANK,
        bingeGroupOnly: Boolean = false,
        preferences: DebridStreamPreferences? = null
    ): Stream? = AutoPlaySelection.select(
        streams = streams,
        inputs = baseInputs.copy(
            mode = mode,
            installedAddonNames = setOf("AIOStreams"),
            preferredBingeGroup = bingeGroup
        ),
        debridStreamPreferences = preferences,
        bingeGroupOnly = bingeGroupOnly
    )

    private fun gib(value: Double): Long = (value * 1024 * 1024 * 1024).toLong()

    private fun aioStream(
        id: String,
        sizeText: String,
        sizeBytes: Long?,
        bingeGroup: String? = null,
        name: String = "AIOStreams 4K DV"
    ): Stream = Stream(
        name = name,
        title = null,
        description = "Testshow S01E06 2160p WEB-DL DV HDR DDP5.1 Atmos HEVC-FLUX\n$sizeText",
        url = "https://example.com/$id.mkv",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = bingeGroup,
            countryWhitelist = null,
            proxyHeaders = null,
            videoSize = sizeBytes
        ),
        addonName = "AIOStreams",
        addonLogo = null
    )

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
        externalUrl: String? = null,
        filename: String? = null
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
            proxyHeaders = null,
            filename = filename
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
