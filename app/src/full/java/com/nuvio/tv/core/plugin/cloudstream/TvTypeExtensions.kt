package com.nuvio.tv.core.plugin.cloudstream

import com.lagradost.cloudstream3.TvType

/** Map CloudStream TvType to NuvioTV content type string ("movie" or "tv"). */
fun TvType.toNuvioType(): String = when (this) {
    TvType.Movie, TvType.AnimeMovie, TvType.Documentary, TvType.Torrent -> "movie"
    else -> "tv"
}

/** Parse TvType from string name, case-insensitive. */
fun tvTypeFromString(value: String): TvType? = TvType.entries.firstOrNull {
    it.name.equals(value, ignoreCase = true)
}

private val ADULT_TYPE = "NSFW"
private val NON_TITLE_TYPES = setOf("Live", "Music", "Audio", "AudioBook", "Podcast", "CustomMedia")

/**
 * Whether a newly added extension starts on. A repository's status 0 means it is down; slow (2)
 * and beta (3) still work. Adult extensions need an explicit choice, and every series title would
 * otherwise be sent to them. Extensions with only live or audio types have no film or episode to
 * match, so searching them by title only adds wait.
 */
fun defaultExtensionEnabled(status: Int, tvTypes: List<String>?): Boolean {
    if (status == 0) return false
    val types = tvTypes.orEmpty()
    if (types.any { it.equals(ADULT_TYPE, ignoreCase = true) }) return false
    val onlyNonTitle = types.isNotEmpty() && types.all { type -> NON_TITLE_TYPES.any { it.equals(type, ignoreCase = true) } }
    return !onlyNonTitle
}
