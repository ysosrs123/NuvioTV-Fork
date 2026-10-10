package com.nuvio.tv.core.iptv

object LivePreferenceKeys {
    const val MENU_ORDER = "menu-order"
    const val MENU_HIDDEN = "menu-hidden"

    fun source(profileId: Int, sourceId: String, name: String) = "$profileId:$sourceId:$name"

    fun ofProfile(keys: Collection<String>, profileId: Int): List<String> = keys.filter { it.startsWith("$profileId:") }

    fun ofSource(keys: Collection<String>, profileId: Int, sourceId: String): List<String> = keys.filter { it.startsWith("$profileId:$sourceId:") }

    fun hiddenOfProfile(keys: Collection<String>, profileId: Int): List<String> = ofProfile(keys, profileId).filter { it.endsWith(":hidden") }

    fun ofAllProfiles(keys: Collection<String>): List<String> = keys.filter { key -> key.substringBefore(':', "").let { it.isNotEmpty() && it.all(Char::isDigit) } }
}
