package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBadge
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prefetched-selection gate, as assertions. Pure JVM: no Robolectric, no
 * ViewModel harness.
 */
class PrefetchedSelectionGateTest {

    private fun inputs(
        mode: StreamAutoPlayMode = StreamAutoPlayMode.QUALITY_RANK,
        bingeGroup: String? = null
    ) = AutoPlaySelection.Inputs(
        mode = mode,
        regexPattern = "",
        source = StreamAutoPlaySource.ALL_SOURCES,
        installedAddonNames = setOf("Torrentio"),
        selectedAddons = emptySet(),
        selectedPlugins = emptySet(),
        preferredBingeGroup = bingeGroup
    )

    private fun snapshot(
        mode: StreamAutoPlayMode = StreamAutoPlayMode.QUALITY_RANK,
        order: List<String> = listOf("Torrentio"),
        prefs: DebridStreamPreferences? = DebridStreamPreferences()
    ) = SelectionSnapshot(
        inputs = inputs(mode),
        installedAddonOrder = order,
        preferences = prefs
    )

    private fun stream(name: String, sizeBytes: Long? = null) = Stream(
        name = name,
        title = null,
        description = null,
        url = "https://example.invalid/$name",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = sizeBytes?.let {
            StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                videoSize = it
            )
        },
        addonName = "Torrentio",
        addonLogo = null
    )

    /** Identity stands in for badgeMergeKey: stable across a badge-only copy. */
    private val identity: (Stream) -> String = { "${it.addonName}|${it.name}" }

    @Test
    fun `a matching snapshot resolves to the winner from the live list`() {
        val winner = stream("4K TB Instant")
        val live = listOf(stream("1080p WEB"), stream("4K TB Instant"))
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot(), winner, live),
            snapshot = snapshot(),
            streams = live,
            identityOf = identity
        )
        assertTrue(outcome is SelectionOutcome.Hit)
        assertEquals("4K TB Instant", (outcome as SelectionOutcome.Hit).stream.name)
    }

    @Test
    fun `the live instance is returned, not the cached twin`() {
        // applySuccess badge-merges via copy() before the ranking pass, so the
        // cached winner can be a badge-less twin. The gate must hand back the
        // instance that is actually in the presented list.
        val cachedTwin = stream("4K TB Instant")
        val liveInstance = stream("4K TB Instant").copy(badges = listOf(StreamBadge(name = "DV")))
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot(), cachedTwin, listOf(cachedTwin)),
            snapshot = snapshot(),
            streams = listOf(liveInstance),
            identityOf = identity
        )
        assertTrue(outcome is SelectionOutcome.Hit)
        assertTrue((outcome as SelectionOutcome.Hit).stream === liveInstance)
    }

    @Test
    fun `no prefetched entry falls back to a live rank`() {
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = null,
            snapshot = snapshot(),
            streams = listOf(stream("4K TB Instant")),
            identityOf = identity
        )
        assertEquals(
            PrefetchedSelectionGate.REASON_NO_ENTRY,
            (outcome as SelectionOutcome.Live).reason
        )
    }

    @Test
    fun `a winner ranked with the same server order is used with several servers`() {
        val winner = stream("4K TB Instant")
        val servers = setOf("Emby · Home")
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot().copy(preferredServerNames = servers), winner, listOf(winner)),
            snapshot = snapshot().copy(preferredServerNames = servers),
            streams = listOf(winner),
            identityOf = identity
        )
        assertTrue(outcome is SelectionOutcome.Hit)
    }

    @Test
    fun `a different server order falls back to a live rank`() {
        val winner = stream("4K TB Instant")
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot(), winner, listOf(winner)),
            snapshot = snapshot().copy(preferredServerNames = setOf("Silo · Den")),
            streams = listOf(winner),
            identityOf = identity
        )
        assertEquals(
            PrefetchedSelectionGate.REASON_INPUTS_CHANGED,
            (outcome as SelectionOutcome.Live).reason
        )
    }

    @Test
    fun `a changed setting falls back to a live rank`() {
        val winner = stream("4K TB Instant")
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot(), winner, listOf(winner)),
            snapshot = snapshot(mode = StreamAutoPlayMode.FIRST_STREAM),
            streams = listOf(winner),
            identityOf = identity
        )
        assertEquals(
            PrefetchedSelectionGate.REASON_INPUTS_CHANGED,
            (outcome as SelectionOutcome.Live).reason
        )
    }

    @Test
    fun `a reordered addon list falls back even though the name set is equal`() {
        // installedAddonNames is a Set, so a reorder compares equal there.
        // orderAddonStreams consumes the ordered list and rank() is a stable
        // sort, so a reorder can change which stream wins a tie.
        val winner = stream("4K TB Instant")
        val prefetched = PrefetchedSelection(
            snapshot(order = listOf("Torrentio", "Comet")),
            winner,
            listOf(winner)
        )
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = prefetched,
            snapshot = snapshot(order = listOf("Comet", "Torrentio")),
            streams = listOf(winner),
            identityOf = identity
        )
        assertEquals(
            PrefetchedSelectionGate.REASON_INPUTS_CHANGED,
            (outcome as SelectionOutcome.Live).reason
        )
    }

    @Test
    fun `a winner absent from the live list falls back to a live rank`() {
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot(), stream("4K TB Instant"), listOf(stream("1080p WEB"))),
            snapshot = snapshot(),
            streams = listOf(stream("1080p WEB")),
            identityOf = identity
        )
        assertEquals(
            PrefetchedSelectionGate.REASON_KEY_MISS,
            (outcome as SelectionOutcome.Live).reason
        )
    }

    @Test
    fun `a larger file that arrived after the rank falls back to a live rank`() {
        val small = stream("small", sizeBytes = 1_546_188_226L)
        val ranked = listOf(small)
        val live = listOf(small, stream("large", sizeBytes = 6_131_000_000L))
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot(), small, ranked),
            snapshot = snapshot(),
            streams = live,
            identityOf = identity
        )
        assertEquals(
            PrefetchedSelectionGate.REASON_CANDIDATES_CHANGED,
            (outcome as SelectionOutcome.Live).reason
        )
    }

    @Test
    fun `a winner that stopped being cached falls back to a live rank`() {
        val winner = stream("4K TB Instant")
        val other = stream("1080p WEB")
        val nowUncached = winner.copy(
            debridCacheStatus = StreamDebridCacheStatus(
                providerId = "torbox",
                providerName = "Torbox",
                state = StreamDebridCacheState.NOT_CACHED
            )
        )
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot(), winner, listOf(winner, other)),
            snapshot = snapshot(),
            streams = listOf(nowUncached, other),
            identityOf = identity
        )
        assertEquals(
            PrefetchedSelectionGate.REASON_CANDIDATES_CHANGED,
            (outcome as SelectionOutcome.Live).reason
        )
    }

    @Test
    fun `a changed size on another stream falls back to a live rank`() {
        val winner = stream("a", sizeBytes = 5_000L)
        val other = stream("b", sizeBytes = 4_000L)
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot(), winner, listOf(winner, other)),
            snapshot = snapshot(),
            streams = listOf(winner, stream("b", sizeBytes = 9_000L)),
            identityOf = identity
        )
        assertEquals(
            PrefetchedSelectionGate.REASON_CANDIDATES_CHANGED,
            (outcome as SelectionOutcome.Live).reason
        )
    }

    @Test
    fun `the same streams in a new order fall back to a live rank`() {
        val first = stream("a")
        val second = stream("b")
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot(), first, listOf(first, second)),
            snapshot = snapshot(),
            streams = listOf(second, first),
            identityOf = identity
        )
        assertEquals(
            PrefetchedSelectionGate.REASON_CANDIDATES_CHANGED,
            (outcome as SelectionOutcome.Live).reason
        )
    }

    @Test
    fun `badges added after the rank keep the prefetched winner`() {
        val winner = stream("a")
        val other = stream("b")
        val live = listOf(
            winner.copy(badges = listOf(StreamBadge(name = "DV"))),
            other.copy(badges = listOf(StreamBadge(name = "HDR")))
        )
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot(), winner, listOf(winner, other)),
            snapshot = snapshot(),
            streams = live,
            identityOf = identity
        )
        assertTrue((outcome as SelectionOutcome.Hit).stream === live[0])
    }
}
