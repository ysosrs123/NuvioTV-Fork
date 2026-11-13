package com.nuvio.tv.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkValidationTest {
    private val up = NetworkValidation.State(known = true, validated = true)

    @Test
    fun `a validated network after a drop keeps the drop it came back from`() {
        val down = NetworkValidation.next(up, validated = false, nowMs = 1_000L)
        val back = NetworkValidation.next(down, validated = true, nowMs = 1_100L)
        assertEquals(1, back.dropId)
        assertEquals(1_000L, back.droppedAtMs)
        assertTrue(back.validated)
    }

    @Test
    fun `repeated down reports belong to one drop`() {
        val down = NetworkValidation.next(up, validated = false, nowMs = 1_000L)
        val stillDown = NetworkValidation.next(down, validated = false, nowMs = 4_000L)
        assertEquals(down, stillDown)
    }

    @Test
    fun `each new loss starts a new drop`() {
        var state = up
        state = NetworkValidation.next(state, validated = false, nowMs = 1_000L)
        state = NetworkValidation.next(state, validated = true, nowMs = 2_000L)
        state = NetworkValidation.next(state, validated = false, nowMs = 9_000L)
        assertEquals(2, state.dropId)
        assertEquals(9_000L, state.droppedAtMs)
        assertFalse(state.validated)
    }

    @Test
    fun `validated reports without a drop change nothing but the known flag`() {
        val first = NetworkValidation.next(NetworkValidation.State(), validated = true, nowMs = 5L)
        assertTrue(first.known)
        assertEquals(0, first.dropId)
        assertEquals(first, NetworkValidation.next(first, validated = true, nowMs = 9L))
    }
}
