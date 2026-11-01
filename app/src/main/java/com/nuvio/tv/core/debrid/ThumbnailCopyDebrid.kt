package com.nuvio.tv.core.debrid

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.player.thumbnail.ConnectionLedger
import com.nuvio.tv.core.player.thumbnail.CopyChoice
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.PremiumizeApi
import com.nuvio.tv.data.remote.api.TorboxApi
import com.nuvio.tv.data.remote.dto.TorboxCheckCachedRequestDto
import com.nuvio.tv.data.remote.dto.TorboxCloudItemDto
import com.nuvio.tv.data.remote.dto.TorboxControlTorrentRequestDto
import com.nuvio.tv.data.remote.dto.TorboxEnvelopeDto
import com.nuvio.tv.data.remote.dto.TorboxTorrentDataDto
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A debrid link for a copy; [torrentId] is set when the item was added to the user's TorBox list for it. */
internal class DebridCopy(val url: String, val torrentId: Int?)

/**
 * Debrid links for 4K thumbnails made from a smaller copy. TorBox: a copy already in the user's list is read from
 * there and never removed; otherwise a cached copy is added (never one that is not cached), recorded on disk at once,
 * and removed again when the copy pass is done with it, only when TorBox confirms it created that item during the add
 * and playback did not claim it. Premiumize: a direct download link, nothing is added to the account. Other services
 * are reached through their resolve links.
 */
@Singleton
class ThumbnailCopyDebrid @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val torboxApi: TorboxApi,
    private val torboxFileSelector: TorboxFileSelector,
    private val premiumizeApi: PremiumizeApi,
    private val premiumizeFileSelector: PremiumizeDirectDownloadFileSelector,
    private val added: ThumbnailCopyAdded,
) {
    private companion object {
        const val TAG = "ThumbCopy"
        const val LIST_TTL_MS = 2L * 60 * 1000
        const val LIST_PAGE = 1000
        const val LIST_MAX_PAGES = 20
        const val TORBOX_API = "https://api.torbox.app/v1/api"
        const val REMOVE_TRIES = 3
        const val REMOVE_RETRY_MS = 30_000L
        const val SWEEP_EVERY_MS = 10L * 60 * 1000
        const val IN_USE_MAX_MS = 30L * 60 * 1000
    }

    /** The user's TorBox list, every page; [serverMs] is TorBox's clock when the read started, null when not known. */
    private class ListRead(val items: List<TorboxCloudItemDto>, val serverMs: Long?)

    private val records get() = added.records
    internal val limits = TorboxAddLimits()
    private val addLock = Mutex()
    private val listLock = Mutex()
    @Volatile private var listed: Pair<Long, ListRead>? = null
    private val inUse = ConcurrentHashMap<Int, Long>()
    private val removing = ConcurrentHashMap.newKeySet<Int>()
    private var lastSweep: Long? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** How [stream]'s copy would be reached with [settings], or why it is left out; null when it is no debrid source. */
    internal fun routeOf(stream: Stream, settings: DebridSettings): CopyRoute? {
        val usable = settings.canResolvePlayableLinks
        return CopyRoute.of(
            directService = if (stream.isDirectDebrid()) stream.clientResolve?.service.orEmpty() else null,
            hasHash = hashOf(stream) != null,
            torboxKey = usable && settings.torboxApiKey.isNotBlank(),
            premiumizeKey = usable && settings.premiumizeApiKey.isNotBlank(),
        )
    }

    /** Whether [stream]'s torrent is in the user's TorBox list; null when the list cannot be read. */
    internal suspend fun inTorboxList(stream: Stream): Boolean? {
        val hash = hashOf(stream) ?: return false
        return torboxListHashes(dataStore.settings.first())?.contains(hash)
    }

    /** The torrents in the user's TorBox list, read once for a whole source list; null when it cannot be read. */
    internal suspend fun torboxListHashes(settings: DebridSettings): Set<String>? {
        if (!settings.canResolvePlayableLinks) return null
        val apiKey = settings.torboxApiKey.trim().takeIf { it.isNotBlank() } ?: return null
        return try {
            torboxList("Bearer $apiKey", fresh = false)?.items?.mapNotNullTo(HashSet()) { AddedCopyRecords.normal(it.hash) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Whether [stream]'s torrent is among [listed] ([torboxListHashes]); null when the list could not be read. */
    internal fun inTorboxList(stream: Stream, listed: Set<String>?): Boolean? {
        val hash = hashOf(stream) ?: return false
        return listed?.contains(hash)
    }

    /** Playback is on [hash]: a thumbnail copy of it stays in the user's list. */
    internal fun notePlaying(hash: String?) {
        added.claim(hash)
    }

    /**
     * A link to [stream]'s file through TorBox: from the user's list when it is there, else from a cached copy added
     * for [title]'s thumbnails ([DebridCopy.torrentId] set; hand it back with [release]).
     */
    internal suspend fun openTorbox(stream: Stream, season: Int?, episode: Int?, title: String): DebridCopy? {
        sweepLeftovers()
        val apiKey = torboxKey() ?: run {
            Log.i(TAG, "TorBox: no API key in the app, no TorBox copy")
            return null
        }
        val hash = hashOf(stream) ?: return null
        val resolve = resolveOf(stream, season, episode, DebridProviders.TORBOX_ID)
        val auth = "Bearer $apiKey"
        return try {
            val list = torboxList(auth, fresh = false) ?: return null
            list.items.firstOrNull { AddedCopyRecords.normal(it.hash) == hash }?.let { item ->
                return fromList(item, resolve, season, episode, apiKey)?.let { DebridCopy(it, null) }
            }
            addAndOpen(stream, resolve, hash, season, episode, title, apiKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "TorBox: copy link failed (${e.javaClass.simpleName})")
            null
        }
    }

    /** A Premiumize direct download link to [stream]'s file; nothing is added to the account. */
    internal suspend fun openPremiumize(stream: Stream, season: Int?, episode: Int?): String? {
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks) return null
        val apiKey = settings.premiumizeApiKey.trim().takeIf { it.isNotBlank() } ?: return null
        val resolve = resolveOf(stream, season, episode, DebridProviders.PREMIUMIZE_ID)
        val source = resolve.magnetUri?.takeIf { it.isNotBlank() } ?: DebridMagnetBuilder.fromStream(stream) ?: return null
        Log.i(TAG, "Premiumize: copy link via direct download (no account entry)")
        return try {
            val r = premiumizeApi.directDownload("Bearer $apiKey", source)
            val body = r.body()
            if (!r.isSuccessful || body == null) {
                Log.i(TAG, "Premiumize: copy link failed (HTTP ${r.code()})")
                return null
            }
            if (body.status.equals("error", ignoreCase = true)) {
                Log.i(TAG, "Premiumize: not cached, not used")
                return null
            }
            premiumizeFileSelector.selectFile(body.content.orEmpty(), resolve, season, episode)
                ?.link?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Premiumize: copy link failed (${e.javaClass.simpleName})")
            null
        }
    }

    /**
     * The copy pass no longer reads [copy]: a TorBox item added for it is removed, unless playback is on [playingHash]
     * or claimed it. The removal runs on its own and is tried again at a later pass when it fails.
     */
    internal fun release(copy: DebridCopy, playingHash: String?): Job? {
        val id = copy.torrentId ?: return null
        added.claim(playingHash)
        inUse.remove(id)
        return scope.launch { remove(id) }
    }

    /**
     * The copy pass no longer reads [copy] and playback moved to a source that does not name its torrent, which may be
     * that copy through an add-on: it stays in the user's list.
     */
    internal fun keep(copy: DebridCopy) {
        val id = copy.torrentId ?: return
        inUse.remove(id)
        added.claim(null, id)
        Log.i(TAG, "TorBox: thumbnail copy kept, playback moved to a source that does not name its torrent (id $id)")
    }

    /** Items left from an earlier pass or run: removed, at most once every [SWEEP_EVERY_MS]. */
    internal fun sweepLeftovers(): Job? {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            lastSweep?.let { if (now - it < SWEEP_EVERY_MS) return null }
            lastSweep = now
        }
        return scope.launch {
            val wall = System.currentTimeMillis()
            giveUpOld(wall)
            val left = runCatching { records.leftovers(wall, busy()) }.getOrDefault(emptyList())
            if (left.isNotEmpty()) Log.i(TAG, "TorBox: ${left.size} thumbnail copies left from before, removing them")
            for (r in left) remove(r.torrentId)
        }
    }

    /** Every recorded item not read by a copy pass now is removed (thumbnails cleared). */
    fun removeAdded(): Job = scope.launch {
        giveUpOld(System.currentTimeMillis())
        val busy = busy()
        for (r in runCatching { records.all() }.getOrDefault(emptyList())) {
            if (r.torrentId !in busy) remove(r.torrentId)
        }
    }

    private fun giveUpOld(wallMs: Long) {
        runCatching { records.giveUp(wallMs) }.getOrDefault(emptyList()).forEach {
            Log.w(TAG, "TorBox: thumbnail copy left in your list, added more than 6 hours ago (id ${it.torrentId})")
        }
    }

    private fun busy(): Set<Int> {
        val now = SystemClock.elapsedRealtime()
        return inUse.filterValues { now - it < IN_USE_MAX_MS }.keys
    }

    private suspend fun torboxKey(): String? {
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks) return null
        return settings.torboxApiKey.trim().takeIf { it.isNotBlank() }
    }

    private suspend fun addAndOpen(
        stream: Stream, resolve: StreamClientResolve, hash: String, season: Int?, episode: Int?, title: String, apiKey: String,
    ): DebridCopy? {
        limits.refusal(title)?.let {
            Log.i(TAG, "TorBox: nothing added ($it)")
            return null
        }
        val auth = "Bearer $apiKey"
        val id = addLock.withLock {
            limits.refusal(title)?.let {
                Log.i(TAG, "TorBox: nothing added ($it)")
                return null
            }
            val cached = counted { torboxApi.checkCached(authorization = auth, body = TorboxCheckCachedRequestDto(listOf(hash))) }
            if (!cached.isSuccessful || cached.body()?.success == false) {
                stopOrSay(cached, "TorBox: cache check failed (HTTP ${cached.code()}), nothing added")
                return null
            }
            if (cached.body()?.data.orEmpty().keys.none { it.trim().lowercase() == hash }) {
                Log.i(TAG, "TorBox: not cached, nothing added")
                return null
            }
            val before = torboxList(auth, fresh = true) ?: return null
            before.items.firstOrNull { AddedCopyRecords.normal(it.hash) == hash }?.let { item ->
                return fromList(item, resolve, season, episode, apiKey)?.let { DebridCopy(it, null) }
            }
            val magnet = resolve.magnetUri?.takeIf { it.isNotBlank() } ?: DebridMagnetBuilder.fromStream(stream) ?: return null
            val c = withContext(NonCancellable) { create(auth, magnet, hash, title, before) } ?: return null
            before.items.firstOrNull { it.id.asInt() == c }?.let { item ->
                return fromList(item, resolve, season, episode, apiKey)?.let { DebridCopy(it, null) }
            }
            c
        }
        var url: String? = null
        var ours = true
        try {
            val item = readItem(id, auth)
            val record = records.of(id)
            if (item != null && record != null && !CopyRemoval.createdWhileAdding(record, TorboxTime.parse(item.createdAt))) {
                records.forget(id)
                inUse.remove(id)
                ours = false
                Log.i(TAG, "TorBox: item $id was in your list before (added ${item.createdAt ?: "at an unknown time"}), used and kept")
            }
            url = item?.let { linkFor(id, it, resolve, season, episode, apiKey) }
            if (url == null) Log.i(TAG, "TorBox: no link to the added copy (id $id)")
        } finally {
            if (url == null && ours) release(DebridCopy("", id), null)
        }
        return url?.let { DebridCopy(it, if (ours) id else null) }
    }

    /**
     * Adds the cached [magnet] and records the new item before anything else; not cancelled half way. The item id, or
     * null when nothing was added and recorded. When TorBox gives no clear answer, the list is read again to find an
     * item the add may have made.
     */
    private suspend fun create(auth: String, magnet: String, hash: String, title: String, before: ListRead): Int? {
        limits.counted(title)
        val create = try {
            counted {
                torboxApi.createTorrent(
                    authorization = auth,
                    magnet = magnet.toTextPart(),
                    addOnlyIfCached = "true".toTextPart(),
                    allowZip = "false".toTextPart(),
                )
            }
        } catch (e: ConnectionLedger.NoSlotException) {
            Log.i(TAG, "TorBox: nothing added (no connection free)")
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "TorBox: no answer to the add (${e.javaClass.simpleName}), looking in your list")
            return findAdded(auth, hash, before)
        }
        val body = create.body()
        (body?.detail ?: body?.error)?.let { Log.i(TAG, "TorBox: add answered: ${it.replace('\n', ' ').take(80)}") }
        val created = body?.takeIf { create.isSuccessful && it.success != false }?.data?.resolvedTorrentId()
        if (created == null) {
            if ((create.isSuccessful && body?.success != false) || create.code() >= 500) {
                Log.i(TAG, "TorBox: unclear answer to the add (HTTP ${create.code()}), looking in your list")
                return findAdded(auth, hash, before)
            }
            stopOrSay(create, if (create.code() == 409) "TorBox: not cached, nothing added" else "TorBox: no copy added (HTTP ${create.code()})")
            return null
        }
        return record(created, hash, before, create.headers().getDate("Date")?.time)
    }

    /** An item with [hash] that is in the list now but was not [before]: recorded as added for thumbnails. */
    private suspend fun findAdded(auth: String, hash: String, before: ListRead): Int? {
        val after = try {
            torboxList(auth, fresh = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: run {
            Log.i(TAG, "TorBox: your list could not be checked after the add")
            return null
        }
        val known = before.items.mapNotNull { it.id.asInt() }.toSet()
        val id = after.items.firstOrNull { AddedCopyRecords.normal(it.hash) == hash && it.id.asInt() !in known }?.id.asInt()
            ?: run {
                Log.i(TAG, "TorBox: nothing was added")
                return null
            }
        return record(id, hash, before, after.serverMs)
    }

    private suspend fun record(id: Int, hash: String, before: ListRead, toServerMs: Long?): Int? {
        if (before.items.any { it.id.asInt() == id }) return id
        inUse[id] = SystemClock.elapsedRealtime()
        val stored = records.add(
            AddedCopy(
                torrentId = id,
                hash = hash,
                addedAtMs = System.currentTimeMillis(),
                fromServerMs = before.serverMs?.let { it - TorboxTime.SLACK_MS } ?: 0L,
                toServerMs = toServerMs?.let { it + TorboxTime.SLACK_MS } ?: 0L,
            )
        )
        listed = null
        Log.i(TAG, "TorBox: copy added for thumbnails (cached, id $id)")
        if (!stored) {
            Log.w(TAG, "TorBox: the thumbnail copy could not be recorded, removing it now (id $id)")
            inUse.remove(id)
            remove(id)
            return null
        }
        return id
    }

    private fun stopOrSay(r: Response<*>, line: String) {
        val text = runCatching {
            (r.body() as? TorboxEnvelopeDto<*>)?.let { listOfNotNull(it.error, it.detail).joinToString(" ") }
                ?: r.errorBody()?.string()
        }.getOrNull()
        if (limits.answered(r.code(), text)) {
            Log.i(TAG, "TorBox: no more copies added this session (${limits.stoppedWhy})")
        } else {
            Log.i(TAG, line)
        }
    }

    private suspend fun remove(id: Int) {
        if (!removing.add(id)) return
        try {
            val record = records.of(id) ?: return
            if (records.claimed(record.hash, id)) {
                records.forget(id)
                Log.i(TAG, "TorBox: thumbnail copy kept, you are watching it (id $id)")
                return
            }
            val apiKey = torboxKey() ?: run {
                failed("no TorBox key in the app")
                return
            }
            val auth = "Bearer $apiKey"
            var tries = 0
            while (true) {
                try {
                    removeOnce(record, auth)
                    return
                } catch (e: ConnectionLedger.NoSlotException) {
                    if (++tries >= REMOVE_TRIES) {
                        failed("no connection free")
                        return
                    }
                    delay(REMOVE_RETRY_MS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed(e.javaClass.simpleName)
                    return
                }
            }
        } finally {
            removing.remove(id)
        }
    }

    private suspend fun removeOnce(record: AddedCopy, auth: String) {
        val id = record.torrentId
        val read = counted(ConnectionLedger.SLOT_WAIT_MS) { torboxApi.getTorrent(authorization = auth, id = id, bypassCache = true) }
        val body = read.body()
        val found = when {
            read.code() == 404 -> false
            !read.isSuccessful || body == null || body.success == false -> {
                failed("HTTP ${read.code()}")
                return
            }
            else -> body.data != null
        }
        val item = body?.data
        when (CopyRemoval.decide(records.of(id), records.claimed(record.hash, id), found, item?.hash, TorboxTime.parse(item?.createdAt))) {
            CopyRemoval.DELETE -> {
                val r = counted(ConnectionLedger.SLOT_WAIT_MS) {
                    val d = records.beginDelete(record) ?: return@counted null
                    try {
                        torboxApi.controlTorrent(auth, TorboxControlTorrentRequestDto(torrentId = id, operation = "delete"))
                    } finally {
                        records.endDelete(record, d)
                    }
                }
                listed = null
                when {
                    r == null -> {
                        records.forget(id)
                        Log.i(TAG, "TorBox: thumbnail copy kept, you are watching it (id $id)")
                    }
                    r.isSuccessful && r.body()?.success != false -> {
                        records.forget(id)
                        Log.i(TAG, "TorBox: thumbnail copy removed from your list (id $id)")
                    }
                    else -> failed("HTTP ${r.code()}")
                }
            }
            CopyRemoval.WATCHING -> {
                records.forget(id)
                Log.i(TAG, "TorBox: thumbnail copy kept, you are watching it (id $id)")
            }
            CopyRemoval.CHANGED -> {
                records.forget(id)
                Log.i(TAG, "TorBox: item $id is no longer the thumbnail copy, left alone")
            }
            CopyRemoval.OLDER -> {
                records.forget(id)
                Log.i(TAG, "TorBox: item $id could not be confirmed as the thumbnail copy (added ${item?.createdAt ?: "at an unknown time"}), left in your list")
            }
            CopyRemoval.GONE -> {
                records.forget(id)
                Log.i(TAG, "TorBox: thumbnail copy already gone from your list (id $id)")
            }
            CopyRemoval.NOT_OURS -> Unit
        }
    }

    private fun failed(what: String) {
        Log.i(TAG, "TorBox: removing the thumbnail copy failed ($what), retry later")
    }

    private suspend fun fromList(
        item: TorboxCloudItemDto, resolve: StreamClientResolve, season: Int?, episode: Int?, apiKey: String,
    ): String? {
        val id = item.id.asInt() ?: return null
        if (!finished(item)) {
            Log.i(TAG, "TorBox: the copy in your list is not finished downloading")
            return null
        }
        val torrent = readItem(id, "Bearer $apiKey") ?: return null
        return linkFor(id, torrent, resolve, season, episode, apiKey)
    }

    private suspend fun readItem(id: Int, auth: String): TorboxTorrentDataDto? {
        val torrent = counted { torboxApi.getTorrent(authorization = auth, id = id, bypassCache = true) }
        return torrent.body()?.data?.takeIf { torrent.isSuccessful }
    }

    private suspend fun linkFor(
        id: Int, torrent: TorboxTorrentDataDto, resolve: StreamClientResolve, season: Int?, episode: Int?, apiKey: String,
    ): String? {
        val file = torboxFileSelector.selectFile(torrent.files.orEmpty(), resolve, season, episode) ?: return null
        val fileId = file.id ?: return null
        val link = counted {
            torboxApi.requestDownloadLink(authorization = "Bearer $apiKey", token = apiKey, torrentId = id, fileId = fileId)
        }
        return link.body()?.data?.takeIf { link.isSuccessful && it.isNotBlank() }
    }

    /**
     * Every page of the user's TorBox list. [fresh] skips the app's short memory and TorBox's own cache. Null when any
     * page cannot be read or the list is too long: nothing is added on an incomplete list.
     */
    private suspend fun torboxList(auth: String, fresh: Boolean): ListRead? = listLock.withLock {
        val now = SystemClock.elapsedRealtime()
        if (!fresh) listed?.takeIf { now - it.first < LIST_TTL_MS }?.let { return@withLock it.second }
        val items = ArrayList<TorboxCloudItemDto>()
        var serverMs: Long? = null
        for (page in 0 until LIST_MAX_PAGES) {
            val r = counted { torboxApi.listTorrents(auth, bypassCache = fresh, offset = page * LIST_PAGE, limit = LIST_PAGE) }
            if (page == 0) serverMs = r.headers().getDate("Date")?.time
            if (r.code() == 404) return@withLock ListRead(items, serverMs).also { listed = now to it }
            if (!r.isSuccessful || r.body()?.success == false) {
                Log.i(TAG, "TorBox: your list could not be read (HTTP ${r.code()})")
                return@withLock null
            }
            val data = r.body()?.data.orEmpty()
            items += data
            if (data.size < LIST_PAGE) return@withLock ListRead(items, serverMs).also { listed = now to it }
        }
        Log.i(TAG, "TorBox: your list is too long to read in full, nothing added")
        null
    }

    /** A TorBox request, counted in the connection ledger like the copy's own reads. */
    private suspend fun <T> counted(waitMs: Long = ConnectionLedger.START_SLOT_WAIT_MS, block: suspend () -> T): T {
        val slot = withContext(Dispatchers.IO) { ConnectionLedger.app.acquire(TORBOX_API, waitMs) }
        try {
            return block()
        } finally {
            slot.close()
        }
    }

    private fun hashOf(stream: Stream): String? =
        AddedCopyRecords.normal(stream.clientResolve?.infoHash ?: stream.getEffectiveInfoHash())
            ?: AddedCopyRecords.normal(CopyChoice.linkHash(stream.getStreamUrl(), streamText(stream)))

    private fun resolveOf(stream: Stream, season: Int?, episode: Int?, service: String): StreamClientResolve =
        stream.clientResolve?.takeIf { stream.isDirectDebrid() } ?: StreamClientResolve(
            type = "torrent",
            infoHash = stream.getEffectiveInfoHash(),
            fileIdx = stream.getEffectiveFileIdx(),
            magnetUri = DebridMagnetBuilder.fromStream(stream),
            sources = stream.sources,
            torrentName = null,
            filename = stream.behaviorHints?.filename,
            mediaType = null,
            mediaId = null,
            mediaOnlyId = null,
            title = stream.title ?: stream.name,
            season = season,
            episode = episode,
            service = service,
            serviceIndex = null,
            serviceExtension = null,
            isCached = true,
            stream = null,
        )

    private fun finished(item: TorboxCloudItemDto): Boolean {
        val state = listOfNotNull(item.downloadState, item.state, item.status).joinToString(" ").lowercase()
        if (listOf("complet", "cached", "uploading").any { it in state }) return true
        if (state.isNotBlank()) return false
        val progress = item.downloadProgress ?: item.progress ?: return false
        return progress == 1.0 || progress == 100.0
    }

    private fun String.toTextPart() = toRequestBody("text/plain".toMediaType())

    private fun Any?.asInt(): Int? = when (this) {
        is Number -> toInt()
        is String -> trim().toIntOrNull()
        else -> null
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ThumbnailCopyDebridEntryPoint {
    fun thumbnailCopyDebrid(): ThumbnailCopyDebrid
}

internal fun streamText(stream: Stream): String =
    listOfNotNull(stream.name, stream.title, stream.description).joinToString(" ")
