package com.nuvio.tv.data.trailer

import org.junit.Assert.*
import org.junit.Test

class TrailerPreloadGateTest {
    @Test fun `playback cancels warming and blocks further speculative requests until all readers close`() {
        val gate = TrailerPreloadGate()
        var cancelled = false
        assertTrue(gate.beginWarm("url", Any()) { cancelled = true })
        gate.beginPlayback("url")
        gate.beginPlayback("url")
        assertTrue(cancelled)
        assertFalse(gate.beginWarm("url", Any()) {})
        gate.endPlayback("url")
        assertFalse(gate.beginWarm("url", Any()) {})
        gate.endPlayback("url")
        assertTrue(gate.beginWarm("url", Any()) {})
    }

    @Test fun `duplicate warming is suppressed without blocking other trailers`() {
        val gate = TrailerPreloadGate()
        val owner = Any()
        assertTrue(gate.beginWarm("one", owner) {})
        assertFalse(gate.beginWarm("one", Any()) {})
        assertTrue(gate.beginWarm("two", Any()) {})
        gate.endWarm("one", owner)
        assertTrue(gate.beginWarm("one", Any()) {})
    }

    @Test fun `old cancelled writer cannot release a replacement writer`() {
        val gate = TrailerPreloadGate()
        val old = Any()
        assertTrue(gate.beginWarm("url", old) {})
        gate.beginPlayback("url")
        gate.endPlayback("url")
        assertTrue(gate.beginWarm("url", Any()) {})
        gate.endWarm("url", old)
        assertFalse(gate.beginWarm("url", Any()) {})
    }
}
