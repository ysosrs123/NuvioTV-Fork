package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {

    private fun doc(
        key: Int,
        title: String,
        explanation: String = "",
        keywords: String = "",
        path: String = "",
        altTitle: String = "",
        altExplanation: String = ""
    ) = SettingsSearchDoc(
        key = key,
        title = normalizeSettingsSearchText(title),
        altTitle = normalizeSettingsSearchText(altTitle),
        explanation = normalizeSettingsSearchText(explanation),
        altExplanation = normalizeSettingsSearchText(altExplanation),
        keywords = normalizeSettingsSearchText(keywords),
        path = normalizeSettingsSearchText(path)
    )

    private fun keys(docs: List<SettingsSearchDoc>, query: String) = searchSettings(docs, query).map { it.key }

    @Test
    fun `names come before keywords, explanations and places`() {
        val docs = listOf(
            doc(1, "Calendar reminders", path = "Night owls"),
            doc(2, "Corner message", explanation = "Shows the night before"),
            doc(3, "Volume", keywords = "night"),
            doc(4, "Late night viewing"),
            doc(5, "Night mode")
        )
        assertEquals(listOf(5, 4, 3, 2, 1), keys(docs, "night"))
    }

    @Test
    fun `every word has to be found`() {
        val docs = listOf(doc(1, "Subtitle size"), doc(2, "Subtitle font"))
        assertEquals(listOf(1), keys(docs, "size subtitle"))
        assertEquals(emptyList<Int>(), keys(docs, "subtitle colour"))
    }

    @Test
    fun `a blank search finds nothing`() {
        assertEquals(emptyList<Int>(), keys(listOf(doc(1, "Night mode")), "   "))
    }

    @Test
    fun `accents and case do not matter`() {
        assertEquals(listOf(1), keys(listOf(doc(1, "Sous-titres préférés")), "PREFERES"))
        assertEquals(listOf(1), keys(listOf(doc(1, "Größe")), "grosse"))
        assertEquals(listOf(1), keys(listOf(doc(1, "Altyazı dili")), "altyazi"))
    }

    @Test
    fun `arabic letter forms are treated as one`() {
        assertEquals(listOf(1), keys(listOf(doc(1, "الإعدادات المتقدمة")), "الاعدادات"))
        assertEquals(listOf(1), keys(listOf(doc(1, "الترجمة")), "الترجمه"))
    }

    @Test
    fun `languages without spaces match inside the text`() {
        assertEquals(listOf(1), keys(listOf(doc(1, "字幕の設定")), "設定"))
    }

    @Test
    fun `japanese voiced marks stay different`() {
        assertEquals(emptyList<Int>(), keys(listOf(doc(1, "テスト")), "デスト"))
    }

    @Test
    fun `english still finds a translated setting`() {
        val docs = listOf(doc(1, "Nachtmodus", altTitle = "Night mode", altExplanation = "Lifts quiet moments"))
        assertEquals(listOf(1), keys(docs, "night"))
        assertEquals(listOf(1), keys(docs, "quiet"))
    }

    @Test
    fun `format placeholders are not searchable`() {
        assertEquals(emptyList<Int>(), keys(listOf(doc(1, "%1\$s selected")), "s selected x"))
        assertEquals("selected", normalizeSettingsSearchText("%1\$s selected"))
    }

    @Test
    fun `modes decide what can be reached`() {
        val entry = SettingsSearchEntry(0, 0, SettingsCategory.PLAYBACK, modes = SettingsSearchModes.ADVANCED)
        assertFalse(entry.availableIn(isEssentialMode = true))
        assertTrue(entry.availableIn(isEssentialMode = false))
        val essential = entry.copy(modes = SettingsSearchModes.ESSENTIAL)
        assertTrue(essential.availableIn(isEssentialMode = true))
        assertFalse(essential.availableIn(isEssentialMode = false))
    }

    @Test
    fun `each interface finds only its own rows`() {
        val both = SettingsSearchEntry(0, 0, SettingsCategory.APPEARANCE)
        val original = both.copy(look = SettingsSearchLook.ORIGINAL)
        val v2 = both.copy(look = SettingsSearchLook.V2)
        assertTrue(both.shownIn(isV2 = true) && both.shownIn(isV2 = false))
        assertTrue(original.shownIn(isV2 = false))
        assertFalse(original.shownIn(isV2 = true))
        assertTrue(v2.shownIn(isV2 = true))
        assertFalse(v2.shownIn(isV2 = false))
    }

    @Test
    fun `remote down leaves the search box for the results`() {
        assertTrue(leavesSearchFieldDown(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN, hasResults = true))
    }

    @Test
    fun `remote down stays in the search box without results`() {
        assertFalse(leavesSearchFieldDown(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN, hasResults = false))
    }

    @Test
    fun `only the press of down moves, not the release or other keys`() {
        assertFalse(leavesSearchFieldDown(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP, hasResults = true))
        assertFalse(leavesSearchFieldDown(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN, hasResults = true))
        assertFalse(leavesSearchFieldDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, hasResults = true))
        assertFalse(leavesSearchFieldDown(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, hasResults = true))
    }

    @Test
    fun `a page leaves focus alone while a search result is landing`() {
        val jump = SettingsJumpState()
        assertFalse(jump.busy)
        jump.start(SettingsSearchEntry(0, 0, SettingsCategory.APPEARANCE), "App language")
        assertTrue(jump.busy)
        jump.land()
        assertTrue(jump.busy)
        jump.clearLanded()
        assertFalse(jump.busy)
    }

    @Test
    fun `a jump that gives up lets the page take focus again`() {
        val jump = SettingsJumpState()
        jump.start(SettingsSearchEntry(0, 0, SettingsCategory.APPEARANCE), "App language")
        jump.giveUp()
        assertFalse(jump.busy)
    }

    private val settingsFolder = File(
        listOf(File("src/main"), File("app/src/main")).first { it.isDirectory },
        "java/com/nuvio/tv/ui/screens/settings"
    )

    private val pagesWithoutSearch = setOf(
        "AppIconPicker.kt", "DebugSettingsScreen.kt", "DeviceAssessmentScreen.kt", "FloppyAccountDialog.kt",
        "FormatCheckScreen.kt", "MdbListLibraryListsDialog.kt", "MediaServerSettingsScreen.kt",
        "MediaServersSettingsScreen.kt", "PlexSignInDialog.kt", "SettingsDesignSystem.kt",
        "SettingsSectionComponents.kt", "SiloSignInDialog.kt", "TrackingProviderDialogs.kt"
    )

    private val rowsWithoutSearch = setOf("experience_mode_switch_to_advanced")

    @Test
    fun `every settings row with a fixed name can be found`() {
        val index = File(settingsFolder, "SettingsSearchIndex.kt").readText()
        val row = Regex("""(?:Settings(?:Toggle|Action)Row|V2ChoiceRow)\(\s*title = stringResource\(R\.string\.(\w+)\)\s*,""")
        val missing = settingsFolder.listFiles().orEmpty()
            .filter { it.extension == "kt" && it.name !in pagesWithoutSearch }
            .flatMap { file -> row.findAll(file.readText()).map { it.groupValues[1] to file.name }.toList() }
            .filter { (name, _) -> name !in rowsWithoutSearch && "R.string.$name," !in index }
        assertEquals("Add these rows to SettingsSearchIndex.kt", emptyList<Pair<String, String>>(), missing)
    }

    @Test
    fun `every entry still points at a row`() {
        val index = File(settingsFolder, "SettingsSearchIndex.kt").readText()
        val pages = settingsFolder.listFiles().orEmpty()
            .filter { it.extension == "kt" && it.name != "SettingsSearchIndex.kt" }
            .joinToString("\n") { it.readText() }
        val gone = Regex("""SettingsSearchEntry\(R\.string\.(\w+),""").findAll(index)
            .map { it.groupValues[1] }
            .filter { "R.string.$it" !in pages }
            .toList()
        assertEquals(emptyList<String>(), gone)
    }
}
