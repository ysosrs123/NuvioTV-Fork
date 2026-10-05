package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.TmdbSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Looks up genre names for one title; empty when TMDB has none, null when the lookup failed. */
fun interface LibraryGenreLookup {
    suspend fun genres(target: LibraryGenreTarget, language: String): List<String>?
}

data class LibraryGenreTarget(val type: String, val tmdbId: Int?, val imdbId: String?) {
    val key: String get() = "$type:" + (tmdbId?.let { "tmdb:$it" } ?: "imdb:$imdbId")
}

data class LibraryGenreFillSettings(val language: String, val lookupsEnabled: Boolean)

interface LibraryGenreStore {
    val isLoaded: Boolean
    suspend fun load()

    /** Known genres for [key] (empty when TMDB has none), or null when unknown or due for another look. */
    fun get(key: String): List<String>?
    fun put(key: String, genres: List<String>)
}

/**
 * Simkl and MDBList libraries carry few or no genres, so their Library genre filter has little to show.
 * Titles without genres get them from TMDB, looked up once per title, only while the Library is on screen, and kept
 * on disk. Genres the provider does send are kept and shown as words instead of slugs.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryGenreFill internal constructor(
    private val lookup: LibraryGenreLookup,
    private val store: LibraryGenreStore,
    private val settings: Flow<LibraryGenreFillSettings>,
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
        settings = tmdbSettingsDataStore.settings.map(::fillSettings)
    )

    private val permits = Semaphore(MAX_CONCURRENT_LOOKUPS)
    private val screenVisible = MutableStateFlow(false)
    private val settled = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var pausedUntil = 0L

    fun setScreenVisible(visible: Boolean) {
        screenVisible.value = visible
    }

    fun fill(items: Flow<List<LibraryEntry>>): Flow<List<LibraryEntry>> =
        combine(items, settings.distinctUntilChanged(), ::Pair).transformLatest { (entries, current) ->
            val language = current.language
            if (!current.lookupsEnabled) {
                emit(entries.withGenres(language, useCache = false))
                return@transformLatest
            }
            val targets = entries.mapNotNull { it.genreTarget() }.associateBy { cacheKey(language, it) }
            if (!store.isLoaded && targets.isNotEmpty()) {
                emit(entries.withGenres(language))
                store.load()
            }
            emit(entries.withGenres(language))
            val missing = targets.filterKeys { key -> store.get(key) == null && key !in settled }.toList()
            if (missing.isEmpty() || now() < pausedUntil) return@transformLatest
            screenVisible.first { it }
            delay(startDelayMs)
            var lastEmitAt = now()
            var pending = false
            for (chunk in missing.chunked(EMIT_EVERY)) {
                val results = coroutineScope {
                    chunk.map { (key, target) ->
                        async {
                            permits.withPermit {
                                screenVisible.first { it }
                                lookupOne(target, key, language)
                            }
                        }
                    }.awaitAll()
                }
                if (results.any { it }) pending = true
                if (pending && now() - lastEmitAt >= MIN_EMIT_INTERVAL_MS) {
                    emit(entries.withGenres(language))
                    pending = false
                    lastEmitAt = now()
                }
                if (results.none { it } && chunk.size >= FAILURE_PAUSE_MIN_CHUNK) {
                    pausedUntil = now() + FAILURE_PAUSE_MS
                    break
                }
            }
            if (pending) emit(entries.withGenres(language))
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
        store.put(key, genres.map(String::trim).filter(String::isNotEmpty).distinct())
        return true
    }

    private fun List<LibraryEntry>.withGenres(language: String, useCache: Boolean = true): List<LibraryEntry> = map { entry ->
        if (entry.trackingProviderId !in FILLED_PROVIDERS) return@map entry
        val genres = if (entry.genres.isNotEmpty()) {
            entry.genres.map(::genreDisplayName).filter(String::isNotEmpty).distinct()
        } else {
            entry.genreTarget()?.takeIf { useCache }?.let { store.get(cacheKey(language, it)) }.orEmpty()
        }
        if (genres == entry.genres) entry else entry.copy(genres = genres)
    }

    private fun cacheKey(language: String, target: LibraryGenreTarget) = "${language.trim()}|${target.key}"

    companion object {
        /** TMDB genre lookups only run while TMDB is switched on in settings. */
        internal const val LOOKUPS_FOLLOW_TMDB_SWITCH = true

        internal const val MAX_CONCURRENT_LOOKUPS = 4
        internal const val EMIT_EVERY = 40
        internal const val MIN_EMIT_INTERVAL_MS = 2_000L
        internal const val START_DELAY_MS = 1_500L
        internal const val FAILURE_PAUSE_MS = 10L * 60L * 1000L
        private const val FAILURE_PAUSE_MIN_CHUNK = 4
        private val FILLED_PROVIDERS = setOf(TrackingProviderId.SIMKL.storageId, TrackingProviderId.MDBLIST.storageId)

        internal fun fillSettings(settings: TmdbSettings) =
            LibraryGenreFillSettings(settings.language, settings.enabled || !LOOKUPS_FOLLOW_TMDB_SWITCH)

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

        internal fun genreDisplayName(genre: String): String {
            val name = genre.trim()
            if (name.any(Char::isUpperCase)) return name
            return name.split('-', '_', ' ').filter(String::isNotEmpty)
                .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
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
        return metadata.fetchGenres(tmdbId, type, language)
    }
}

@Serializable
internal data class StoredLibraryGenres(val genres: List<String>, val storedAt: Long = 0)

internal class FileLibraryGenreStore(
    private val directory: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis
) : LibraryGenreStore {
    private val entries = ConcurrentHashMap<String, StoredLibraryGenres>()
    private val mutex = Mutex()
    @Volatile
    override var isLoaded = false
        private set
    private var saveJob: Job? = null

    private val file get() = File(directory, "genres_v2.json")

    override suspend fun load() {
        if (isLoaded) return
        mutex.withLock {
            if (isLoaded) return
            withContext(ioDispatcher) {
                runCatching {
                    if (file.exists()) {
                        JSON.decodeFromString(SERIALIZER, file.readText()).forEach { (key, stored) -> entries.putIfAbsent(key, stored) }
                    }
                }
            }
            isLoaded = true
        }
    }

    override fun get(key: String): List<String>? {
        val stored = entries[key] ?: return null
        if (stored.genres.isEmpty() && now() - stored.storedAt !in 0 until EMPTY_RETRY_MS) return null
        return stored.genres
    }

    override fun put(key: String, genres: List<String>) {
        entries[key] = StoredLibraryGenres(genres, now())
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
        const val EMPTY_RETRY_MS = 7L * 24L * 60L * 60L * 1000L
        const val MAX_ENTRIES = 20_000
        val JSON = Json { ignoreUnknownKeys = true }
        val SERIALIZER = MapSerializer(String.serializer(), StoredLibraryGenres.serializer())
    }
}
