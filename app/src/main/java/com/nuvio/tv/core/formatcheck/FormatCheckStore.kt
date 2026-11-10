package com.nuvio.tv.core.formatcheck

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.formatCheckDataStore by preferencesDataStore(
    name = "format_check_device",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() }
)

data class SavedFormatCheck(
    val atMs: Long,
    val withTvAnswers: Boolean,
    val audioRouteKey: String?,
    val results: List<ClipResult>
)

/** Results and rules describe this box and its audio chain, so they stay out of profile sync. */
object FormatCheckStore {
    private val resultsKey = stringPreferencesKey("results")
    private val rulesKey = stringPreferencesKey("stream_rules")
    private val appliedAudioKey = stringPreferencesKey("applied_audio")
    private val runningClipKey = stringPreferencesKey("running_clip")

    fun results(context: Context): Flow<SavedFormatCheck?> =
        context.formatCheckDataStore.data.map { prefs -> prefs[resultsKey]?.let(::decodeResults) }

    fun rules(context: Context): Flow<Map<FormatFamily, StreamAction>> =
        context.formatCheckDataStore.data.map { prefs -> prefs[rulesKey]?.let(::decodeRules).orEmpty() }

    fun appliedAudio(context: Context): Flow<List<String>> =
        context.formatCheckDataStore.data.map { prefs ->
            prefs[appliedAudioKey]?.let { raw -> runCatching { JSONArray(raw).toStrings() }.getOrNull() }.orEmpty()
        }

    suspend fun runningClip(context: Context): String? = context.formatCheckDataStore.data.first()[runningClipKey]

    suspend fun setRunningClip(context: Context, clipId: String?) {
        context.formatCheckDataStore.edit {
            if (clipId == null) it.remove(runningClipKey) else it[runningClipKey] = clipId
        }
    }

    suspend fun saveResults(context: Context, saved: SavedFormatCheck) {
        context.formatCheckDataStore.edit { it[resultsKey] = encodeResults(saved) }
    }

    suspend fun saveRules(context: Context, rules: Map<FormatFamily, StreamAction>, appliedAudio: List<String>) {
        context.formatCheckDataStore.edit {
            it[rulesKey] = encodeRules(rules)
            it[appliedAudioKey] = JSONArray(appliedAudio).toString()
        }
    }

    internal fun encodeResults(saved: SavedFormatCheck): String = JSONObject().apply {
        put("atMs", saved.atMs)
        put("withTvAnswers", saved.withTvAnswers)
        saved.audioRouteKey?.let { put("audioRouteKey", it) }
        put("results", JSONArray().apply {
            saved.results.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.clipId)
                    put("verdict", r.verdict.name)
                    r.decoder?.let { put("decoder", it) }
                    put("dropped", r.droppedFrames)
                    r.passthrough?.let { put("passthrough", it) }
                    r.answer?.let { put("answer", it) }
                    r.dolbyVisionRoute?.let { put("route", it.name) }
                    put("notes", JSONArray(r.settingNotes.map { it.name }))
                })
            }
        })
    }.toString()

    internal fun decodeResults(raw: String): SavedFormatCheck? = runCatching {
        val json = JSONObject(raw)
        val rows = json.getJSONArray("results")
        SavedFormatCheck(
            atMs = json.getLong("atMs"),
            withTvAnswers = json.optBoolean("withTvAnswers"),
            audioRouteKey = json.optString("audioRouteKey").takeIf { it.isNotBlank() },
            results = (0 until rows.length()).mapNotNull { i ->
                val row = rows.getJSONObject(i)
                val verdict = FormatVerdict.entries.firstOrNull { it.name == row.optString("verdict") }
                    ?: return@mapNotNull null
                ClipResult(
                    clipId = row.getString("id"),
                    verdict = verdict,
                    decoder = row.optString("decoder").takeIf { it.isNotBlank() },
                    droppedFrames = row.optInt("dropped"),
                    passthrough = if (row.has("passthrough")) row.getBoolean("passthrough") else null,
                    answer = row.optString("answer").takeIf { it.isNotBlank() },
                    dolbyVisionRoute = DolbyVisionRoute.entries.firstOrNull { it.name == row.optString("route") },
                    settingNotes = row.optJSONArray("notes")?.toStrings().orEmpty().mapNotNull { name ->
                        FormatSettingNote.entries.firstOrNull { it.name == name }
                    }
                )
            }
        )
    }.getOrNull()

    internal fun encodeRules(rules: Map<FormatFamily, StreamAction>): String = JSONObject().apply {
        rules.forEach { (family, action) -> put(family.name, action.name) }
    }.toString()

    internal fun decodeRules(raw: String): Map<FormatFamily, StreamAction> = runCatching {
        val json = JSONObject(raw)
        json.keys().asSequence().mapNotNull { key ->
            val family = FormatFamily.entries.firstOrNull { it.name == key } ?: return@mapNotNull null
            val action = StreamAction.entries.firstOrNull { it.name == json.optString(key) } ?: return@mapNotNull null
            family to action
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun JSONArray.toStrings() = (0 until length()).map { getString(it) }
}
