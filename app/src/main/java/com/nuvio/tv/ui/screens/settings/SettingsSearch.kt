package com.nuvio.tv.ui.screens.settings

import androidx.annotation.StringRes
import com.nuvio.tv.R
import java.text.Normalizer
import java.util.Locale

internal enum class SettingsSearchModes { BOTH, ADVANCED, ESSENTIAL }

/** Which interface shows the row: Original Nuvio, Nuvio V2 or both. */
internal enum class SettingsSearchLook { BOTH, ORIGINAL, V2 }

/** One setting the search can find. A title of 0 means the row shows [literalTitle]. */
internal data class SettingsSearchEntry(
    @param:StringRes val title: Int,
    @param:StringRes val explanation: Int,
    val category: SettingsCategory,
    val playback: PlaybackSection? = null,
    val layout: LayoutSection? = null,
    val integration: IntegrationSettingsSection? = null,
    val qualityRules: Boolean = false,
    val modes: SettingsSearchModes = SettingsSearchModes.BOTH,
    val literalTitle: String? = null,
    val keywords: List<String> = emptyList(),
    val look: SettingsSearchLook = SettingsSearchLook.BOTH
)

internal fun SettingsSearchEntry.availableIn(isEssentialMode: Boolean): Boolean = when (modes) {
    SettingsSearchModes.BOTH -> true
    SettingsSearchModes.ADVANCED -> !isEssentialMode
    SettingsSearchModes.ESSENTIAL -> isEssentialMode
}

internal fun SettingsSearchEntry.shownIn(isV2: Boolean): Boolean = when (look) {
    SettingsSearchLook.BOTH -> true
    SettingsSearchLook.ORIGINAL -> !isV2
    SettingsSearchLook.V2 -> isV2
}

/** Text ready for matching, in the app's language plus English as a second chance. */
internal data class SettingsSearchDoc(
    val key: Int,
    val title: String,
    val altTitle: String,
    val explanation: String,
    val altExplanation: String,
    val keywords: String,
    val path: String
)

private val MARKS = Regex("[\\p{Mn}&&[^\\u3099\\u309A]]")
private val PLACEHOLDERS = Regex("%(\\d+\\$)?[sd]")
private val INVISIBLE = Regex("[\\u0640\\u200B-\\u200F\\u2060\\uFEFF]")
private val SPACES = Regex("\\s+")

internal fun normalizeSettingsSearchText(text: String): String {
    val plain = PLACEHOLDERS.replace(text, " ")
    return MARKS.replace(Normalizer.normalize(plain, Normalizer.Form.NFD), "")
        .lowercase(Locale.ROOT)
        .replace("ı", "i")
        .replace("ß", "ss")
        .replace('ة', 'ه')
        .replace('ى', 'ي')
        .let { INVISIBLE.replace(it, "") }
        .let { SPACES.replace(it, " ") }
        .trim()
}

internal fun settingsSearchTokens(query: String): List<String> =
    normalizeSettingsSearchText(query).split(' ').filter { it.isNotEmpty() }

private fun rankInTitle(title: String, token: String): Int? {
    val at = title.indexOf(token)
    if (at < 0) return null
    if (at == 0) return 0
    var from = at
    while (from >= 0) {
        if (!title[from - 1].isLetterOrDigit()) return 1
        from = title.indexOf(token, from + 1)
    }
    return 2
}

private fun rankToken(doc: SettingsSearchDoc, token: String): Int? {
    val inTitle = listOfNotNull(rankInTitle(doc.title, token), rankInTitle(doc.altTitle, token)).minOrNull()
    return inTitle ?: when {
        token in doc.keywords -> 3
        token in doc.explanation || token in doc.altExplanation -> 4
        token in doc.path -> 5
        else -> null
    }
}

/** Every word has to be found somewhere. Names come before keywords, explanations and places. */
internal fun searchSettings(docs: List<SettingsSearchDoc>, query: String): List<SettingsSearchDoc> {
    val tokens = settingsSearchTokens(query)
    if (tokens.isEmpty()) return emptyList()
    return docs.mapNotNull { doc ->
        val ranks = tokens.map { rankToken(doc, it) ?: return@mapNotNull null }
        doc to ranks.max()
    }.sortedWith(compareBy({ it.second }, { it.first.title.length })).map { it.first }
}

@StringRes
internal fun SettingsCategory.searchTitle(): Int = when (this) {
    SettingsCategory.SEARCH -> R.string.settings_search_rail
    SettingsCategory.ACCOUNT -> R.string.settings_account
    SettingsCategory.PROFILES -> R.string.settings_profiles
    SettingsCategory.APPEARANCE -> R.string.appearance_title
    SettingsCategory.LAYOUT -> R.string.settings_layout
    SettingsCategory.CONTENT_DISCOVERY -> R.string.settings_content_discovery
    SettingsCategory.PLAYBACK -> R.string.settings_playback
    SettingsCategory.SCOUT -> R.string.scout_title
    SettingsCategory.INTEGRATION -> R.string.settings_integration
    SettingsCategory.TRACKING -> R.string.settings_tracking_title
    SettingsCategory.ADVANCED -> R.string.settings_advanced
    SettingsCategory.BACKUP -> R.string.backup_title
    SettingsCategory.REPORT -> R.string.report_panel_title
    SettingsCategory.ABOUT -> R.string.about_title
    SettingsCategory.DEBUG -> R.string.settings_debug
    SettingsCategory.EXPERIENCE -> R.string.settings_experience
}
