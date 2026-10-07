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
                SettingsCategory.ABOUT,
                SettingsCategory.DEBUG
            ),
            categories
        )
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
        assertEquals(SettingsCategory.APPEARANCE, categories.first())
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

    @Test
    fun `live tv is hidden unless the feature is enabled`() {
        allCombinations().forEach { categories ->
            assertFalse(SettingsCategory.LIVE_TV in categories)
        }
    }

    @Test
    fun `live tv follows playback in the watch group`() {
        val categories = visibleSettingsCategories(
            isPrimaryProfile = true,
            isEssentialMode = true,
            isDebugBuild = false,
            isLiveTvEnabled = true
        )
        assertEquals(categories.indexOf(SettingsCategory.PLAYBACK) + 1, categories.indexOf(SettingsCategory.LIVE_TV))
        assertEquals(SettingsRailGroup.WATCH, SettingsCategory.LIVE_TV.group)
        assertFalse(categories.startsNewGroup(categories.indexOf(SettingsCategory.LIVE_TV)))
    }

    @Test
    fun `category request is consumed once`() {
        SettingsCategoryRequest.open(SettingsCategory.LIVE_TV)
        SettingsCategoryRequest.consume(SettingsCategory.PLAYBACK)
        assertEquals(SettingsCategory.LIVE_TV, SettingsCategoryRequest.category.value)
        SettingsCategoryRequest.consume(SettingsCategory.LIVE_TV)
        assertEquals(null, SettingsCategoryRequest.category.value)
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
