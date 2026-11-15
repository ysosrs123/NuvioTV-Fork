package com.nuvio.tv.core.scout

import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.ScoutPick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ScoutAnimeRecaps"
private const val ANIMATION = 16
private const val JAPANESE = "ja"

/** TMDB keywords "compilation" and "edited from tv series". */
internal const val SCOUT_RECAP_KEYWORDS = "12197|194008"
private const val RECAP_PAGES = 8
private const val RECAP_MIN_VOTES = 20
private const val RECAP_WEEK_MS = 7L * 24 * 60 * 60 * 1000

/** Re-cut films people still watch in place of the series: Mobile Suit Gundam I, II and III. */
internal val SCOUT_RECAP_KEEP = setOf("movie:39228", "movie:39229", "movie:39230")

private val RECAP_TITLE = Regex("""\b(compilation|recap|special edition|extra edition)\b""", RegexOption.IGNORE_CASE)

/**
 * An anime film that only re-cuts a TV season: tagged so on TMDB, or named so while it is too new to be tagged.
 * Only Japanese animated films; the same keywords on other films mark things people do want.
 */
internal fun scoutIsAnimeRecap(pick: ScoutPick, tagged: Set<String>): Boolean {
    if (pick.tv || pick.key in SCOUT_RECAP_KEEP) return false
    if (pick.key in tagged) return true
    return ANIMATION in pick.genres && pick.language == JAPANESE && RECAP_TITLE.containsMatchIn(pick.name)
}

/** The tagged films, asked from TMDB once a week and kept on disk. */
internal class ScoutAnimeRecapList(private val context: Context, private val tmdbApi: TmdbApi) {
    private val lock = Mutex()
    @Volatile private var keys: Set<String>? = null
    @Volatile private var fetchedAt = 0L

    private val file get() = File(context.filesDir, "scout/anime_recaps.txt")

    suspend fun keys(): Set<String> = lock.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (keys == null) runCatching { readFile() }
            val held = keys
            if (held != null && now - fetchedAt < RECAP_WEEK_MS) return@withContext held
            val fresh = runCatching { fetch() }.onFailure { Log.w(TAG, "Recap list not refreshed: ${it.message}") }.getOrNull()
            if (fresh != null) {
                keys = fresh
                fetchedAt = now
                runCatching { writeFile(fresh, now) }
            }
            keys.orEmpty()
        }
    }

    private suspend fun fetch(): Set<String> {
        val found = LinkedHashSet<String>()
        var page = 1
        while (page <= RECAP_PAGES) {
            val body = tmdbApi.discoverMovies(
                apiKey = BuildConfig.TMDB_API_KEY,
                page = page,
                sortBy = "vote_count.desc",
                voteCountGte = RECAP_MIN_VOTES,
                withGenres = ANIMATION.toString(),
                withOriginalLanguage = JAPANESE,
                withKeywords = SCOUT_RECAP_KEYWORDS
            ).body() ?: error("no answer")
            body.results.orEmpty().forEach { found += "movie:${it.id}" }
            if (page >= (body.totalPages ?: 1)) break
            page++
        }
        return found
    }

    private fun readFile() {
        val lines = file.takeIf { it.exists() }?.readLines() ?: return
        fetchedAt = lines.firstOrNull()?.toLongOrNull() ?: return
        keys = lines.drop(1).filter { it.startsWith("movie:") }.toSet()
    }

    private fun writeFile(keys: Set<String>, at: Long) {
        file.parentFile?.mkdirs()
        file.writeText((listOf(at.toString()) + keys).joinToString("\n"))
    }
}
