package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.Stream
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

    private fun stream(name: String) = Stream(
        name = name,
        title = null,
        description = null,
        url = "https://example.invalid/$name",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
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
            prefetched = PrefetchedSelection(snapshot(), winner),
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
        val liveInstance = stream("4K TB Instant").copy(title = "badged")
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot(), cachedTwin),
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
    fun `a changed setting falls back to a live rank`() {
        val winner = stream("4K TB Instant")
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot(), winner),
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
            winner
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
            prefetched = PrefetchedSelection(snapshot(), stream("4K TB Instant")),
            snapshot = snapshot(),
            streams = listOf(stream("1080p WEB")),
            identityOf = identity
        )
        assertEquals(
            PrefetchedSelectionGate.REASON_KEY_MISS,
            (outcome as SelectionOutcome.Live).reason
        )
    }
}
