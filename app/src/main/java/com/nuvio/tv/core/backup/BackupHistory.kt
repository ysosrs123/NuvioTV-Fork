package com.nuvio.tv.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class BackupHistoryCounts(val saved: Int, val watched: Int, val inProgress: Int)

/**
 * Library and watch history as a backup keeps them. Add-on addresses and artwork links from anywhere but the
 * public picture hosts are left out, because both can carry a personal key and this part is not sealed.
 */
internal object BackupHistory {
    const val LIBRARY = "library_preferences"
    const val PROGRESS_STATE = "watch_progress_preferences"
    const val PROGRESS_RECENT = "watch_progress_recent_preferences"
    const val PROGRESS_ARCHIVE = "watch_progress_archive_preferences"
    const val WATCHED = "watched_items_preferences"

    /** What was saved or watched while signed out. A later sign-in adds exactly these to the account. */
    const val PENDING = "watch_state_pending_mutations"

    val stores = listOf(LIBRARY, PROGRESS_STATE, PROGRESS_RECENT, PROGRESS_ARCHIVE, WATCHED, PENDING)

    private const val LIBRARY_ITEMS = "library_items"
    private const val WATCHED_ITEMS = "watched_items"
    private const val PROGRESS_MAP = "watch_progress_map"

    private val addonAddressFields = setOf("addonBaseUrl", "addon_base_url")
    private val keptLinkFields = setOf("trackingSourceUrl", "tracking_source_url")
    private val publicPictureHosts = setOf(
        "image.tmdb.org",
        "images.metahub.space",
        "live.metahub.space",
        "episodes.metahub.space",
        "m.media-amazon.com",
        "artworks.thetvdb.com",
        "static.tvmaze.com",
        "cdn.myanimelist.net",
        "media.kitsu.app",
        "media.kitsu.io",
        "s4.anilist.co",
        "walter.trakt.tv",
        "walter-r2.trakt.tv",
        "media.trakt.tv",
        "assets.fanart.tv",
        "simkl.in"
    )

    private val json = Json

    fun scrub(values: Map<String, BackupValue>): Map<String, BackupValue> = values.mapValues { (_, value) ->
        when (value) {
            is BackupValue.Text -> BackupValue.Text(scrubText(value.value))
            is BackupValue.Texts -> BackupValue.Texts(value.value.map(::scrubText).toSet())
            else -> value
        }
    }

    fun counts(entries: List<BackupEntry>): BackupHistoryCounts {
        var saved = 0
        var watched = 0
        var inProgress = 0
        entries.forEach { entry ->
            when (entry.store) {
                LIBRARY -> saved += (entry.values[LIBRARY_ITEMS] as? BackupValue.Texts)?.value?.size ?: 0
                WATCHED -> watched += (entry.values[WATCHED_ITEMS] as? BackupValue.Texts)?.value?.size ?: 0
                PROGRESS_RECENT, PROGRESS_ARCHIVE -> {
                    val text = (entry.values[PROGRESS_MAP] as? BackupValue.Text)?.value ?: return@forEach
                    inProgress += (runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject)?.size ?: 0
                }
            }
        }
        return BackupHistoryCounts(saved, watched, inProgress)
    }

    /** History stores the backup has nothing for, per restored profile: left as they are they would mix in old entries. */
    fun storesToEmpty(entries: List<BackupEntry>): List<Pair<String, Int>> {
        val present = entries.mapNotNull { entry -> entry.profileId?.let { entry.store to it } }.toSet()
        return present.map { it.second }.distinct().sorted().flatMap { profileId ->
            stores.filter { (it to profileId) !in present }.map { it to profileId }
        }
    }

    private fun scrubText(text: String): String {
        if ("http" !in text && addonAddressFields.none { it in text }) return text
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
        if (parsed !is JsonObject && parsed !is JsonArray) return text
        return scrubElement(parsed).toString()
    }

    private fun scrubElement(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.filterNot { (name, value) -> name in addonAddressFields || (name !in keptLinkFields && isPrivateLink(value)) }
                .mapValues { (name, value) -> if (name in keptLinkFields) value else scrubElement(value) }
        )
        is JsonArray -> JsonArray(element.filterNot(::isPrivateLink).map(::scrubElement))
        is JsonPrimitive -> scrubNested(element)
        else -> element
    }

    /** Older entries hold a whole item as text inside the map. */
    private fun scrubNested(value: JsonPrimitive): JsonElement {
        if (!value.isString) return value
        val text = value.content
        if (!text.startsWith("{")) return value
        return JsonPrimitive(scrubText(text))
    }

    private fun isPrivateLink(value: JsonElement): Boolean {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return false
        if (!text.startsWith("http://", ignoreCase = true) && !text.startsWith("https://", ignoreCase = true)) return false
        val host = text.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@')
            .substringBefore(':').lowercase()
        return host !in publicPictureHosts
    }
}
