package com.nuvio.tv.ui.screens.stream

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.debrid.ThumbnailCopyDebrid
import com.nuvio.tv.core.player.thumbnail.ConnectionLedger
import com.nuvio.tv.core.player.thumbnail.CopyFirst
import com.nuvio.tv.core.player.thumbnail.CopyHeadStart
import com.nuvio.tv.core.player.thumbnail.CopyOffer
import com.nuvio.tv.core.player.thumbnail.CopyPrePick
import com.nuvio.tv.core.player.thumbnail.CopySourceBooks
import com.nuvio.tv.core.player.thumbnail.MemoryGovernor
import com.nuvio.tv.core.player.thumbnail.SeekThumbnailPreferences
import com.nuvio.tv.core.player.thumbnail.ThumbStore
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.mediaserver.ServerPlayback
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.CopyListHost
import com.nuvio.tv.ui.screens.player.CopyListed
import com.nuvio.tv.ui.screens.player.SourceFailoverSelection
import com.nuvio.tv.ui.screens.player.ThumbnailCopySources
import com.nuvio.tv.ui.screens.player.streamFilenameFromUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The stream list as it was when it finished loading, with the stream taken as the one that plays. */
internal class PrePickCopyList(
    override val context: Context,
    override val serverPlayback: ServerPlayback,
    override val directDebridResolver: DirectDebridResolver,
    override val debridSettings: Flow<DebridSettings>,
    val streams: List<Stream>,
    private val season: Int?,
    private val episode: Int?,
    private val title: String,
) : CopyListHost {
    @Volatile var playingIndex = -1
    @Volatile private var picked = false
    @Volatile private var pickedHash: String? = null
    @Volatile var pickedOffer: CopyOffer? = null

    fun pick(index: Int, hash: String?) {
        playingIndex = index
        pickedHash = hash
        picked = true
    }

    override suspend fun listed(): CopyListed {
        val i = playingIndex
        val s = streams.getOrNull(i)
        return CopyListed(
            streams, i, season, episode, s?.let(::playingTextOf).orEmpty(), serverCopies = true, title = title,
            playingHash = pickedHash.takeIf { picked }, playingUrl = s?.getStreamUrl(),
        )
    }

    override fun playingHash(): String? = pickedHash.takeIf { picked }

    override fun keepsAdded(copy: CopyOffer, listedPlayingUrl: String?): Boolean =
        CopyPrePick.keepsAdded(picked, pickedHash, pickedOffer, copy)

    /** Where [stream] is on the list: the same entry, or one with the same link, torrent, file, add-on and name. */
    fun indexOf(stream: Stream): Int {
        streams.indexOfFirst { it === stream }.takeIf { it >= 0 }?.let { return it }
        return streams.indexOfFirst {
            it.getStreamUrl() == stream.getStreamUrl() && it.getEffectiveInfoHash() == stream.getEffectiveInfoHash() &&
                it.getEffectiveFileIdx() == stream.getEffectiveFileIdx() && it.addonName == stream.addonName &&
                it.serverTarget == stream.serverTarget && it.name == stream.name
        }
    }

    private companion object {
        fun playingTextOf(s: Stream): String = listOfNotNull(
            s.name, s.behaviorHints?.filename ?: s.getStreamUrl()?.let(::streamFilenameFromUrl), s.description,
        ).joinToString(" ")
    }
}

/**
 * The stream screen's side of [CopyPrePick]: once per title, when the list finished loading and is still shown
 * [CopyPrePick.LIST_DELAY_MS] later, the lighter copy is chosen and opened; at the pick it is checked against the stream
 * picked; leaving the list without a pick gives it back.
 */
internal class StreamCopyPrePick(
    private val context: Context,
    private val debrid: ThumbnailCopyDebrid,
    private val serverPlayback: ServerPlayback,
    private val resolver: DirectDebridResolver,
    private val debridSettings: Flow<DebridSettings>,
    private val identity: String,
    private val title: String,
    private val season: Int?,
    private val episode: Int?,
) {
    private companion object {
        const val TAG = "ThumbCopy"
    }

    private var job: Job? = null
    private var tried = false
    @Volatile private var pickMade = false
    @Volatile private var list: PrePickCopyList? = null
    @Volatile private var sources: ThumbnailCopySources? = null

    /** Main thread: the list finished loading. [shown]: it is up for the user to pick from. */
    fun listReady(
        scope: CoroutineScope, streams: () -> List<Stream>, shown: () -> Boolean, settings: suspend () -> PlayerSettings,
    ) {
        if (tried || pickMade || job?.isActive == true || identity.isBlank()) return
        job = scope.launch {
            delay(CopyPrePick.LIST_DELAY_MS)
            if (pickMade || !shown()) return@launch
            tried = true
            val all = streams()
            val player = settings()
            try {
                withContext(Dispatchers.IO) { start(all, player) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "copy first: not opened while the stream list is shown (${t.message ?: t.javaClass.simpleName})")
            }
        }
    }

    private suspend fun start(streams: List<Stream>, player: PlayerSettings) {
        val starts = CopyPrePick.starts(
            prepare = SeekThumbnailPreferences.prepareMode(context),
            mode = SeekThumbnailPreferences.mode(context),
            exactIdentity = identity.isNotBlank(),
            internalExoPlayer = player.playerPreference != PlayerPreference.EXTERNAL &&
                player.internalPlayerEngine == InternalPlayerEngine.EXOPLAYER,
            listShown = streams.isNotEmpty(),
            picked = pickMade,
        )
        if (!starts) return
        MemoryGovernor.init(context)
        val list = PrePickCopyList(context, serverPlayback, resolver, debridSettings, streams, season, episode, title)
        val sources = ThumbnailCopySources(list, debrid)
        val all = sources.offers() ?: return
        val assumed = CopyPrePick.assumedPlaying(all.offers)
        val assumedStream = assumed?.let { sources.streamOf(it) }
        if (assumed == null || assumedStream == null) {
            Log.i(TAG, "copy first: no 4K stream on the list, the copy is opened at the pick")
            return
        }
        val size = assumed.sizeBytes?.takeIf { it > 0L }
        if (size != null && runCatching { ThumbStore.isMarkedComplete(context, identity, size) }.getOrDefault(false)) return
        list.playingIndex = list.indexOf(assumedStream)
        if (pickMade) return
        val head = CopyHeadStart(identity, beforePick = true)
        val book = runCatching { CopySourceBooks.app(context) }.getOrNull()
        Log.i(TAG, "copy first: opening the copy while the stream list is shown (${assumed.label} taken as the one that plays)")
        ConnectionLedger.app.noteNothingPlays(true)
        CopyPrePick.hold(identity, head) { scope -> head.start(scope, sources, book, size) }
        this.list = list
        this.sources = sources
    }

    /** Main thread: [stream] was picked. */
    fun picked(stream: Stream) {
        if (pickMade) return
        pickMade = true
        job?.cancel()
        ConnectionLedger.app.noteNothingPlays(false)
        val list = list ?: return
        val sources = sources ?: return
        val index = list.indexOf(stream)
        val torrent = stream.isTorrent()
        val usenet = SourceFailoverSelection.isUsenet(stream)
        val hash = stream.getEffectiveInfoHash()
        val at = SystemClock.elapsedRealtime()
        CopyPrePick.picked(identity) fits@{ head ->
            list.pick(index, hash)
            val offers = sources.offers() ?: return@fits false
            list.pickedOffer = offers.playing
            if (!CopyPrePick.pickUsable(index >= 0, torrent, usenet, offers.playing?.remote == true)) {
                return@fits false
            }
            val chosen = head.chosenOffer()
            if (head.prereadFailed) {
                Log.i(TAG, "copy first: the header and index of ${chosen?.label} were not read ahead, the copy is opened again at the pick")
                return@fits false
            }
            val verdicts = CopyFirst.headStartVerdicts(offers, null) { sources.multiRange(it) }
            if (!CopyPrePick.fits(chosen, verdicts)) return@fits false
            if (head.backup?.prereadFailed == true || !CopyPrePick.fits(head.backup?.offer, verdicts)) head.dropBackup()
            head.repicked(at, offers)
            Log.i(TAG, "copy first: ${chosen?.label} fits the stream picked, handed to the player's thumbnails")
            true
        }
    }

    /** The stream screen is gone without a pick: the copy is given back. */
    fun left() {
        job?.cancel()
        if (!pickMade) {
            ConnectionLedger.app.noteNothingPlays(false)
            CopyPrePick.leave(identity)
        }
    }
}
