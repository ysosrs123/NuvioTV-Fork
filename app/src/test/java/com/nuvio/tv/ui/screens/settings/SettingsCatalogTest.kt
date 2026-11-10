package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {

    @Test
    fun `primary profile in advanced debug build sees every category in group order`() {
        val categories = visibleSettingsCategories(
            isPrimaryProfile = true,
            isEssentialMode = false,
            isDebugBuild = true
        )

        assertEquals(
            listOf(
                SettingsCategory.ACCOUNT,
                SettingsCategory.PROFILES,
                SettingsCategory.APPEARANCE,
                SettingsCategory.LAYOUT,
                SettingsCategory.CONTENT_DISCOVERY,
                SettingsCategory.PLAYBACK,
                SettingsCategory.INTEGRATION,
                SettingsCategory.TRACKING,
                SettingsCategory.ADVANCED,
                SettingsCategory.BACKUP,
                SettingsCategory.ABOUT,
                SettingsCategory.DEBUG
            ),
            categories - SettingsCategory.SCOUT - SettingsCategory.SEARCH
        )
        assertEquals(com.nuvio.tv.domain.model.ScoutFeature.available, SettingsCategory.SCOUT in categories)
    }

    @Test
    fun `secondary profile does not see account or profiles`() {
        val categories = visibleSettingsCategories(
            isPrimaryProfile = false,
            isEssentialMode = false,
            isDebugBuild = false
        )

        assertFalse(SettingsCategory.ACCOUNT in categories)
        assertFalse(SettingsCategory.PROFILES in categories)
        assertEquals(SettingsCategory.SEARCH, categories.first())
        assertEquals(SettingsCategory.APPEARANCE, categories[1])
    }

    @Test
    fun `debug is hidden in release builds and in essential mode`() {
        assertFalse(SettingsCategory.DEBUG in visibleSettingsCategories(true, isEssentialMode = false, isDebugBuild = false))
        assertFalse(SettingsCategory.DEBUG in visibleSettingsCategories(true, isEssentialMode = true, isDebugBuild = true))
    }

    @Test
    fun `experience never appears in the rail`() {
        allCombinations().forEach { categories ->
            assertFalse(SettingsCategory.EXPERIENCE in categories)
        }
    }

    @Test
    fun `report a problem sits next to about, and only when reports are available`() {
        val shown = visibleSettingsCategories(true, isEssentialMode = true, isDebugBuild = false, reportsAvailable = true)
        assertEquals(shown.indexOf(SettingsCategory.ABOUT) - 1, shown.indexOf(SettingsCategory.REPORT))
        assertFalse(SettingsCategory.REPORT in visibleSettingsCategories(true, isEssentialMode = true, isDebugBuild = false))
    }

    @Test
    fun `about is the last regular category`() {
        allCombinations().forEach { categories ->
            val withoutDebug = categories - SettingsCategory.DEBUG
            assertEquals(SettingsCategory.ABOUT, withoutDebug.last())
        }
    }

    @Test
    fun `each group is contiguous in every configuration`() {
        allCombinations().forEach { categories ->
            val groupRuns = categories.map { it.group }.fold(mutableListOf<SettingsRailGroup>()) { runs, group ->
                if (runs.lastOrNull() != group) runs.add(group)
                runs
            }
            assertEquals(groupRuns.distinct(), groupRuns)
        }
    }

    @Test
    fun `dividers are placed only where the group changes`() {
        val categories = visibleSettingsCategories(
            isPrimaryProfile = true,
            isEssentialMode = false,
            isDebugBuild = true
        )
        val dividerBefore = categories.indices.filter { categories.startsNewGroup(it) }.map { categories[it] }

        assertEquals(
            listOf(
                SettingsCategory.ACCOUNT,
                SettingsCategory.APPEARANCE,
                SettingsCategory.CONTENT_DISCOVERY,
                SettingsCategory.INTEGRATION,
                SettingsCategory.ADVANCED
            ),
            dividerBefore
        )
        assertFalse(categories.startsNewGroup(0))
    }

    @Test
    fun `secondary profile rail starts without a divider`() {
        val categories = visibleSettingsCategories(
            isPrimaryProfile = false,
            isEssentialMode = true,
            isDebugBuild = false
        )
        assertFalse(categories.startsNewGroup(0))
        assertTrue(categories.startsNewGroup(categories.indexOf(SettingsCategory.CONTENT_DISCOVERY)))
    }

    private fun allCombinations(): List<List<SettingsCategory>> =
        listOf(true, false).flatMap { primary ->
            listOf(true, false).flatMap { essential ->
                listOf(true, false).map { debug ->
                    visibleSettingsCategories(primary, essential, debug)
                }
            }
        }
}
