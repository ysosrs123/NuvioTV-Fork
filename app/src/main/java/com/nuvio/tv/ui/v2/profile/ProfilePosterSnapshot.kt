package com.nuvio.tv.ui.v2.profile

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.Reader
import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.PreferencesSerializer
import coil3.imageLoader

/** Only read existing named stores. No active-profile change, sync or repository loading. */
internal suspend fun readProfilePosters(context: Context, profileId: Int): List<String> {
    if (profileId <= 0) return emptyList()
    val posters = linkedSetOf<String>()
    posters.addAll(ProfilePosterWall.posters(context, profileId))
    // The dedicated wall snapshot already contains the intended 24 tiles on a
    // populated profile. Do not parse its whole history/library before showing it.
    val saved = cachedPosterVariants(context, posters.toList(), allowUncachedFallback = false)
    if (saved.size >= 24) return saved
    posters.addAll(readProfilePosters(context.filesDir, profileId))
    for (feature in listOf("watch_progress_recent_preferences", "watch_progress_preferences", "library_preferences")) {
        if (posters.size >= 72) break
        val name = if (profileId == 1) feature else "${feature}_p$profileId"
        val file = File(context.filesDir, "datastore/$name.preferences_pb")
        if (!file.isFile || file.length() > 8 * 1024 * 1024) continue
        runCatching {
            val source = file.inputStream().use { input -> okio.Buffer().apply { readFrom(input) } }
            val prefs = PreferencesSerializer.readFrom(source)
            if (feature == "library_preferences") {
                prefs[stringSetPreferencesKey("library_items")].orEmpty().asSequence().take(192).forEach {
                    posters.addAll(readPosterSnapshot("[$it]".reader()))
                }
            } else {
                prefs[stringPreferencesKey("watch_progress_map")]?.let {
                    posters.addAll(readPosterSnapshot(it.reader()))
                }
            }
        }
    }
    return cachedPosterVariants(context, posters.toList())
}


/** Read-only previews from the named profile's existing cache; never activate a profile. */
internal fun readProfilePosters(filesDir: File, profileId: Int): List<String> {
    if (profileId <= 0) return emptyList()
    return listOf("inprogress", "nextup").flatMap { kind ->
        val file = File(filesDir, "cw_enrichment/${kind}_${profileId}.json")
        if (!file.isFile || file.length() > 8 * 1024 * 1024) emptyList()
        else runCatching { file.reader().use(::readPosterSnapshot) }.getOrDefault(emptyList())
    }.distinct().take(24)
}

internal fun readPosterSnapshot(reader: Reader): List<String> {
    val posters = linkedSetOf<String>()
    JsonReader(reader).use { json ->
        val map = json.peek() == JsonToken.BEGIN_OBJECT
        if (map) json.beginObject() else json.beginArray()
        var inspected = 0
        while (json.hasNext() && inspected++ < 192 && posters.size < 24) {
            if (map) json.nextName()
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() == "poster" && json.peek() == JsonToken.STRING) {
                    json.nextString().takeIf(String::isNotBlank)?.let(posters::add)
                } else json.skipValue()
            }
            json.endObject()
        }
    }
    return posters.toList()
}

/** Match an already cached Home decode bucket, rather than requesting a different TMDB URL. */
internal fun posterCacheCandidates(url: String): List<String> {
    val prefix = "https://image.tmdb.org/t/p/"
    if (!url.startsWith(prefix)) return listOf(url)
    val path = url.removePrefix(prefix).substringAfter('/', "")
    if (path.isEmpty()) return listOf(url)
    return (listOf("w342", "w500", "w780", "original").map { "$prefix$it/$path" } + url).distinct()
}

private fun cachedPosterVariants(context: Context, urls: List<String>, allowUncachedFallback: Boolean = true): List<String> {
    val disk = context.imageLoader.diskCache ?: return if (allowUncachedFallback) urls.take(24) else emptyList()
    val cached = urls.take(72).mapNotNull { url ->
        posterCacheCandidates(url).firstOrNull { candidate ->
            runCatching { disk.openSnapshot(candidate)?.use { true } ?: false }.getOrDefault(false)
        }
    }.distinct().take(24)
    return cached.ifEmpty { if (allowUncachedFallback) urls.take(24) else emptyList() }
}
