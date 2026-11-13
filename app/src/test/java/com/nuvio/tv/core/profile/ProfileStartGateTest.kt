package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileStartGateTest {

    @Test
    fun `PIN state is unknown until the saved states are read`() {
        assertNull(ProfileStartGate.hasPin(signedOut = false, savedPinStates = null, profileId = 2))
    }

    @Test
    fun `saved states answer for each profile`() {
        val saved = mapOf(1 to false, 2 to true)
        assertEquals(false, ProfileStartGate.hasPin(false, saved, 1))
        assertEquals(true, ProfileStartGate.hasPin(false, saved, 2))
        assertEquals(false, ProfileStartGate.hasPin(false, saved, 3))
        assertEquals(false, ProfileStartGate.hasPin(false, emptyMap(), 1))
    }

    @Test
    fun `a signed out box has no PIN and does not wait`() {
        assertEquals(false, ProfileStartGate.hasPin(signedOut = true, savedPinStates = null, profileId = 2))
        assertEquals(false, ProfileStartGate.hasPin(signedOut = true, savedPinStates = mapOf(2 to true), profileId = 2))
    }

    @Test
    fun `remembered profile without a PIN opens by itself`() {
        assertTrue(ProfileStartGate.canAutoSelect(true, true, shownHasPin = false, savedHasPin = false))
    }

    @Test
    fun `remembered profile with a PIN never opens by itself`() {
        assertFalse(ProfileStartGate.canAutoSelect(true, true, shownHasPin = true, savedHasPin = true))
    }

    @Test
    fun `nothing opens by itself while the PIN state is unknown`() {
        assertFalse(ProfileStartGate.canAutoSelect(true, true, shownHasPin = null, savedHasPin = null))
        assertFalse(ProfileStartGate.canAutoSelect(true, true, shownHasPin = false, savedHasPin = null))
        assertFalse(ProfileStartGate.canAutoSelect(true, true, shownHasPin = null, savedHasPin = false))
    }

    @Test
    fun `a PIN on the profile saved on disk wins over the one shown`() {
        assertFalse(ProfileStartGate.canAutoSelect(true, true, shownHasPin = false, savedHasPin = true))
        assertFalse(ProfileStartGate.canAutoSelect(true, true, shownHasPin = true, savedHasPin = false))
    }

    @Test
    fun `remember off or no profile ever chosen keeps the profile page`() {
        assertFalse(ProfileStartGate.canAutoSelect(false, true, shownHasPin = false, savedHasPin = false))
        assertFalse(ProfileStartGate.canAutoSelect(true, false, shownHasPin = false, savedHasPin = false))
    }
}
