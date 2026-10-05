package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Looks up genre names for one title; null when the lookup failed. */
fun interface LibraryGenreLookup {
    suspend fun genres(target: LibraryGenreTarget, language: String): List<String>?
}

data class LibraryGenreTarget(val type: String, val tmdbId: Int?, val imdbId: String?) {
    val key: String get() = "$type:" + (tmdbId?.let { "tmdb:$it" } ?: "imdb:$imdbId")
}

interface LibraryGenreStore {
    val isLoaded: Boolean
    suspend fun load()
    fun get(key: String): List<String>?
    fun put(key: String, genres: List<String>)
}

/**
 * Simkl and MDBList libraries carry few or no genres, so the Library genre filter has nothing to show.
 * Entries from those sources without genres get them from TMDB, looked up once per title and kept on disk.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryGenreFill internal constructor(
    private val lookup: LibraryGenreLookup,
    private val store: LibraryGenreStore,
    private val language: Flow<String>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val startDelayMs: Long = START_DELAY_MS,
    private val now: () -> Long = System::currentTimeMillis
) {
    @Inject
    constructor(
        tmdbMetadataService: TmdbMetadataService,
        tmdbService: TmdbService,
        tmdbSettingsDataStore: TmdbSettingsDataStore,
        @ApplicationContext context: Context
    ) : this(
        lookup = TmdbLibraryGenreLookup(tmdbMetadataService, tmdbService),
        store = FileLibraryGenreStore(File(context.filesDir, "library_genres")),
        language = tmdbSettingsDataStore.settings.map { it.language }
    )

    private val permits = Semaphore(MAX_CONCURRENT_LOOKUPS)
    private val settled = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var pausedUntil = 0L

    fun fill(items: Flow<List<LibraryEntry>>): Flow<List<LibraryEntry>> =
        combine(items, language.distinctUntilChanged(), ::Pair).transformLatest { (entries, language) ->
            val targets = entries.mapNotNull { it.genreTarget() }.associateBy { cacheKey(language, it) }
            if (targets.isEmpty()) {
                emit(entries)
                return@transformLatest
            }
            if (!store.isLoaded) {
                emit(entries)
                store.load()
            }
            emit(entries.withGenres(language))
            val missing = targets.filterKeys { key -> store.get(key) == null && key !in settled }.toList()
            if (missing.isEmpty() || now() < pausedUntil) return@transformLatest
            delay(startDelayMs)
            for (chunk in missing.chunked(EMIT_EVERY)) {
                val results = coroutineScope {
                    chunk.map { (key, target) ->
                        async { permits.withPermit { lookupOne(target, key, language) } }
                    }.awaitAll()
                }
                if (results.any { it }) emit(entries.withGenres(language))
                if (results.none { it } && chunk.size >= FAILURE_PAUSE_MIN_CHUNK) {
                    pausedUntil = now() + FAILURE_PAUSE_MS
                    return@transformLatest
                }
            }
        }.distinctUntilChanged().flowOn(dispatcher)

    private suspend fun lookupOne(target: LibraryGenreTarget, key: String, language: String): Boolean {
        val genres = try {
            lookup.genres(target, language)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        settled += key
        if (genres == null) return false
        val cleaned = genres.map(String::trim).filter(String::isNotEmpty).distinct()
        if (cleaned.isNotEmpty()) store.put(key, cleaned)
        return true
    }

    private fun List<LibraryEntry>.withGenres(language: String): List<LibraryEntry> = map { entry ->
        val target = entry.genreTarget() ?: return@map entry
        store.get(cacheKey(language, target))?.let { entry.copy(genres = it) } ?: entry
    }

    private fun cacheKey(language: String, target: LibraryGenreTarget) = "${language.trim()}|${target.key}"

    companion object {
        internal const val MAX_CONCURRENT_LOOKUPS = 4
        internal const val EMIT_EVERY = 40
        internal const val START_DELAY_MS = 1_500L
        internal const val FAILURE_PAUSE_MS = 10L * 60L * 1000L
        private const val FAILURE_PAUSE_MIN_CHUNK = 4
        private val FILLED_PROVIDERS = setOf(TrackingProviderId.SIMKL.storageId, TrackingProviderId.MDBLIST.storageId)

        internal fun LibraryEntry.genreTarget(): LibraryGenreTarget? {
            if (genres.isNotEmpty() || trackingProviderId !in FILLED_PROVIDERS) return null
            val kind = when (type) {
                "movie" -> "movie"
                "series", "tv" -> "series"
                else -> return null
            }
            val tmdb = tmdbId?.takeIf { it > 0 }
                ?: id.removePrefix("tmdb:").takeIf { id.startsWith("tmdb:") }?.substringBefore(':')?.toIntOrNull()
            val imdb = (imdbId ?: id.takeIf { it.startsWith("tt") })?.substringBefore(':')?.takeIf { it.startsWith("tt") }
            if (tmdb == null && imdb == null) return null
            return LibraryGenreTarget(kind, tmdb, imdb)
        }
    }
}

internal class TmdbLibraryGenreLookup(
    private val metadata: TmdbMetadataService,
    private val ids: TmdbService
) : LibraryGenreLookup {
    override suspend fun genres(target: LibraryGenreTarget, language: String): List<String>? {
        val tmdbId = target.tmdbId ?: target.imdbId?.let { ids.imdbToTmdb(it, target.type) } ?: return null
        val type = if (target.type == "movie") ContentType.MOVIE else ContentType.SERIES
        return metadata.fetchPosterArt(tmdbId.toString(), type, language)?.genres
    }
}

internal class FileLibraryGenreStore(
    private val directory: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : LibraryGenreStore {
    private val entries = ConcurrentHashMap<String, List<String>>()
    private val mutex = Mutex()
    @Volatile
    override var isLoaded = false
        private set
    private var saveJob: Job? = null

    private val file get() = File(directory, "genres_v1.json")

    override suspend fun load() {
        if (isLoaded) return
        mutex.withLock {
            if (isLoaded) return
            withContext(ioDispatcher) {
                runCatching {
                    if (file.exists()) {
                        JSON.decodeFromString(SERIALIZER, file.readText()).forEach { (key, genres) -> entries.putIfAbsent(key, genres) }
                    }
                }
            }
            isLoaded = true
        }
    }

    override fun get(key: String): List<String>? = entries[key]

    override fun put(key: String, genres: List<String>) {
        entries[key] = genres
        synchronized(this) {
            saveJob?.cancel()
            saveJob = scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                save()
            }
        }
    }

    internal suspend fun save() = mutex.withLock {
        withContext(ioDispatcher) {
            runCatching {
                directory.mkdirs()
                val snapshot = entries.entries.take(MAX_ENTRIES).associate { it.key to it.value }
                val tmp = File(directory, "${file.name}.tmp")
                tmp.writeText(JSON.encodeToString(SERIALIZER, snapshot))
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 2_000L
        const val MAX_ENTRIES = 20_000
        val JSON = Json { ignoreUnknownKeys = true }
        val SERIALIZER = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    }
}
