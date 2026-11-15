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

    @Test
    fun `a PIN set to Every time is asked at start, the relaxed choices are not`() {
        assertEquals(true, ProfileStartGate.asksAtStart(true, null))
        assertEquals(true, ProfileStartGate.asksAtStart(true, PinAsk.EVERY_TIME))
        assertEquals(false, ProfileStartGate.asksAtStart(true, PinAsk.NOT_AT_START))
        assertEquals(false, ProfileStartGate.asksAtStart(true, PinAsk.LIMITS_ONLY))
        assertEquals(false, ProfileStartGate.asksAtStart(false, PinAsk.EVERY_TIME))
        assertNull(ProfileStartGate.asksAtStart(null, PinAsk.NOT_AT_START))
    }

    @Test
    fun `remembered profile with a PIN not asked at start opens by itself`() {
        val shown = ProfileStartGate.asksAtStart(true, PinAsk.NOT_AT_START)
        assertTrue(ProfileStartGate.canAutoSelect(true, true, shownHasPin = shown, savedHasPin = shown))
        assertFalse(ProfileStartGate.canAutoSelect(false, true, shownHasPin = shown, savedHasPin = shown))
    }

    @Test
    fun `picking a profile asks unless its PIN is only for the kids limits`() {
        assertTrue(PinAsk.EVERY_TIME.toOpen(true))
        assertTrue(PinAsk.NOT_AT_START.toOpen(true))
        assertFalse(PinAsk.LIMITS_ONLY.toOpen(true))
        assertFalse(PinAsk.EVERY_TIME.toOpen(false))
    }

    @Test
    fun `only the main profile can keep its PIN for the kids limits alone`() {
        assertEquals(PinAsk.entries, PinAsk.choicesFor(isPrimary = true))
        assertEquals(listOf(PinAsk.EVERY_TIME, PinAsk.NOT_AT_START), PinAsk.choicesFor(isPrimary = false))
        assertEquals(PinAsk.EVERY_TIME, PinAsk.fromCode(null))
        assertEquals(PinAsk.LIMITS_ONLY, PinAsk.fromCode("limits"))
    }

    @Test
    fun `one profile with a PIN not asked at start still shows the list when Remember last profile is off`() {
        assertTrue(ProfileStartGate.showPicker(false, 1, hasPin = true, pickerRequested = false))
        assertFalse(ProfileStartGate.showPicker(false, 1, hasPin = false, pickerRequested = false))
        assertTrue(ProfileStartGate.showPicker(false, 2, hasPin = false, pickerRequested = false))
        assertTrue(ProfileStartGate.showPicker(false, 1, hasPin = false, pickerRequested = true))
        assertFalse(ProfileStartGate.showPicker(true, 3, hasPin = true, pickerRequested = true))
    }

    @Test
    fun `a relaxed choice on a profile that lost its PIN is dropped`() {
        val asks = mapOf(1 to PinAsk.LIMITS_ONLY, 2 to PinAsk.NOT_AT_START, 3 to PinAsk.NOT_AT_START, 4 to PinAsk.EVERY_TIME)
        assertEquals(setOf(2, 3), PinAsk.withoutPin(asks, mapOf(1 to true, 2 to false, 4 to false)))
        assertTrue(PinAsk.withoutPin(asks, mapOf(1 to true, 2 to true, 3 to true)).isEmpty())
    }
}
