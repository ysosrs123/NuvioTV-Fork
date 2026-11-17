package com.nuvio.tv.core.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherOnlySemanticsTest {
    private val projectivy = "com.spocky.projengmenu"
    private val talkBack = "com.google.android.marvin.talkback"

    @Test
    fun `a launcher service alone qualifies`() {
        assertTrue(onlyLauncherServices(listOf(projectivy), touchExploration = false))
    }

    @Test
    fun `any other service keeps the tree`() {
        assertFalse(onlyLauncherServices(emptyList(), touchExploration = false))
        assertFalse(onlyLauncherServices(listOf(projectivy, talkBack), touchExploration = false))
        assertFalse(onlyLauncherServices(listOf(""), touchExploration = false))
        assertFalse(onlyLauncherServices(listOf(projectivy), touchExploration = true))
    }

    @Test
    fun `a remapper not on the list keeps the tree`() {
        assertFalse(onlyLauncherServices(listOf(projectivy, "flar2.homebutton"), touchExploration = false))
    }

    @Test
    fun `the setting turns it off`() {
        assertTrue(launcherOnly(settingOn = true, accessibilityOn = true, servicePackages = { listOf(projectivy) }, touchExploration = false))
        assertFalse(launcherOnly(settingOn = false, accessibilityOn = true, servicePackages = { listOf(projectivy) }, touchExploration = false))
    }

    @Test
    fun `nothing changes while accessibility is off`() {
        var asked = false
        assertFalse(launcherOnly(settingOn = true, accessibilityOn = false, servicePackages = { asked = true; listOf(projectivy) }, touchExploration = false))
        assertFalse(asked)
    }

    @Test
    fun `a screen reader switched on brings the screen back`() {
        val services = mutableListOf(projectivy)
        assertTrue(launcherOnly(true, true, { services }, touchExploration = false))
        services += talkBack
        assertFalse(launcherOnly(true, true, { services }, touchExploration = false))
    }

    @Test
    fun `touch exploration alone brings the screen back`() {
        assertFalse(launcherOnly(true, true, { listOf(projectivy) }, touchExploration = true))
    }

    @Test
    fun `the system list is read as packages`() {
        assertEquals(
            listOf(projectivy, talkBack),
            enabledServicePackages("$projectivy/$projectivy.services.ProjectivyAccessibilityService:$talkBack/.TalkBackService")
        )
        assertEquals(emptyList<String>(), enabledServicePackages(null))
        assertEquals(emptyList<String>(), enabledServicePackages(""))
    }

    @Test
    fun `a service switched on but not running yet still counts`() {
        val running = listOf(projectivy)
        val listed = enabledServicePackages("$projectivy/.A:$talkBack/.TalkBackService")
        assertFalse(launcherOnly(true, true, { running + listed }, touchExploration = false))
    }
}
