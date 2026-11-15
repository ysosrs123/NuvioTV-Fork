package com.nuvio.tv

import com.nuvio.tv.data.local.resolveExperienceMode
import com.nuvio.tv.domain.model.ExperienceMode
import com.nuvio.tv.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRootRoutesTest {
    @Test fun `a hidden Calendar is not a menu page, so Back from it goes Home`() {
        assertTrue(Screen.Calendar.route in appRootRoutes(discoverInSidebar = false, calendarInMenu = true, scoutInMenu = false))
        assertFalse(Screen.Calendar.route in appRootRoutes(discoverInSidebar = false, calendarInMenu = false, scoutInMenu = false))
    }

    @Test fun `the other menu pages stay root pages`() {
        assertEquals(
            setOf(Screen.Home.route, Screen.Search.route, Screen.Library.route, Screen.Settings.route, Screen.Discover.route),
            appRootRoutes(discoverInSidebar = true, calendarInMenu = false, scoutInMenu = false)
        )
        assertFalse(Screen.Discover.route in appRootRoutes(discoverInSidebar = false, calendarInMenu = true, scoutInMenu = false))
    }

    @Test fun `the Search button opens search outside the player and before sign-in`() {
        assertTrue(searchButtonOpensSearch(Screen.Home.route, playbackUiActive = false))
        assertTrue(searchButtonOpensSearch(Screen.Library.route, playbackUiActive = false))
        assertTrue(searchButtonOpensSearch(Screen.Detail.route, playbackUiActive = false))
        assertFalse(searchButtonOpensSearch(Screen.Player.route, playbackUiActive = true))
        assertFalse(searchButtonOpensSearch(Screen.Detail.route, playbackUiActive = true))
        assertFalse(searchButtonOpensSearch(Screen.ProfileSelection.route, playbackUiActive = false))
        assertFalse(searchButtonOpensSearch(Screen.AuthQrSignIn.route, playbackUiActive = false))
        assertFalse(searchButtonOpensSearch(null, playbackUiActive = false))
    }

    @Test fun `a fresh profile that never picked a layout follows launch extras and links`() {
        val freshMode = resolveExperienceMode(storedMode = null, essentialChosen = false)
        assertTrue(launchTargetsReady(freshMode, hasChosenLayout = false))
        assertEquals(ExperienceMode.ADVANCED, resolveStartExperienceMode(freshMode, hasChosenLayout = false))
    }

    @Test fun `older profiles and Essential profiles follow them too`() {
        assertTrue(launchTargetsReady(ExperienceMode.ADVANCED, hasChosenLayout = true))
        assertTrue(launchTargetsReady(ExperienceMode.ESSENTIAL, hasChosenLayout = false))
        assertTrue(launchTargetsReady(null, hasChosenLayout = true))
    }

    @Test fun `with no experience mode and no layout the first-run choice still comes first`() {
        assertNull(resolveStartExperienceMode(null, hasChosenLayout = false))
        assertFalse(launchTargetsReady(null, hasChosenLayout = false))
    }
}
