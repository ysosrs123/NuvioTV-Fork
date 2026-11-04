package com.nuvio.tv.core.debrid

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Links made for playback, kept after the app closes so a resume starts without asking the service again.
 * An older link is checked with a one-byte read first (1.5 s at most); a link that does not answer in time is dropped.
 */
@Singleton
class ResolvedLinkStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("directDebrid") client: OkHttpClient
) {
    private val prefs by lazy { context.getSharedPreferences("resolved_debrid_links", Context.MODE_PRIVATE) }
    private val gson = Gson()
    private val probeClient = client.newBuilder()
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    class Hit(val result: DirectDebridResolveResult.Success, val savedAtMs: Long)

    suspend fun get(key: String): Hit? = withContext(Dispatchers.IO) {
        val slot = slot(key)
        val entry = runCatching { gson.fromJson(prefs.getString(slot, null), StoredLink::class.java) }.getOrNull()
            ?: return@withContext null
        val age = System.currentTimeMillis() - entry.savedAtMs
        val usable = when {
            entry.key != key || entry.url.isBlank() -> false
            age !in 0..MAX_AGE_MS -> false
            age <= FRESH_MS -> true
            else -> answers(entry.url)
        }
        if (!usable) {
            prefs.edit().remove(slot).apply()
            return@withContext null
        }
        Hit(DirectDebridResolveResult.Success(url = entry.url, filename = entry.filename, videoSize = entry.videoSize), entry.savedAtMs)
    }

    suspend fun put(key: String, result: DirectDebridResolveResult.Success) = withContext(Dispatchers.IO) {
        val all = prefs.all
        val editor = prefs.edit()
        if (all.size >= MAX_ENTRIES) {
            all.keys.mapNotNull { name ->
                runCatching { gson.fromJson(all[name] as? String, StoredLink::class.java) }.getOrNull()?.let { name to it.savedAtMs }
            }.sortedBy { it.second }.take(all.size - MAX_ENTRIES + 1).forEach { editor.remove(it.first) }
        }
        val stored = StoredLink(key, result.url, result.filename, result.videoSize, System.currentTimeMillis())
        editor.putString(slot(key), gson.toJson(stored)).apply()
    }

    fun forget(key: String) {
        prefs.edit().remove(slot(key)).apply()
    }

    private fun answers(url: String): Boolean =
        try {
            val request = Request.Builder().url(url).header("Range", "bytes=0-0").get().build()
            probeClient.newCall(request).execute().use { response -> response.code == 200 || response.code == 206 }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            false
        }

    private fun slot(key: String): String = key.hashCode().toUInt().toString(16) + "_" + key.length

    private data class StoredLink(
        val key: String = "",
        val url: String = "",
        val filename: String? = null,
        val videoSize: Long? = null,
        val savedAtMs: Long = 0L
    )

    private companion object {
        const val FRESH_MS = 15L * 60L * 1000L
        const val MAX_AGE_MS = 6L * 60L * 60L * 1000L
        const val MAX_ENTRIES = 200
        const val PROBE_TIMEOUT_MS = 1_500L
    }
}
