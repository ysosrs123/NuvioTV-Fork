package com.nuvio.tv.core.stream

import org.junit.Assert.*
import org.junit.Test

class SourcePrefetchSignalTest {
    @Test fun `size setting hides known size and invalid sizes never display`() {
        val signal = SourcePrefetchSignal("a", SourcePrefetchPhase.RANKED, null, fileSizeBytes = 1024)
        assertEquals(1024L, signal.withFileSizeVisibility(true).fileSizeBytes)
        assertNull(signal.withFileSizeVisibility(false).fileSizeBytes)
        listOf(null, 0L, -1L).forEach { size -> assertNull(signal.copy(fileSizeBytes = size).withFileSizeVisibility(true).fileSizeBytes) }
    }

    @Test fun `new pick clears old size and late A results cannot replace a new visit to A`() {
        val store = SourcePrefetchSignalStore()
        val first = store.begin("a")
        store.publish(first, SourcePrefetchSignal("a", SourcePrefetchPhase.RANKED, null, fileSizeBytes = 100))
        val second = store.begin("b")
        assertNull(store.signals.value?.fileSizeBytes)
        store.publish(second, SourcePrefetchSignal("b", SourcePrefetchPhase.RANKED, null, fileSizeBytes = 200))
        store.ready(first)
        assertEquals(SourcePrefetchPhase.RANKED, store.signals.value?.phase)
        val third = store.begin("a")
        store.publish(first, SourcePrefetchSignal("a", SourcePrefetchPhase.RANKED, null, fileSizeBytes = 100))
        assertNull(store.signals.value?.fileSizeBytes)
        store.publish(third, SourcePrefetchSignal("a", SourcePrefetchPhase.RANKED, null, fileSizeBytes = 300))
        store.ready(third)
        assertEquals(300L, store.signals.value?.fileSizeBytes)
        assertEquals(SourcePrefetchPhase.READY, store.signals.value?.phase)
    }
}
