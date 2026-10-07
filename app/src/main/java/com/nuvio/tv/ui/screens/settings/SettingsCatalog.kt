package com.nuvio.tv.ui.screens.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class SettingsRailGroup {
    YOU,
    LOOK,
    WATCH,
    SERVICES,
    SYSTEM
}

internal enum class SettingsCategory(val group: SettingsRailGroup) {
    ACCOUNT(SettingsRailGroup.YOU),
    PROFILES(SettingsRailGroup.YOU),
    APPEARANCE(SettingsRailGroup.LOOK),
    LAYOUT(SettingsRailGroup.LOOK),
    CONTENT_DISCOVERY(SettingsRailGroup.WATCH),
    PLAYBACK(SettingsRailGroup.WATCH),
    LIVE_TV(SettingsRailGroup.WATCH),
    INTEGRATION(SettingsRailGroup.SERVICES),
    TRACKING(SettingsRailGroup.SERVICES),
    ADVANCED(SettingsRailGroup.SYSTEM),
    ABOUT(SettingsRailGroup.SYSTEM),
    DEBUG(SettingsRailGroup.SYSTEM),
    EXPERIENCE(SettingsRailGroup.SYSTEM)
}

internal fun visibleSettingsCategories(
    isPrimaryProfile: Boolean,
    isEssentialMode: Boolean,
    isDebugBuild: Boolean,
    isLiveTvEnabled: Boolean = false
): List<SettingsCategory> = SettingsCategory.entries.filter { category ->
    when (category) {
        SettingsCategory.ACCOUNT,
        SettingsCategory.PROFILES -> isPrimaryProfile
        SettingsCategory.DEBUG -> isDebugBuild && !isEssentialMode
        SettingsCategory.LIVE_TV -> isLiveTvEnabled
        SettingsCategory.EXPERIENCE -> false
        else -> true
    }
}

internal fun List<SettingsCategory>.startsNewGroup(index: Int): Boolean =
    index > 0 && this[index - 1].group != this[index].group

internal object SettingsCategoryRequest {
    private val pending = MutableStateFlow<SettingsCategory?>(null)
    val category: StateFlow<SettingsCategory?> = pending.asStateFlow()

    fun open(category: SettingsCategory) {
        pending.value = category
    }

    fun consume(category: SettingsCategory) {
        pending.compareAndSet(category, null)
    }
}
