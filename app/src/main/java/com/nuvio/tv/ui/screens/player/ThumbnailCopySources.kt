package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.debrid.CopyRoute
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.DirectDebridPlayableResult
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.debrid.DirectDebridStreamFilter
import com.nuvio.tv.core.debrid.ThumbnailCopyDebrid
import com.nuvio.tv.core.debrid.streamText
import com.nuvio.tv.core.player.thumbnail.CopyChoice
import com.nuvio.tv.core.player.thumbnail.CopyCodec
import com.nuvio.tv.core.player.thumbnail.CopyKind
import com.nuvio.tv.core.player.thumbnail.CopyLink
import com.nuvio.tv.core.player.thumbnail.CopyOffer
import com.nuvio.tv.core.player.thumbnail.CopyOffers
import com.nuvio.tv.core.player.thumbnail.CopySources
import com.nuvio.tv.core.player.thumbnail.MediaServerThumbs
import com.nuvio.tv.core.player.thumbnail.MkvIndexReader
import com.nuvio.tv.core.player.thumbnail.MultiRangeSupport
import com.nuvio.tv.core.player.thumbnail.thumbnailHttpClient
import com.nuvio.tv.data.mediaserver.ServerPlayMethod
import com.nuvio.tv.data.mediaserver.ServerPlayback
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamVisualTag
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** Where [ThumbnailCopySources] reads its source list: the player's, or the stream list's before the pick. */
internal interface CopyListHost {
    val context: Context
    val serverPlayback: ServerPlayback
    val directDebridResolver: DirectDebridResolver
    val debridSettings: Flow<DebridSettings>

    suspend fun loadNow() = Unit

    /** The list with the playing entry; null while it is not loaded. */
    suspend fun listed(): CopyListed?

    /** The torrent playback is on now, for a TorBox copy given back. */
    fun playingHash(): String?

    /**
     * A TorBox copy added for thumbnails stays in the user's list when given back: playback may read [copy]'s torrent
     * through a link that does not name it. [listedPlayingUrl]: the playing link when the list was read.
     */
    fun keepsAdded(copy: CopyOffer, listedPlayingUrl: String?): Boolean
}

internal class CopyListed(
    val streams: List<Stream>, val playingIndex: Int, val season: Int?, val episode: Int?, val playingText: String,
    val serverCopies: Boolean, val title: String, val playingHash: String?, val playingUrl: String?,
)

/** The player's source list. */
internal class PlayerCopyList(private val controller: PlayerRuntimeController) : CopyListHost {
    override val context: Context get() = controller.context
    override val serverPlayback: ServerPlayback get() = controller.serverPlayback
    override val directDebridResolver: DirectDebridResolver get() = controller.directDebridResolver
    override val debridSettings: Flow<DebridSettings> get() = controller.debridSettingsDataStore.settings

    override suspend fun loadNow() {
        val fromMemory = withContext(Dispatchers.Main) {
            controller.loadSourceStreams(forceRefresh = false)
            controller.sourceStreamsJob?.takeIf { controller.sourceStreamsFromMemory }
        }
        // A list taken over from the stream screen is ready within moments: waited for, not polled.
        fromMemory?.let { withTimeoutOrNull(MEMORY_LIST_WAIT_MS) { it.join() } }
    }

    private companion object {
        const val MEMORY_LIST_WAIT_MS = 2_000L
    }

    /** Main thread: the playing stream plays from Usenet, by its link or by its add-on's marking. */
    fun playingIsUsenet(url: String?): Boolean {
        if (SlowStartServer.isUsenet(url)) return true
        val ui = controller._uiState.value
        val streams = ui.sourceAllStreams
        // After an automatic switch the playing entry is found by its link: the list position may still be the old one.
        if (!url.isNullOrBlank() && streams.any { s ->
                s.getStreamUrl() == url && s.clientResolve?.let { CopyChoice.usenetMarked(it.type, it.service) } == true
            }) return true
        val i = findCurrentStreamIndex(
            streams = streams,
            currentStreamInfoHash = ui.currentStreamInfoHash,
            currentStreamFileIdx = ui.currentStreamFileIdx,
            currentStreamAddonName = ui.currentStreamAddonName,
            currentStreamUrl = ui.currentStreamUrl,
            currentStreamName = ui.currentStreamName
        )
        val resolve = streams.getOrNull(i)?.clientResolve ?: return false
        return CopyChoice.usenetMarked(resolve.type, resolve.service)
    }

    override suspend fun listed(): CopyListed? = withContext(Dispatchers.Main) {
        val key = controller.currentSourceRequestKey() ?: return@withContext null
        if (controller.sourceStreamsCacheRequestKey != key ||
            !(controller.sourceStreamsFetchCompleted || controller.sourceCopyListEarly)) {
            return@withContext null
        }
        val ui = controller._uiState.value
        val streams = copyCandidates(ui.sourceAllStreams, controller.sourceThumbnailCopyStreams)
        val request = controller.currentSourceStreamsRequest()
        val playing = findCurrentStreamIndex(
            streams = streams,
            currentStreamInfoHash = ui.currentStreamInfoHash,
            currentStreamFileIdx = ui.currentStreamFileIdx,
            currentStreamAddonName = ui.currentStreamAddonName,
            currentStreamUrl = ui.currentStreamUrl,
            currentStreamName = ui.currentStreamName
        ).takeIf { it >= 0 }
        val serverSession = controller.serverPlayback.session(controller.currentStreamUrl)
        CopyListed(
            streams, playing ?: indexOfServerTarget(streams, serverSession?.target), request?.season, request?.episode,
            listOfNotNull(ui.currentStreamName, controller.currentFilename, controller.currentStreamDescription).joinToString(" "),
            serverCopies = serverSession == null || serverSession.playMethod == ServerPlayMethod.DIRECT_PLAY,
            title = key,
            playingHash = ui.currentStreamInfoHash ?: playing?.let { streams.getOrNull(it)?.getEffectiveInfoHash() }
                ?: playing?.let { streams.getOrNull(it) }?.let { CopyChoice.linkHash(it.getStreamUrl(), streamText(it)) },
            playingUrl = ui.currentStreamUrl,
        )
    }

    override fun playingHash(): String? = controller._uiState.value.currentStreamInfoHash

    override fun keepsAdded(copy: CopyOffer, listedPlayingUrl: String?): Boolean {
        val ui = controller._uiState.value
        return ui.currentStreamInfoHash == null && ui.currentStreamUrl != listedPlayingUrl
    }
}

/**
 * The player's source list as copies for 4K thumbnails, failing closed: media-server versions (only while the playing
 * server stream is direct play; one request at a time from a server away from the home network), Usenet links streamed by a local
 * AIOStreams, plain file links, app debrid entries or add-on sources naming their torrent, through the app's own
 * keys: TorBox (from the user's list, or a cached copy added and removed again) and Premiumize (direct download),
 * and otherwise cached debrid copies through their resolve links or the app's resolver, which may leave the title in
 * that debrid account ([CopyChoice.linkRejection]).
 */
internal class ThumbnailCopySources(
    private val host: CopyListHost,
    private val debrid: ThumbnailCopyDebrid,
) : CopySources {
    constructor(controller: PlayerRuntimeController, debrid: ThumbnailCopyDebrid) : this(PlayerCopyList(controller), debrid)

    private companion object {
        const val TAG = "ThumbCopy"
        /** A link followed ahead is used within this long, else followed again. */
        const val WARM_MS = 10L * 60 * 1000
        const val WARM_WAIT_MS = 20_000L
        val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "avi", "ts", "m2ts", "m3u8", "webm", "mov", "wmv", "iso")
        val HDR_TAGS = setOf(
            DebridStreamVisualTag.HDR_DV, DebridStreamVisualTag.DV_ONLY, DebridStreamVisualTag.HDR_ONLY,
            DebridStreamVisualTag.HDR10_PLUS, DebridStreamVisualTag.HDR10, DebridStreamVisualTag.DV,
            DebridStreamVisualTag.HDR, DebridStreamVisualTag.HLG,
        )
        val H264_TEXT = Regex("h\\.?\\s?264|avc", RegexOption.IGNORE_CASE)

        /** Titles a Usenet copy was opened for in this run: one indexer grab per title at most. */
        val usenetTried: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }

    /** Main thread: the playing stream plays from Usenet, by its link or by its add-on's marking. */
    fun playingIsUsenet(url: String?): Boolean =
        (host as? PlayerCopyList)?.playingIsUsenet(url) ?: SlowStartServer.isUsenet(url)

    private val byId = ConcurrentHashMap<String, Stream>()
    @Volatile private var season: Int? = null
    @Volatile private var episode: Int? = null
    @Volatile private var title = ""
    @Volatile private var playingUrl: String? = null

    override suspend fun loadNow() = host.loadNow()

    override suspend fun offers(): CopyOffers? {
        val snap = host.listed() ?: return null
        season = snap.season
        episode = snap.episode
        title = snap.title
        playingUrl = snap.playingUrl
        debrid.notePlaying(snap.playingHash)
        debrid.sweepLeftovers()
        val settings = host.debridSettings.first()
        val ids = HashMap<String, Stream>()
        val leftOut = LinkedHashMap<String, Int>()
        fun leaveOut(why: String) {
            leftOut[why] = (leftOut[why] ?: 0) + 1
        }
        var torboxListed: Set<String>? = null
        var torboxRead = false
        val offers = snap.streams.mapIndexedNotNull { i, stream ->
            if (i == snap.playingIndex) return@mapIndexedNotNull null
            if (!SourceFailoverSelection.isAutoFailoverPlayable(stream, snap.season, snap.episode)) return@mapIndexedNotNull null
            val route = if (stream.serverTarget == null) debrid.routeOf(stream, settings) else null
            val kind = when {
                stream.serverTarget != null -> {
                    if (!snap.serverCopies) {
                        leaveOut("server version while the server transcodes playback")
                        return@mapIndexedNotNull null
                    }
                    if (MediaServerThumbs.copyLanes(host.serverPlayback.serverAddress(stream.serverTarget)) == 0) {
                        leaveOut("server version without a server address")
                        return@mapIndexedNotNull null
                    }
                    CopyKind.SERVER
                }
                route is CopyRoute.LeftOut -> {
                    leaveOut(route.why)
                    return@mapIndexedNotNull null
                }
                route == CopyRoute.Torbox -> {
                    if (!torboxRead) {
                        torboxRead = true
                        torboxListed = debrid.torboxListHashes(settings)
                    }
                    if (debrid.inTorboxList(stream, torboxListed) == true) CopyKind.DEBRID else CopyKind.TORBOX_ADD
                }
                route == CopyRoute.Premiumize -> CopyKind.PREMIUMIZE
                route == CopyRoute.Resolve -> CopyKind.RESOLVE
                else -> {
                    val url = stream.getStreamUrl() ?: return@mapIndexedNotNull null
                    val text = streamText(stream)
                    CopyChoice.linkRejection(url, text, cachedOf(stream))?.let {
                        leaveOut(it)
                        return@mapIndexedNotNull null
                    }
                    CopyChoice.linkKind(url, text)
                }
            }
            val id = "s$i"
            ids[id] = stream
            val remote = stream.serverTarget?.let {
                MediaServerThumbs.copyLanes(host.serverPlayback.serverAddress(it)) != Int.MAX_VALUE
            } == true
            offerOf(id, stream, kind, DirectDebridStreamFilter.facts(stream, settings), remote)
        }
        byId.keys.retainAll(ids.keys)
        byId.putAll(ids)
        if (leftOut.isNotEmpty()) {
            Log.i(TAG, "left out: " + leftOut.entries.joinToString("; ") { "${it.value} x ${it.key}" })
        }
        val playing = snap.streams.getOrNull(snap.playingIndex)?.let { stream ->
            val kind = when {
                stream.serverTarget != null -> CopyKind.SERVER
                else -> when (debrid.routeOf(stream, settings)) {
                    CopyRoute.Torbox -> CopyKind.DEBRID
                    CopyRoute.Premiumize -> CopyKind.PREMIUMIZE
                    CopyRoute.Resolve -> CopyKind.RESOLVE
                    else -> stream.getStreamUrl()?.let { CopyChoice.linkKind(it, streamText(stream)) } ?: CopyKind.LINK
                }
            }
            val remote = stream.serverTarget?.let {
                MediaServerThumbs.copyLanes(host.serverPlayback.serverAddress(it)) != Int.MAX_VALUE
            } == true
            offerOf("playing", stream, kind, DirectDebridStreamFilter.facts(stream, settings), remote)
        }
        return CopyOffers(snap.playingText, offers, playingTranscoded = !snap.serverCopies, playing = playing)
    }

    private fun offerOf(
        id: String, stream: Stream, kind: CopyKind, facts: DirectDebridStreamFilter.StreamFacts, remote: Boolean,
    ): CopyOffer {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        val parsed = raw?.parsed
        val fileName = stream.behaviorHints?.filename ?: resolve?.filename ?: raw?.filename
        val text = listOfNotNull(
            stream.name, stream.title, stream.description, fileName, raw?.torrentName, parsed?.edition,
            "extended".takeIf { parsed?.extended == true }, "unrated".takeIf { parsed?.unrated == true },
            "remastered".takeIf { parsed?.remastered == true },
        ).joinToString(" ")
        val codec = when (facts.encode) {
            DebridStreamEncode.AVC -> CopyCodec.H264
            DebridStreamEncode.HEVC -> CopyCodec.HEVC
            DebridStreamEncode.UNKNOWN -> if (H264_TEXT.containsMatchIn(text)) CopyCodec.H264 else CopyCodec.UNKNOWN
            else -> CopyCodec.OTHER
        }
        val container = CopyChoice.containerOf(fileName)
            ?: CopyChoice.containerOf(stream.getStreamUrl())?.takeIf { it in VIDEO_EXTENSIONS }
        val label = (stream.addonName + ": " + (stream.name?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: "?"))
            .take(60)
        return CopyOffer(
            id = id,
            label = label,
            kind = kind,
            height = facts.resolution.value,
            codec = codec,
            hdr = facts.visualTags.any { it in HDR_TAGS },
            web = facts.quality == DebridStreamQuality.WEB_DL || facts.quality == DebridStreamQuality.WEBRIP,
            sizeBytes = facts.size ?: stream.behaviorHints?.videoSize,
            container = container,
            durationMs = stream.durationMs ?: CopyChoice.parsedDurationMs(parsed?.duration),
            text = text,
            remote = remote,
            source = stream.addonName,
        )
    }

    /** The list entry behind [offer]. */
    fun streamOf(offer: CopyOffer): Stream? = byId[offer.id]

    override fun multiRange(offer: CopyOffer): Boolean? {
        val stream = byId[offer.id] ?: return null
        return when (offer.kind) {
            CopyKind.LINK -> stream.getStreamUrl()?.let { MultiRangeSupport.app(host.context).known(it) }
            CopyKind.RESOLVE -> if (stream.isDirectDebrid()) {
                when (stream.clientResolve?.service?.let { DebridProviders.byId(it)?.id }) {
                    DebridProviders.TORBOX_ID -> true
                    DebridProviders.REAL_DEBRID_ID, DebridProviders.PREMIUMIZE_ID -> false
                    else -> null
                }
            } else {
                CopyChoice.resolveBatched(stream.getStreamUrl().orEmpty(), streamText(stream))
            }
            else -> null
        }
    }

    override suspend fun open(offer: CopyOffer): CopyLink? {
        val stream = byId[offer.id] ?: return null
        return when (offer.kind) {
            CopyKind.SERVER -> {
                val target = stream.serverTarget ?: return null
                host.serverPlayback.staticUrl(target)?.let {
                    val lanes = MediaServerThumbs.copyLanes(it)
                    CopyLink(it, emptyMap(), if (lanes == 1) "a media server away from home" else "the media server", lanes)
                }
            }
            CopyKind.LINK -> stream.getStreamUrl()?.let {
                val w = warmed(it)
                CopyLink(w?.url ?: it, stream.behaviorHints?.proxyHeaders?.request.orEmpty(), "a ready link",
                    prewarmed = w != null, head = w?.head, length = w?.length ?: -1L)
            }
            CopyKind.USENET -> {
                if (!usenetTried.add(title)) {
                    Log.i(TAG, "Usenet: a copy was tried for this title already, no second grab")
                    return null
                }
                stream.getStreamUrl()?.let {
                    CopyLink(it, stream.behaviorHints?.proxyHeaders?.request.orEmpty(), "a Usenet link")
                }
            }
            CopyKind.DEBRID, CopyKind.TORBOX_ADD -> debrid.openTorbox(stream, season, episode, title)?.let { c ->
                if (c.torrentId == null) {
                    CopyLink(c.url, emptyMap(), "your TorBox list")
                } else {
                    CopyLink(c.url, emptyMap(), "a TorBox copy added for thumbnails") {
                        if (host.keepsAdded(offer, playingUrl)) debrid.keep(c) else debrid.release(c, host.playingHash())
                    }
                }
            }
            CopyKind.PREMIUMIZE -> debrid.openPremiumize(stream, season, episode)?.let {
                CopyLink(it, emptyMap(), "a Premiumize direct download")
            }
            CopyKind.RESOLVE -> if (stream.isDirectDebrid()) {
                val r = host.directDebridResolver.resolveToPlayableStream(stream, season, episode)
                (r as? DirectDebridPlayableResult.Success)?.stream?.getStreamUrl()?.let {
                    CopyLink(it, emptyMap(), "your debrid service")
                }
            } else {
                stream.getStreamUrl()?.let {
                    val w = warmed(it)
                    CopyLink(w?.url ?: it, stream.behaviorHints?.proxyHeaders?.request.orEmpty(), "the add-on's debrid link",
                        prewarmed = w != null, head = w?.head, length = w?.length ?: -1L)
                }
            }
        }
    }

    /** [head]: the file's first bytes from the same answer, with the [length] it named. */
    private class Warm(val url: String, val atMs: Long, val head: ByteArray?, val length: Long)

    /** Links whose redirects were followed ahead of the pass, by the source's own link. */
    private val warm = ConcurrentHashMap<String, Warm>()

    /** Links being followed ahead right now: the pass waits for that answer instead of resolving the link twice. */
    private val warming = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private suspend fun warmed(url: String): Warm? {
        warming[url]?.let { withTimeoutOrNull(WARM_WAIT_MS) { it.await() } }
        return warm.remove(url)?.takeIf { SystemClock.elapsedRealtime() - it.atMs < WARM_MS }
    }

    override suspend fun prewarm(offer: CopyOffer): Long? {
        if (offer.kind != CopyKind.LINK && offer.kind != CopyKind.RESOLVE) return null
        val stream = byId[offer.id] ?: return null
        if (stream.isDirectDebrid()) return null
        val url = stream.getStreamUrl() ?: return null
        val headers = stream.behaviorHints?.proxyHeaders?.request.orEmpty()
        val done = CompletableDeferred<Unit>()
        if (warming.putIfAbsent(url, done) != null) return null
        try {
            val t0 = SystemClock.elapsedRealtime()
            val got = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder().url(url)
                        .header("Range", "bytes=0-${MkvIndexReader.HEAD_BYTES - 1}")
                        .header("Accept-Encoding", "identity")
                        .build()
                    thumbnailHttpClient(headers).newCall(request).execute().use { r ->
                        if (r.code != 206) return@use null
                        val length = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                        val head = runCatching { r.body?.source()?.let { src ->
                            src.request(MkvIndexReader.HEAD_BYTES.toLong())
                            src.buffer.readByteArray(minOf(src.buffer.size, MkvIndexReader.HEAD_BYTES.toLong()))
                        } }.getOrNull()
                        Warm(r.request.url.toString(), SystemClock.elapsedRealtime(), head, length)
                    }
                }.getOrNull()
            } ?: return null
            if (got.url != url || got.head != null) warm[url] = got
            return SystemClock.elapsedRealtime() - t0
        } finally {
            warming.remove(url, done)
            done.complete(Unit)
        }
    }

    private fun cachedOf(stream: Stream): Boolean? = when {
        stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED -> true
        else -> stream.clientResolve?.isCached
    }
}

/**
 * The source list unchanged (its positions stay valid), then the app debrid streams that only the resolution rules
 * keep off it: offered as copies, never listed or played.
 */
internal fun copyCandidates(listed: List<Stream>, resolutionOnly: List<Stream>): List<Stream> =
    if (resolutionOnly.isEmpty()) listed else listed + resolutionOnly
