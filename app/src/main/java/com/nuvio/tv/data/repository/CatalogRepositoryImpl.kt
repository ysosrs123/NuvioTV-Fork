package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.core.health.AddonHealthStore
import com.nuvio.tv.core.health.HealthOutcome
import com.nuvio.tv.core.util.canonicalizeAddonUrl
import com.nuvio.tv.core.poster.withCustomPosterUrls
import com.nuvio.tv.data.mapper.toDomainOrNull
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.data.mediaserver.messageRes
import com.nuvio.tv.data.mediaserver.serverFailure
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.CatalogRepository
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val healthStore: AddonHealthStore,
    private val serverCatalog: ServerCatalog
) : CatalogRepository {
    companion object {
        private const val TAG = "CatalogRepository"

        // In-memory freshness cache: short TTL, drop-stale,
        // skip-network-on-hit. Keyed on the fully-resolved catalogue URL.
        private const val CATALOG_CACHE_TTL_MS = 90_000L
        private const val CATALOG_CACHE_MAX_ENTRIES = 64

        // Disk first-paint cache. DIFFERENT purpose to the in-memory cache: it is a
        // stale-while-revalidate FIRST PAINT, not a freshness gate. On a disk hit we
        // serve the stored row immediately and ALWAYS revalidate over the network,
        // swapping in the fresh row when it lands (unless identical). Only skip==0,
        // no-extra requests -- the first page of a home row -- are eligible. The
        // ceiling only caps how old a blob may be before we stop serving it at all.
        private const val DISK_CACHE_FILE_NAME = "catalog_rows_v1.json"
        private const val DISK_CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
        private const val DISK_CACHE_MAX_ENTRIES = 128

        private data class CacheEntry(val row: CatalogRow, val storedAtMs: Long)
        private data class DiskEntry(val dtoJson: String, val storedAtMs: Long)

        // access-ordered LinkedHashMap wrapped for LRU eviction; synchronized
        // because catalogue fetches run concurrently across rows.
        private val catalogCache: MutableMap<String, CacheEntry> =
            Collections.synchronizedMap(
                object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
                    override fun removeEldestEntry(eldest: Map.Entry<String, CacheEntry>): Boolean =
                        size > CATALOG_CACHE_MAX_ENTRIES
                }
            )
    }

    private val moshi: Moshi = Moshi.Builder().build()
    private val catalogResponseAdapter = moshi.adapter(CatalogResponseDto::class.java)

    private val diskFile: File by lazy { File(context.cacheDir, DISK_CACHE_FILE_NAME) }
    private val diskCache = ConcurrentHashMap<String, DiskEntry>()
    private val diskMutex = Mutex()
    @Volatile private var diskLoaded = false
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Warm the disk mirror off the critical path so it is ready before the first
        // getCatalog collection; the guard in ensureDiskLoaded() covers the race.
        ioScope.launch { runCatching { ensureDiskLoaded() } }
    }

    override fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean,
        posterScreen: com.nuvio.tv.core.poster.CustomPosterScreen
    ): Flow<NetworkResult<CatalogRow>> = flow {
        emit(NetworkResult.Loading)

        val posterPattern = com.nuvio.tv.core.poster.patternForScreen(
            layoutPreferenceDataStore.customPosterUrlPattern.first(),
            posterScreen,
            layoutPreferenceDataStore.customPosterEnabledScreens.first()
        )

        if (ServerCatalog.isServerAddon(addonBaseUrl)) {
            val row = try {
                serverCatalog.catalog(addonBaseUrl, addonId, addonName, catalogId, catalogName, type, skip, extraArgs, posterPattern)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                emit(NetworkResult.Error(context.getString(error.serverFailure().messageRes())))
                return@flow
            }
            emit(NetworkResult.Success(row))
            // Same first-page condition as the disk cache below, whose callers already take a
            // second row for the same request.
            if (skip == 0 && extraArgs.isEmpty()) {
                serverCatalog.settledArtwork(row, posterPattern)?.let { emit(NetworkResult.Success(it)) }
            }
            return@flow
        }

        val url = buildCatalogUrl(addonBaseUrl, type, catalogId, skip, extraArgs)
        fun CatalogRow.withPosterPattern(): CatalogRow =
            if (posterPattern.isBlank()) this else copy(items = items.withCustomPosterUrls(posterPattern))

        // 1) In-memory freshness cache hit within TTL: serve and skip the network.
        val cached = catalogCache[url]
        if (cached != null) {
            if (System.currentTimeMillis() - cached.storedAtMs <= CATALOG_CACHE_TTL_MS) {
                Log.d(TAG, "Catalog cache hit type=$type catalogId=$catalogId skip=$skip url=$url")
                emit(NetworkResult.Success(cached.row.withPosterPattern()))
                return@flow
            } else {
                catalogCache.remove(url)
            }
        }

        // 2) Disk first-paint cache (first page of a home row only). Serve stale
        //    immediately, then fall through to revalidate over the network.
        val diskEligible = skip == 0 && extraArgs.isEmpty()
        var servedDiskRow: CatalogRow? = null
        if (diskEligible) {
            val diskRow = runCatching {
                readDiskRow(
                    url = url,
                    addonBaseUrl = addonBaseUrl,
                    addonId = addonId,
                    addonName = addonName,
                    catalogId = catalogId,
                    catalogName = catalogName,
                    type = type,
                    skip = skip,
                    skipStep = skipStep,
                    supportsSkip = supportsSkip
                )
            }.getOrNull()?.withPosterPattern()
            if (diskRow != null) {
                Log.d(TAG, "Catalog disk hit (serving stale, revalidating) type=$type catalogId=$catalogId url=$url")
                servedDiskRow = diskRow
                emit(NetworkResult.Success(diskRow))
            }
        }

        Log.d(
            TAG,
            "Fetching catalog addonId=$addonId addonName=$addonName type=$type catalogId=$catalogId skip=$skip skipStep=$skipStep supportsSkip=$supportsSkip url=$url"
        )

        val healthT0 = android.os.SystemClock.elapsedRealtime()
        when (val result = safeApiCall(context) { api.getCatalog(url) }) {
            is NetworkResult.Success -> {
                recordAddonHealth(addonBaseUrl, HealthOutcome.SUCCESS, android.os.SystemClock.elapsedRealtime() - healthT0)
                val catalogRow = buildCatalogRow(
                    data = result.data,
                    addonBaseUrl = addonBaseUrl,
                    addonId = addonId,
                    addonName = addonName,
                    catalogId = catalogId,
                    catalogName = catalogName,
                    type = type,
                    skip = skip,
                    skipStep = skipStep,
                    supportsSkip = supportsSkip,
                    extraArgs = extraArgs
                )
                Log.d(
                    TAG,
                    "Catalog fetch success addonId=$addonId type=$type catalogId=$catalogId items=${catalogRow.items.size}"
                )
                // The addon's own declared TTL, against the local one.
                // declared=null means the addon sent no cacheMaxAge.
                Log.i(
                    TAG,
                    "CATALOG_TTL declared=${result.data.cacheMaxAge} " +
                        "localMs=$CATALOG_CACHE_TTL_MS " +
                        "addonName=$addonName catalogId=$catalogId"
                )

                catalogCache[url] = CacheEntry(catalogRow, System.currentTimeMillis())
                if (diskEligible) {
                    runCatching { writeDiskRow(url, catalogResponseAdapter.toJson(result.data)) }
                }

                // Emit the fresh row unless it is identical to the disk row already
                // served (avoids a needless row swap / focus disruption).
                val shownRow = catalogRow.withPosterPattern()
                if (servedDiskRow == null || servedDiskRow != shownRow) {
                    emit(NetworkResult.Success(shownRow))
                }
            }
            is NetworkResult.Error -> {
                recordAddonHealth(addonBaseUrl, HealthOutcome.FAILURE, android.os.SystemClock.elapsedRealtime() - healthT0)
                Log.w(
                    TAG,
                    "Catalog fetch failed addonId=$addonId type=$type catalogId=$catalogId code=${result.code} message=${result.message} url=$url"
                )
                // If a disk row was already served, keep it on screen rather than
                // surfacing the error and blanking the row.
                if (servedDiskRow == null) {
                    emit(result)
                }
            }
            NetworkResult.Loading -> { /* Already emitted */ }
        }
    }

    private fun recordAddonHealth(baseUrl: String, outcome: HealthOutcome, latencyMs: Long) {
        ioScope.launch {
            healthStore.record(AddonHealthStore.addonKey(canonicalizeAddonUrl(baseUrl)), outcome, latencyMs)
        }
    }

    private fun buildCatalogRow(
        data: CatalogResponseDto,
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        supportsSkip: Boolean,
        extraArgs: Map<String, String>
    ): CatalogRow {
        // On duplicate IDs keep the FIRST (catalogue order is
        // meaningful) but backfill visual/metadata fields the first copy lacks from
        // the dropped duplicate, so a richer poster/background/description is not lost.
        // Metas may contain null elements and entries with
        // blank id/name (malformed-entry guard). Skip those; toDomainOrNull
        // returns null for a blank id or name.
        val rawItemCount = data.metas.size
        val merged = LinkedHashMap<String, MetaPreview>(rawItemCount)
        data.metas.forEach { dto ->
            val item = dto?.toDomainOrNull(type, addonBaseUrl) ?: return@forEach
            val prior = merged[item.id]
            merged[item.id] = if (prior == null) {
                item
            } else {
                prior.copy(
                    poster = prior.poster ?: item.poster,
                    background = prior.background ?: item.background,
                    logo = prior.logo ?: item.logo,
                    description = prior.description ?: item.description,
                    releaseInfo = prior.releaseInfo ?: item.releaseInfo,
                    imdbRating = prior.imdbRating ?: item.imdbRating,
                    landscapePoster = prior.landscapePoster ?: item.landscapePoster,
                    behaviorHints = prior.behaviorHints ?: item.behaviorHints
                )
            }
        }
        val items = merged.values.toList()
        return CatalogRow(
            addonId = addonId,
            addonName = addonName,
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = catalogName,
            type = ContentType.fromString(type),
            rawType = type,
            items = items,
            isLoading = false,
            hasMore = supportsSkip && rawItemCount > 0,
            currentPage = if (skipStep > 0) skip / skipStep else 0,
            supportsSkip = supportsSkip,
            skipStep = skipStep,
            nextSkip = if (supportsSkip && rawItemCount > 0) skip + rawItemCount else skip,
            extraArgs = extraArgs
        )
    }

    // ---- disk first-paint cache -------------------------------------------------

    private suspend fun ensureDiskLoaded() {
        if (diskLoaded) return
        diskMutex.withLock {
            if (diskLoaded) return
            withContext(Dispatchers.IO) {
                runCatching {
                    if (diskFile.exists()) {
                        val root = JSONObject(diskFile.readText())
                        val now = System.currentTimeMillis()
                        val keys = root.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val obj = root.getJSONObject(key)
                            val storedAt = obj.getLong("t")
                            if (now - storedAt <= DISK_CACHE_MAX_AGE_MS) {
                                diskCache[key] = DiskEntry(obj.getString("b"), storedAt)
                            }
                        }
                    }
                }
            }
            diskLoaded = true
        }
    }

    private suspend fun readDiskRow(
        url: String,
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        supportsSkip: Boolean
    ): CatalogRow? {
        ensureDiskLoaded()
        val entry = diskCache[url] ?: return null
        if (System.currentTimeMillis() - entry.storedAtMs > DISK_CACHE_MAX_AGE_MS) {
            diskCache.remove(url)
            return null
        }
        val dto = catalogResponseAdapter.fromJson(entry.dtoJson) ?: return null
        return buildCatalogRow(
            data = dto,
            addonBaseUrl = addonBaseUrl,
            addonId = addonId,
            addonName = addonName,
            catalogId = catalogId,
            catalogName = catalogName,
            type = type,
            skip = skip,
            skipStep = skipStep,
            supportsSkip = supportsSkip,
            extraArgs = emptyMap()
        )
    }

    private suspend fun writeDiskRow(url: String, dtoJson: String) {
        ensureDiskLoaded()
        diskCache[url] = DiskEntry(dtoJson, System.currentTimeMillis())
        if (diskCache.size > DISK_CACHE_MAX_ENTRIES) {
            diskCache.entries
                .sortedBy { it.value.storedAtMs }
                .take(diskCache.size - DISK_CACHE_MAX_ENTRIES)
                .forEach { diskCache.remove(it.key) }
        }
        flushDisk()
    }

    private suspend fun flushDisk() {
        diskMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val root = JSONObject()
                    diskCache.entries.forEach { (key, entry) ->
                        root.put(key, JSONObject().put("b", entry.dtoJson).put("t", entry.storedAtMs))
                    }
                    diskFile.writeText(root.toString())
                }
            }
        }
    }

    override suspend fun clearCaches() {
        // In-memory freshness LRU: a synchronizedMap, so a single clear() is
        // atomic on its own.
        catalogCache.clear()
        // Disk first-paint cache: clear the map and delete the backing file
        // under the same mutex ensureDiskLoaded()/flushDisk() take, so a
        // concurrent flush cannot interleave with the delete. diskLoaded is
        // left true so a later read does not reload the file just deleted.
        // A writeDiskRow racing this clear simply repopulates afterwards,
        // which is a fresh fetch doing exactly what it should.
        diskMutex.withLock {
            diskCache.clear()
            withContext(Dispatchers.IO) {
                runCatching { if (diskFile.exists()) diskFile.delete() }
            }
            diskLoaded = true
        }
    }

    private fun buildCatalogUrl(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): String {
        val trimmedBase = baseUrl.trimEnd('/')
        val queryStart = trimmedBase.indexOf('?')
        val basePath = if (queryStart >= 0) trimmedBase.substring(0, queryStart).trimEnd('/') else trimmedBase
        val baseQuery = if (queryStart >= 0) trimmedBase.substring(queryStart) else ""

        val catalogPath = if (extraArgs.isEmpty()) {
            if (skip > 0) {
                "$basePath/catalog/$type/$catalogId/skip=$skip.json"
            } else {
                "$basePath/catalog/$type/$catalogId.json"
            }
        } else {
            val allArgs = LinkedHashMap<String, String>()
            allArgs.putAll(extraArgs)

            if (!allArgs.containsKey("skip") && skip > 0) {
                allArgs["skip"] = skip.toString()
            }

            val encodedArgs = allArgs.entries.joinToString("&") { (key, value) ->
                "${encodeArg(key)}=${encodeArg(value)}"
            }

            "$basePath/catalog/$type/$catalogId/$encodedArgs.json"
        }

        return catalogPath + baseQuery
    }

    private fun encodeArg(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
