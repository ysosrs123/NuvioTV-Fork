package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapePosterScopeTest {
    @Test fun `switch that was on becomes home only`() {
        assertEquals(LandscapePosterScope.HOME_ONLY, LandscapePosterScope.resolve(null, true))
    }

    @Test fun `switch that was off or never set stays off`() {
        assertEquals(LandscapePosterScope.OFF, LandscapePosterScope.resolve(null, false))
        assertEquals(LandscapePosterScope.OFF, LandscapePosterScope.resolve(null, null))
    }

    @Test fun `stored choice is used while the switch value agrees`() {
        assertEquals(LandscapePosterScope.EVERYWHERE, LandscapePosterScope.resolve("EVERYWHERE", true))
        assertEquals(LandscapePosterScope.HOME_ONLY, LandscapePosterScope.resolve("HOME_ONLY", true))
        assertEquals(LandscapePosterScope.OFF, LandscapePosterScope.resolve("OFF", false))
    }

    @Test fun `switch value changed by an install without the choice wins`() {
        assertEquals(LandscapePosterScope.OFF, LandscapePosterScope.resolve("EVERYWHERE", false))
        assertEquals(LandscapePosterScope.OFF, LandscapePosterScope.resolve("HOME_ONLY", null))
        assertEquals(LandscapePosterScope.HOME_ONLY, LandscapePosterScope.resolve("OFF", true))
    }

    @Test fun `unknown stored choice falls back to home only`() {
        assertEquals(LandscapePosterScope.HOME_ONLY, LandscapePosterScope.resolve("SOMEWHERE", true))
    }

    @Test fun `home is landscape for home only and everywhere, other screens only for everywhere`() {
        assertFalse(LandscapePosterScope.OFF.onHome)
        assertFalse(LandscapePosterScope.OFF.onAllScreens)
        assertTrue(LandscapePosterScope.HOME_ONLY.onHome)
        assertFalse(LandscapePosterScope.HOME_ONLY.onAllScreens)
        assertTrue(LandscapePosterScope.EVERYWHERE.onHome)
        assertTrue(LandscapePosterScope.EVERYWHERE.onAllScreens)
    }
}
