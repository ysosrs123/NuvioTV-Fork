package com.nuvio.tv.core.debrid

import android.content.Context
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.AllDebridApi
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.dto.AllDebridMagnetDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Reader
import java.io.StringReader
import javax.inject.Inject
import javax.inject.Singleton

data class DebridAccountItem(
    val id: String,
    val hash: String?,
    val name: String?,
    val ready: Boolean,
    val sizeBytes: Long?,
    val addedAtMs: Long?
)

internal class AccountSnapshot(val items: List<DebridAccountItem>, val atMs: Long)

/**
 * What is already in the Real-Debrid and AllDebrid accounts, so a title found there plays without adding it again.
 * Reading the list also opens the connection to the service ahead of the first Play press.
 */
@Singleton
class DebridAccounts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DebridSettingsDataStore,
    private val realDebridApi: RealDebridApi,
    private val realDebridAuth: RealDebridAuth,
    private val allDebridApi: AllDebridApi
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val snapshots = HashMap<String, AccountSnapshot>()
    private val fetching = HashMap<String, Deferred<List<DebridAccountItem>?>>()
    private val allDebridIds by lazy { context.getSharedPreferences("all_debrid_magnet_ids", Context.MODE_PRIVATE) }

    /** Reads the Real-Debrid and AllDebrid account lists in the background. */
    fun warmUp() {
        scope.launch {
            val settings = dataStore.settings.first()
            if (!settings.canResolvePlayableLinks) return@launch
            DebridProviders.configuredResolverServices(settings)
                .filter { supportsAccountList(it.provider.id) }
                .forEach { account -> launch { items(account.provider.id, account.apiKey, WARM_MAX_AGE_MS) } }
        }
    }

    /** One read of an account list at a time: a second caller waits for the read already under way. */
    suspend fun items(providerId: String, stored: String, maxAgeMs: Long = LOOKUP_MAX_AGE_MS): List<DebridAccountItem>? {
        val key = snapshotKey(providerId, stored)
        val read = mutex.withLock {
            snapshots[key]?.takeIf { System.currentTimeMillis() - it.atMs <= maxAgeMs }?.let { return it.items }
            fetching[key] ?: scope.async {
                try {
                    fetch(providerId, stored)?.also { fresh ->
                        mutex.withLock { snapshots[key] = AccountSnapshot(fresh, System.currentTimeMillis()) }
                    }
                } finally {
                    withContext(NonCancellable) { mutex.withLock { fetching.remove(key) } }
                }
            }.also { fetching[key] = it }
        }
        return read.await()
    }

    /** A ready entry for [hash], or null when the title is not in the account (or the list could not be read). */
    suspend fun findReady(providerId: String, stored: String, hash: String?): DebridAccountItem? {
        val normalized = hash?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val list = items(providerId, stored) ?: return null
        list.firstOrNull { it.ready && it.hash == normalized }?.let { return it }
        if (providerId != DebridProviders.ALL_DEBRID_ID) return null
        val knownId = allDebridIds.getString(normalized, null) ?: return null
        return list.firstOrNull { it.ready && it.id == knownId }
    }

    fun rememberedAllDebridMagnet(hash: String?): String? {
        val normalized = hash?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return allDebridIds.getString(normalized, null)
    }

    fun rememberAllDebridMagnet(hash: String?, id: Long) {
        val normalized = hash?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return
        allDebridIds.edit().putString(normalized, id.toString()).apply()
    }

    suspend fun invalidate(providerId: String, stored: String) {
        mutex.withLock { snapshots.remove(snapshotKey(providerId, stored)) }
    }

    private suspend fun fetch(providerId: String, stored: String): List<DebridAccountItem>? =
        try {
            when (providerId) {
                DebridProviders.REAL_DEBRID_ID -> fetchRealDebrid(stored)
                DebridProviders.ALL_DEBRID_ID -> fetchAllDebrid(stored)
                else -> null
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }

    private suspend fun fetchRealDebrid(stored: String): List<DebridAccountItem>? {
        val response = realDebridAuth.call(stored) { auth ->
            realDebridApi.listTorrents(auth, limit = REAL_DEBRID_LIST_LIMIT)
        } ?: return null
        if (response.code() == 204) return emptyList()
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            return null
        }
        return response.body().orEmpty().mapNotNull { torrent ->
            val id = torrent.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DebridAccountItem(
                id = id,
                hash = torrent.hash?.trim()?.lowercase(),
                name = torrent.filename,
                ready = torrent.status.equals("downloaded", ignoreCase = true),
                sizeBytes = torrent.bytes,
                addedAtMs = parseIsoTime(torrent.added)
            )
        }
    }

    private suspend fun fetchAllDebrid(stored: String): List<DebridAccountItem>? {
        val key = stored.trim().takeIf { it.isNotBlank() } ?: return null
        val response = allDebridApi.listMagnets("Bearer $key")
        response.errorBody()?.close()
        val magnets = response.body()?.use { body ->
            if (response.isSuccessful) parseAllDebridMagnets(body.charStream()) else null
        } ?: return null
        return magnets.mapNotNull { magnet -> magnet.toAccountItem() }
    }

    private fun snapshotKey(providerId: String, stored: String): String =
        providerId + "|" + stored.trim().hashCode().toString(16)

    companion object {
        const val LOOKUP_MAX_AGE_MS = 3L * 60L * 1000L
        const val WARM_MAX_AGE_MS = 60L * 1000L
        private const val REAL_DEBRID_LIST_LIMIT = 500

        fun supportsAccountList(providerId: String?): Boolean =
            providerId == DebridProviders.REAL_DEBRID_ID || providerId == DebridProviders.ALL_DEBRID_ID
    }
}

internal fun AllDebridMagnetDto.toAccountItem(): DebridAccountItem? {
    val magnetId = id ?: return null
    return DebridAccountItem(
        id = magnetId.toString(),
        hash = hash?.trim()?.lowercase()?.takeIf { it.isNotBlank() },
        name = filename,
        ready = isReady,
        sizeBytes = size,
        addedAtMs = uploadDate?.let { it * 1000L }
    )
}

/** AllDebrid answers `data.magnets` as a list, or as a single object when one id was asked for. */
internal fun parseAllDebridMagnets(json: String): List<AllDebridMagnetDto>? = parseAllDebridMagnets(StringReader(json))

/** Read as a stream, so a long magnet list is never held as one string or one JSON tree. */
internal fun parseAllDebridMagnets(source: Reader): List<AllDebridMagnetDto>? = runCatching {
    JsonReader(source).use { reader ->
        var success = false
        var magnets = emptyList<AllDebridMagnetDto>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "status" -> success = reader.stringOrNull().equals("success", ignoreCase = true)
                "data" -> if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "magnets") magnets = reader.magnets() else reader.skipValue()
                    }
                    reader.endObject()
                } else {
                    reader.skipValue()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        magnets.takeIf { success }
    }
}.getOrNull()

private fun JsonReader.magnets(): List<AllDebridMagnetDto> = when (peek()) {
    JsonToken.BEGIN_OBJECT -> listOf(magnet())
    JsonToken.BEGIN_ARRAY -> {
        val list = ArrayList<AllDebridMagnetDto>()
        beginArray()
        while (hasNext()) {
            if (peek() == JsonToken.BEGIN_OBJECT) list += magnet() else skipValue()
        }
        endArray()
        list
    }
    else -> {
        skipValue()
        emptyList()
    }
}

private fun JsonReader.magnet(): AllDebridMagnetDto {
    var id: Long? = null
    var filename: String? = null
    var hash: String? = null
    var size: Long? = null
    var status: String? = null
    var statusCode: Int? = null
    var uploadDate: Long? = null
    var completionDate: Long? = null
    beginObject()
    while (hasNext()) {
        when (nextName()) {
            "id" -> id = longOrNull()
            "filename" -> filename = stringOrNull()
            "hash" -> hash = stringOrNull()
            "size" -> size = longOrNull()
            "status" -> status = stringOrNull()
            "statusCode" -> statusCode = longOrNull()?.toInt()
            "uploadDate" -> uploadDate = longOrNull()
            "completionDate" -> completionDate = longOrNull()
            else -> skipValue()
        }
    }
    endObject()
    return AllDebridMagnetDto(
        id = id,
        filename = filename,
        hash = hash,
        size = size,
        status = status,
        statusCode = statusCode,
        uploadDate = uploadDate,
        completionDate = completionDate
    )
}

private fun JsonReader.stringOrNull(): String? = when (peek()) {
    JsonToken.STRING, JsonToken.NUMBER -> nextString()
    JsonToken.BOOLEAN -> nextBoolean().toString()
    else -> {
        skipValue()
        null
    }
}

private fun JsonReader.longOrNull(): Long? = when (peek()) {
    JsonToken.STRING, JsonToken.NUMBER -> nextString().let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
    else -> {
        skipValue()
        null
    }
}

private fun parseIsoTime(value: String?): Long? =
    value?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
