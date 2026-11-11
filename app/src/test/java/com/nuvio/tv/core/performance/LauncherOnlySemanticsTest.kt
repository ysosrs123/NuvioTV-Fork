package com.nuvio.tv.core.performance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherOnlySemanticsTest {

    @Test
    fun `a launcher service alone qualifies`() {
        assertTrue(onlyLauncherServices(listOf("com.spocky.projengmenu"), touchExploration = false))
    }

    @Test
    fun `any other service keeps the tree`() {
        assertFalse(onlyLauncherServices(emptyList(), touchExploration = false))
        assertFalse(
            onlyLauncherServices(
                listOf("com.spocky.projengmenu", "com.google.android.marvin.talkback"),
                touchExploration = false
            )
        )
        assertFalse(onlyLauncherServices(listOf(""), touchExploration = false))
        assertFalse(onlyLauncherServices(listOf("com.spocky.projengmenu"), touchExploration = true))
    }
}
