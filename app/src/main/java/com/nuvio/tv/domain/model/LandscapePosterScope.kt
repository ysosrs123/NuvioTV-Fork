package com.nuvio.tv.domain.model

enum class LandscapePosterScope {
    OFF,
    HOME_ONLY,
    EVERYWHERE;

    val onHome: Boolean get() = this != OFF
    val onAllScreens: Boolean get() = this == EVERYWHERE

    companion object {
        fun resolve(stored: String?, legacyEnabled: Boolean?): LandscapePosterScope {
            if (legacyEnabled != true) return OFF
            return entries.firstOrNull { it.name == stored }?.takeIf { it.onHome } ?: HOME_ONLY
        }
    }
}
