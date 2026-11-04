package com.nuvio.tv.core.party

import com.nuvio.tv.core.party.nuvio.ActiveParty
import com.nuvio.tv.core.party.nuvio.PartyCorrection
import com.nuvio.tv.core.party.nuvio.PartyError
import com.nuvio.tv.core.party.nuvio.PartyPlayback
import com.nuvio.tv.core.party.nuvio.PartySource
import com.nuvio.tv.core.party.nuvio.PartySync
import com.nuvio.tv.core.party.nuvio.partyMediaKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import com.nuvio.tv.core.party.nuvio.PartyMedia as NuvioMedia
import com.nuvio.tv.core.party.nuvio.PartyMemberStatus as NuvioStatus

/** What [NuvioPartySession] needs from Nuvio's party service. Results arrive on the main thread. */
interface NuvioPartyLink {
    val party: ActiveParty?
    val playback: PartyPlayback?
    fun nowMs(): Long
    fun create(media: NuvioMedia, done: (PartyError?) -> Unit)
    fun join(code: String, done: (PartyError?) -> Unit)
    fun leave()
    fun end()
    fun setHostOnly(enabled: Boolean)
    fun publishPlayback(paused: Boolean, positionMs: Long, rate: Float)
    fun publishMedia(media: NuvioMedia, positionMs: Long)
    fun setLocalStatus(status: NuvioStatus, mediaKey: String?, behindMs: Long?)
}

/**
 * A party with people on the Nuvio apps, held on Nuvio's server. The server keeps the newest play state; this
 * device follows it with the same rules as an Arc party: no speed change while the audio leaves as a bitstream.
 * Everything here runs on the main thread; [tick] is expected about ten times a second.
 */
class NuvioPartySession(
    private val link: NuvioPartyLink,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
) : PartyEngine {
    private val _state = MutableStateFlow(PartyState(withNuvioApps = true))
    override val state: StateFlow<PartyState> = _state.asStateFlow()

    private val _mediaRequest = MutableStateFlow<PartyMedia?>(null)
    override val mediaRequest: StateFlow<PartyMedia?> = _mediaRequest.asStateFlow()

    private val _error = MutableStateFlow<PartyError?>(null)

    /** Why the last start or join did not work. Cleared by the next attempt. */
    val error: StateFlow<PartyError?> = _error.asStateFlow()

    private var player: PartyPlayer? = null
    private var playerMedia: PartyMedia? = null
    private var requesting = false
    private var cancelled = false
    private var abandonedId: String? = null
    private var waitingForTitle = false
    private var inParty = false
    private var leftOnPurpose = false
    private var endedByHost = false
    private var requestedKey: String? = null
    private var publishedKey: String? = null
    private var publishedAt = Long.MIN_VALUE / 2
    private var publishedInitial = false
    private var lastSeq: Pair<Long, String?>? = null
    private var lastStatus: Triple<NuvioStatus, String?, Long?>? = null
    private var bufferingSince: Long? = null
    private var mismatchSince: Long? = null
    private var seekIntentUntil = Long.MIN_VALUE / 2
    private var lastSeekAt = Long.MIN_VALUE / 2
    private var lastCorrectionAt = Long.MIN_VALUE / 2
    private var lastDriftSeekAt = Long.MIN_VALUE / 2
    private var nudge: Float? = null
    private var baseRate = 1f
    private var seekLeadMs = 0L
    private var measureLead = false
    private var selfPaused = false
    private var driftMs: Long? = null
    private var waitSeenAt: Long? = null
    private var wasAway = false

    /** The server's party, except one this box left during its join while the leave is still on its way. */
    private val activeParty: ActiveParty?
        get() {
            val party = link.party
            if (party?.id != abandonedId) abandonedId = null
            return party?.takeIf { abandonedId == null }
        }

    override val partyMedia: PartyMedia? get() = activeParty?.media?.let(::toArc)

    override val attachedMedia: PartyMedia? get() = playerMedia

    /** Starts a party with what is playing, or with the next title that plays when nothing is. */
    fun createParty() {
        if (inParty || requesting) return
        _error.value = null
        leftOnPurpose = false
        endedByHost = false
        val media = playerMedia
        if (media == null) {
            waitingForTitle = true
            log("nuvio party waits for a title")
            publish()
            return
        }
        create(media)
    }

    fun joinParty(code: String) {
        if (inParty || requesting) return
        _error.value = null
        leftOnPurpose = false
        endedByHost = false
        waitingForTitle = false
        requesting = true
        cancelled = false
        log("nuvio party join")
        publish()
        link.join(code) { result -> finishRequest(result) }
    }

    private fun create(media: PartyMedia) {
        waitingForTitle = false
        requesting = true
        cancelled = false
        log("nuvio party create ${media.contentId}")
        publish()
        link.create(toNuvio(media)) { result -> finishRequest(result) }
    }

    private fun finishRequest(result: PartyError?) {
        requesting = false
        if (cancelled) {
            cancelled = false
            if (result == null) {
                abandonedId = link.party?.id
                leftOnPurpose = true
                link.leave()
            }
            publish()
            return
        }
        if (result != null) {
            log("nuvio party request failed $result")
            _error.value = result
        }
        publish()
    }

    override fun consumeMediaRequest() {
        _mediaRequest.value = null
    }

    override fun requestPartyTitle() {
        val party = activeParty ?: return
        if (!party.isHost) _mediaRequest.value = toArc(party.media)
    }

    override fun leaveParty() {
        if (waitingForTitle || requesting) {
            waitingForTitle = false
            if (requesting) cancelled = true
            publish()
            return
        }
        if (activeParty == null) return
        leftOnPurpose = true
        reportBrowsingIfAlone()
        link.leave()
    }

    override fun endParty() {
        if (waitingForTitle || requesting) {
            leaveParty()
            return
        }
        val party = activeParty ?: return
        leftOnPurpose = true
        if (party.isHost) link.end() else link.leave()
    }

    override fun acknowledgeEnded() {
        if (endedByHost || _error.value != null) {
            endedByHost = false
            _error.value = null
            publish()
        }
    }

    override fun setGuestsControl(enabled: Boolean) {
        val party = activeParty ?: return
        if (party.isHost && party.hostOnly == enabled) link.setHostOnly(!enabled)
    }

    override fun continueWithoutWaiting() {
        val party = activeParty ?: return
        val playback = link.playback ?: return
        if (!party.canControl || !playback.waiting) return
        link.publishPlayback(paused = false, positionMs = playback.positionMs, rate = playback.rate)
    }

    override fun attachPlayer(player: PartyPlayer, media: PartyMedia) {
        this.player = player
        playerMedia = media
        lastSeq = null
        selfPaused = false
        bufferingSince = null
        mismatchSince = null
        clearNudge(player)
        if (waitingForTitle && !requesting) create(media)
        publish()
    }

    override fun detachPlayer(player: PartyPlayer) {
        if (this.player !== player) return
        clearNudge(player)
        this.player = null
        playerMedia = null
        lastSeq = null
        selfPaused = false
        driftMs = null
        publish()
    }

    override fun noteLocalSeek() {
        seekIntentUntil = clock() + SEEK_INTENT_MS
    }

    override fun tick() {
        val now = clock()
        val party = activeParty
        if (party == null) {
            if (inParty) {
                inParty = false
                endedByHost = !leftOnPurpose
                leftOnPurpose = false
                player?.let(::clearNudge)
                log(if (endedByHost) "nuvio party ended" else "nuvio party left")
            }
            publish()
            return
        }
        if (!inParty) {
            inParty = true
            requestedKey = null
            publishedKey = party.media.key
            publishedInitial = false
            lastSeq = null
            lastStatus = null
            selfPaused = false
            log("nuvio party ${if (party.isHost) "hosting" else "joined"} members=${party.members.size}")
        }
        val p = player
        val media = playerMedia
        val localKey = media?.let(::keyOf)
        val away = p?.isAway == true
        if (wasAway && !away) lastSeq = null
        wasAway = away

        if (localKey == publishedKey && localKey != party.media.key && now - publishedAt > MEDIA_RETRY_MS) publishedKey = null
        if (party.isHost && media != null && !away && localKey != party.media.key && localKey != publishedKey) {
            publishedKey = localKey
            publishedAt = now
            log("nuvio party title -> ${media.contentId}")
            link.publishMedia(toNuvio(media), p?.positionMs ?: 0L)
        }
        if (!party.isHost && party.media.key != requestedKey) {
            requestedKey = party.media.key
            if (localKey != party.media.key) _mediaRequest.value = toArc(party.media)
        }

        val following = p?.takeIf { localKey == party.media.key && !away }
        reportStatus(following, localKey, now)
        if (following != null) follow(party, following, now) else {
            p?.let(::clearNudge)
            driftMs = null
        }
        publish()
    }

    /** [p] is the player while it shows the party's title, else null. */
    private fun reportStatus(p: PartyPlayer?, localKey: String?, now: Long) {
        val playback = link.playback
        val behind = if (selfPaused && p != null && playback != null) {
            PartySync.reportedGapMs(PartySync.expectedPositionMs(playback, link.nowMs(), p.durationMs) - p.positionMs)
        } else {
            null
        }
        val status = when {
            p == null -> NuvioStatus.Browsing
            behind != null -> NuvioStatus.Behind
            p.durationMs <= 0L -> NuvioStatus.Loading
            p.isBuffering -> {
                val since = bufferingSince ?: now.also { bufferingSince = it }
                if (now - since > BUFFERING_REPORT_MS) NuvioStatus.Buffering else NuvioStatus.Ready
            }
            else -> {
                bufferingSince = null
                NuvioStatus.Ready
            }
        }
        val next = Triple(status, if (p != null) localKey else null, behind)
        if (next == lastStatus) return
        lastStatus = next
        link.setLocalStatus(status, next.second, behind)
    }

    private fun follow(party: ActiveParty, p: PartyPlayer, now: Long) {
        val position = p.positionMs
        val duration = p.durationMs
        val playback = link.playback
        if (playback == null) {
            if (party.isHost && !publishedInitial && duration > 0L) {
                publishedInitial = true
                link.publishPlayback(paused = !p.wantsToPlay, positionMs = position, rate = 1f)
            }
            return
        }
        publishedInitial = true
        if (duration <= 0L) return
        val expected = PartySync.expectedPositionMs(playback, link.nowMs(), duration)
        driftMs = expected - position

        val identity = playback.seq to playback.memberId
        if (identity != lastSeq) {
            val forced = lastSeq == null
            lastSeq = identity
            if (selfPaused && playback.paused) selfPaused = false
            if (!selfPaused) {
                if (forced || p.wantsToPlay == playback.paused) {
                    if (playback.paused) p.pause() else p.play()
                }
                val own = playback.memberId == party.memberId && !forced
                val distance = abs(position - expected)
                val bitstream = !p.speedAllowed && !playback.paused
                val limit = if (bitstream) PartySync.SEEK_THRESHOLD_MS else PartySync.PAUSED_TOLERANCE_MS
                val due = !bitstream || forced || now - lastDriftSeekAt >= BITSTREAM_SEEK_GAP_MS || distance > BITSTREAM_FAR_MS
                if ((!own || playback.paused) && distance > limit && due) {
                    if (bitstream && !forced) lastDriftSeekAt = now
                    seek(p, if (playback.paused) expected else expected + seekLeadMs, duration, now)
                    measureLead = !playback.paused
                }
                baseRate = playback.rate
                if (nudge == null && p.speedAllowed) p.setSpeed(playback.rate)
            }
            lastSeekAt = now
            mismatchSince = null
            return
        }

        if (selfPaused) {
            if (!p.wantsToPlay) return
            selfPaused = false
            log("nuvio party catch up")
            seek(p, expected + seekLeadMs, duration, now)
            return
        }
        if (p.isBuffering) {
            mismatchSince = null
            return
        }

        if (now < seekIntentUntil && abs(position - expected) > PartySync.SEEK_NOTICE_MS) {
            seekIntentUntil = Long.MIN_VALUE / 2
            if (party.canControl) {
                link.publishPlayback(paused = !p.wantsToPlay, positionMs = position, rate = playback.rate)
            } else {
                seek(p, if (playback.paused) expected else expected + seekLeadMs, duration, now)
            }
            lastSeekAt = now
            return
        }

        val playing = p.wantsToPlay
        if (playing == playback.paused) {
            val since = mismatchSince ?: now.also { mismatchSince = it }
            if (now - since > OUTSIDE_TOGGLE_MS) {
                mismatchSince = null
                when {
                    party.canControl -> link.publishPlayback(paused = !playing, positionMs = position, rate = playback.rate)
                    playing -> p.pause()
                    else -> {
                        selfPaused = true
                        clearNudge(p)
                        log("nuvio party self paused")
                    }
                }
            }
            return
        }
        mismatchSince = null

        if (now - lastSeekAt < SEEK_SETTLE_MS || now - lastCorrectionAt < CORRECTION_INTERVAL_MS) return
        lastCorrectionAt = now
        val drift = position - expected
        if (measureLead && !playback.paused) {
            measureLead = false
            if (abs(drift) < MAX_SEEK_LEAD_MS) seekLeadMs = (seekLeadMs - drift).coerceIn(0L, MAX_SEEK_LEAD_MS)
        }
        if (!p.speedAllowed) {
            clearNudge(p)
            val distance = abs(drift)
            val limit = if (playback.paused) PartySync.PAUSED_TOLERANCE_MS else PartySync.SEEK_THRESHOLD_MS
            val due = playback.paused || now - lastDriftSeekAt >= BITSTREAM_SEEK_GAP_MS || distance > BITSTREAM_FAR_MS
            if (distance > limit && due) {
                if (!playback.paused) lastDriftSeekAt = now
                log("nuvio party bitstream seek drift=$drift")
                seek(p, if (playback.paused) expected else expected + seekLeadMs, duration, now)
                measureLead = !playback.paused
            }
            return
        }
        when (val correction = PartySync.correction(position, expected, playback, nudge != null)) {
            is PartyCorrection.Seek -> {
                clearNudge(p)
                seek(p, if (playback.paused) correction.positionMs else correction.positionMs + seekLeadMs, duration, now)
                measureLead = !playback.paused
            }
            is PartyCorrection.Nudge -> if (nudge != correction.rate) {
                nudge = correction.rate
                p.setSpeed(correction.rate)
            }
            PartyCorrection.InSync -> clearNudge(p)
        }
    }

    private fun seek(p: PartyPlayer, target: Long, duration: Long, now: Long) {
        p.seekTo(if (duration > 0L) target.coerceIn(0L, duration) else target.coerceAtLeast(0L))
        lastSeekAt = now
    }

    private fun clearNudge(p: PartyPlayer) {
        if (nudge == null) return
        nudge = null
        p.setSpeed(if (p.speedAllowed) baseRate else 1f)
    }

    /** A host that leaves while nobody else watches pauses the room, as the Nuvio apps do. */
    private fun reportBrowsingIfAlone() {
        val party = activeParty ?: return
        val playback = link.playback ?: return
        val othersWatching = party.presence.values.any {
            it.memberId != party.memberId && it.mediaKey == party.media.key && it.status != NuvioStatus.Browsing
        }
        if (!playback.paused && !othersWatching && party.canControl) {
            link.publishPlayback(paused = true, positionMs = player?.positionMs ?: playback.positionMs, rate = playback.rate)
        }
    }

    private fun publish() {
        val party = activeParty
        val now = clock()
        if (party == null) {
            waitSeenAt = null
            _state.value = PartyState(
                status = if (requesting || waitingForTitle) PartyStatus.CONNECTING else PartyStatus.IDLE,
                role = if (waitingForTitle) PartyRole.HOST else null,
                endedByHost = endedByHost,
                withNuvioApps = true,
                waitingForTitle = waitingForTitle,
            )
            return
        }
        val playback = link.playback
        val localKey = playerMedia?.let(::keyOf)
        val online = party.presence.keys + party.memberId
        val members = party.members.map { member ->
            val self = member.id == party.memberId
            PartyMemberView(
                id = member.id,
                name = member.displayName,
                isHost = member.id == party.hostMemberId,
                isSelf = self,
                status = memberStatus(if (self) lastStatus?.first else party.presence[member.id]?.status, member.id in online),
                avatarUrl = member.avatarUrl,
                colour = member.avatarColor,
            )
        }
        val waiting = playback?.takeIf { it.paused && it.waiting }?.let { PartySync.waitingMemberIds(party, emptySet()) }
        if (waiting == null) waitSeenAt = null else if (waitSeenAt == null) waitSeenAt = now
        val hold = waiting?.let { ids ->
            val id = ids.firstOrNull()
            PartyHoldView(
                reason = PartyHoldReason.BUFFERING,
                memberName = party.member(id)?.displayName,
                isSelf = id == party.memberId,
                secondsLeft = ((WAIT_LIMIT_MS - (now - (waitSeenAt ?: now))) / 1000L).toInt().coerceAtLeast(0),
            )
        }
        _state.value = PartyState(
            status = if (party.connected) PartyStatus.ACTIVE else PartyStatus.RECONNECTING,
            role = if (party.isHost) PartyRole.HOST else PartyRole.GUEST,
            code = party.code,
            members = members,
            hostPresent = party.isHost || party.hostMemberId in online,
            guestsControl = !party.hostOnly,
            hold = hold,
            driftMs = driftMs?.takeIf { localKey == party.media.key },
            selfPaused = selfPaused,
            playingPartyTitle = localKey == party.media.key,
            withNuvioApps = true,
            maxMembers = party.maxMembers,
            inviteLink = party.inviteLink,
        )
    }

    private fun memberStatus(status: NuvioStatus?, online: Boolean): PartyMemberStatus = when {
        !online -> PartyMemberStatus.AWAY
        status == NuvioStatus.Ready -> PartyMemberStatus.IN_SYNC
        status == NuvioStatus.Loading -> PartyMemberStatus.SYNCING
        status == NuvioStatus.Buffering -> PartyMemberStatus.BUFFERING
        status == NuvioStatus.Behind -> PartyMemberStatus.CATCHING_UP
        else -> PartyMemberStatus.AWAY
    }

    companion object {
        private const val SEEK_INTENT_MS = 3_000L
        private const val BUFFERING_REPORT_MS = 1_000L
        private const val OUTSIDE_TOGGLE_MS = 1_200L
        private const val SEEK_SETTLE_MS = 2_500L
        private const val CORRECTION_INTERVAL_MS = 500L
        private const val MAX_SEEK_LEAD_MS = 3_000L
        private const val BITSTREAM_SEEK_GAP_MS = 60_000L
        private const val BITSTREAM_FAR_MS = 5_000L
        private const val WAIT_LIMIT_MS = 15_000L
        private const val MEDIA_RETRY_MS = 10_000L

        fun keyOf(media: PartyMedia): String = partyMediaKey(media.contentType, media.contentId, media.season, media.episode)

        fun toNuvio(media: PartyMedia): NuvioMedia = NuvioMedia(
            type = media.contentType,
            metaId = media.contentId,
            metaType = media.contentType,
            videoId = media.videoId,
            season = media.season,
            episode = media.episode,
            title = media.title,
            episodeTitle = media.episodeTitle,
            poster = media.poster,
            background = media.backdrop,
            logo = media.logo,
            source = PartySource(
                infoHash = media.fingerprint.infoHash,
                fileIdx = media.fingerprint.fileIdx,
                filename = media.fingerprint.filename,
                videoSize = media.fingerprint.sizeBytes,
                name = media.streamName,
            ),
        )

        fun toArc(media: NuvioMedia): PartyMedia = PartyMedia(
            contentId = media.metaId,
            contentType = media.metaType,
            videoId = media.videoId,
            season = media.season,
            episode = media.episode,
            title = media.title,
            episodeTitle = media.episodeTitle,
            poster = PartyInvites.cleanImage(media.poster),
            backdrop = PartyInvites.cleanImage(media.background),
            logo = PartyInvites.cleanImage(media.logo),
            fingerprint = PartyFingerprint(
                infoHash = media.source?.infoHash?.lowercase(),
                fileIdx = media.source?.fileIdx,
                filename = media.source?.filename,
                sizeBytes = media.source?.videoSize,
            ),
            streamName = media.source?.name,
        )
    }
}
