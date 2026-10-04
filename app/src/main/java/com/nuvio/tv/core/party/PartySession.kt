package com.nuvio.tv.core.party

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * One watch party as seen from this device. The host's player is the room's clock; guests estimate where the
 * host is and follow. Everything here runs on one thread; [tick] is expected about ten times a second.
 */
class PartySession(
    private val transportFactory: () -> PartyTransport,
    private val clock: () -> Long,
    private val deviceName: () -> String,
    private val codeFactory: () -> String = { PartyCode.generate() },
    private val log: (String) -> Unit = {},
    private val profile: () -> PartyProfile = { PartyProfile() },
    /** This device's friend key pair; [create] makes one the first time it is needed. Null when there is none. */
    private val friendIdentity: (create: Boolean) -> PartyIdentity? = { null },
    private val isFriend: (String) -> Boolean = { false },
    private val onFriendAdded: (PartyFriendRequest) -> Unit = {},
) {
    private class Member(val id: String) {
        var name: String = ""
        var speedOk: Boolean = true
        var lastSeenAt: Long = 0L
        var stalled: Boolean = false
        var ready: Boolean = false
        var readyAt: Long = Long.MIN_VALUE
        var positionMs: Long = 0L
        var lastHoldAt: Long? = null
        var away: Boolean = false
        var avatar: String? = null
        var colour: String? = null
        var friendKey: String? = null
    }

    private class Room(val epoch: Int, val hostClock: Long, val positionMs: Long, val playing: Boolean, val hold: PartyHoldInfo?)

    private class Hold(val reason: PartyHoldReason, val memberId: String?, val startedAt: Long, val until: Long, val positionMs: Long)

    private class Settle(val target: Long, val startedAt: Long, val moving: Boolean, val learn: Boolean = moving)

    private class PendingCommand(val action: PartyAction, val positionMs: Long, val until: Long)

    /** Where the room was when this device took over without a player; it keeps the room's clock until one attaches. */
    private class Adopted(val positionMs: Long, val at: Long, val playing: Boolean) {
        fun positionAt(now: Long): Long = if (playing) positionMs + max(0L, now - at) else positionMs
    }

    private val _state = MutableStateFlow(PartyState())
    val state: StateFlow<PartyState> = _state.asStateFlow()

    private val _mediaRequest = MutableStateFlow<PartyMedia?>(null)

    /** The title a guest should open. The app navigates there, then calls [consumeMediaRequest]. */
    val mediaRequest: StateFlow<PartyMedia?> = _mediaRequest.asStateFlow()

    private val _friendRequest = MutableStateFlow<PartyFriendRequest?>(null)

    /** A member asked to add this device as a friend; answer with [answerFriendRequest]. */
    val friendRequest: StateFlow<PartyFriendRequest?> = _friendRequest.asStateFlow()

    /** What the party is watching, for the guest's stream picker. */
    val partyMedia: PartyMedia? get() = hostMedia

    private var transport: PartyTransport? = null
    private var localId: String = ""
    private var status = PartyStatus.IDLE
    private var role: PartyRole? = null
    private var code: String? = null
    private var term = 0
    private var epoch = 0
    /** Host and term of the last accepted State and Media, and the newest State time from them: relays can reorder. */
    private var stateFrom: String? = null
    private var newestHostState = Long.MIN_VALUE
    private var mediaFrom: String? = null
    private var hostId: String? = null
    private var hostLeft = false
    private var sawHost = false
    private val members = LinkedHashMap<String, Member>()
    private var roster: List<String> = emptyList()
    private val catchingUp = LinkedHashSet<String>()
    private var guestsControl = true
    private var shareLink = false
    private var problem: PartyTransportProblem? = null
    private var endedByHost = false

    private var player: PartyPlayer? = null
    private var playerMedia: PartyMedia? = null
    private var hostMedia: PartyMedia? = null
    private var room: Room? = null
    private val hostClock = PartyClock()
    private val pendingPings = LinkedHashSet<Long>()
    private var pingsSent = 0
    private var nextPingAt = 0L

    private var expectedIntent: Boolean? = null
    private var intentGraceUntil = 0L
    private var seekIntentUntil = 0L
    private var lastPosition = 0L
    private var lastSampleAt = 0L
    private var settle: Settle? = null
    private var seekLeadMs = DEFAULT_SEEK_LEAD_MS
    private var lastSeekAt = Long.MIN_VALUE / 2
    private var currentSpeed = 1f
    private var bufferingSince: Long? = null
    private var selfStalled = false
    private var aligned = false
    private var everSynced = false
    private var resyncRequested = false
    private var selfPaused = false
    private var pendingCommand: PendingCommand? = null
    private var driftMs: Long? = null
    private var pausedAlignAttempts = 0
    private var pausedAlignKey = Long.MIN_VALUE

    private var hold: Hold? = null
    private var lastBufferHoldAt: Long? = null
    private val handledStalls = HashSet<String>()
    private var pendingPlayAt: Long? = null
    private var lastHostPosition = 0L
    private var lastStateSentAt = Long.MIN_VALUE / 2
    private var lastStatusSentAt = Long.MIN_VALUE / 2
    private var lastStatusStalled = false
    private var lastStatusReady = false
    private var lastHelloAt = Long.MIN_VALUE / 2
    private var startedAt = 0L
    private var lastDriftLogAt = Long.MIN_VALUE / 2
    private var lastSpeedOk: Boolean? = null
    private var away = false
    private var steppedDown = false
    private var recoverUntil = Long.MIN_VALUE / 2
    private var resyncAfterRecovery = false
    private var adopted: Adopted? = null
    private var parkedUntil: Long? = null
    private var parkedSince = 0L
    private var parkAttempts = 0
    private var hostChanges = 0
    private var heldMediaRequest: PartyMedia? = null
    private val friendAsked = LinkedHashMap<String, Long>()
    private val friendRequests = ArrayDeque<PartyFriendRequest>()
    private var helloProof: Triple<String, String, String>? = null
    private val random = SecureRandom()

    fun createParty(): String {
        val newCode = codeFactory()
        start(newCode, PartyRole.HOST)
        return newCode
    }

    /** False when the code is not a valid party code. */
    fun joinParty(rawCode: String): Boolean {
        val normalized = PartyCode.normalize(rawCode) ?: return false
        start(normalized, PartyRole.GUEST)
        return true
    }

    fun leaveParty() {
        if (status == PartyStatus.IDLE) return
        transport?.send(PartyCodec.encode(PartyMessage.Bye))
        shutDown(endedByHost = false)
    }

    /** Host only: closes the room for everyone. */
    fun endParty() {
        if (status == PartyStatus.IDLE) return
        if (role == PartyRole.HOST) transport?.send(PartyCodec.encode(PartyMessage.End(term)))
        else transport?.send(PartyCodec.encode(PartyMessage.Bye))
        shutDown(endedByHost = false)
    }

    fun acknowledgeEnded() {
        if (endedByHost) {
            endedByHost = false
            publish()
        }
    }

    /** Tells the room straight away that this member's name changed. */
    fun announceName() {
        if (status != PartyStatus.ACTIVE) return
        sendHello(clock(), force = true)
        publish()
    }

    /** Asks [memberId] to become a friend. Nothing is stored until they accept on their own screen. */
    fun addFriend(memberId: String) {
        if (status != PartyStatus.ACTIVE || memberId == localId || !members.containsKey(memberId)) return
        val me = friendIdentity(true) ?: return
        friendAsked[memberId] = clock()
        log("friend request to ${short(memberId)}")
        val card = profile()
        send(
            PartyMessage.FriendRequest(
                to = memberId,
                key = me.publicKeyHex,
                proof = PartyInvites.proof(me, localId, memberId, random),
                name = deviceName(),
                avatar = card.avatarUrl,
                colour = card.colour,
            )
        )
        publish()
    }

    fun answerFriendRequest(accept: Boolean) {
        val request = friendRequests.removeFirstOrNull() ?: return
        _friendRequest.value = friendRequests.firstOrNull()
        if (accept) befriend(request)
    }

    private fun befriend(request: PartyFriendRequest) {
        val me = friendIdentity(true) ?: return
        friendAsked.remove(request.memberId)
        log("friend added ${short(request.memberId)}")
        onFriendAdded(request)
        val card = profile()
        send(
            PartyMessage.FriendAccept(
                to = request.memberId,
                key = me.publicKeyHex,
                proof = PartyInvites.proof(me, localId, request.memberId, random),
                name = deviceName(),
                avatar = card.avatarUrl,
                colour = card.colour,
            )
        )
        sendHello(clock(), force = true)
    }

    private fun forgetFriendRequest(memberId: String) {
        friendAsked.remove(memberId)
        friendRequests.removeAll { it.memberId == memberId }
        _friendRequest.value = friendRequests.firstOrNull()
    }

    private fun queueFriendRequest(request: PartyFriendRequest) {
        val index = friendRequests.indexOfFirst { it.memberId == request.memberId }
        if (index >= 0) friendRequests[index] = request else friendRequests.addLast(request)
        _friendRequest.value = friendRequests.firstOrNull()
    }

    fun consumeMediaRequest() {
        _mediaRequest.value = null
    }

    /** Guest only: opens what the party is watching again, for a guest who wandered off. */
    fun requestPartyTitle() {
        if (role != PartyRole.GUEST) return
        hostMedia?.let { _mediaRequest.value = it }
    }

    fun setGuestsControl(enabled: Boolean) {
        if (role != PartyRole.HOST || guestsControl == enabled) return
        guestsControl = enabled
        broadcastState()
        publish()
    }

    /** Host only: lets guests receive the stream link itself, for links the app considers safe to share. */
    fun setShareLink(enabled: Boolean) {
        if (role != PartyRole.HOST || shareLink == enabled) return
        shareLink = enabled
        sendMedia()
        publish()
    }

    val shareLinkEnabled: Boolean get() = shareLink

    /** Host only: pauses everyone at the current position and resumes once the room is lined up. */
    fun startTogether() {
        if (role != PartyRole.HOST || player == null || hold != null) return
        val now = clock()
        beginHold(PartyHoldReason.LOADING, null, now + LOADING_HOLD_MS, now)
    }

    /** Host only: stops waiting for a member who is still buffering. */
    fun continueWithoutWaiting() {
        val current = hold ?: return
        if (role != PartyRole.HOST) return
        val now = clock()
        if (current.reason == PartyHoldReason.BUFFERING) current.memberId?.takeIf { it != localId }?.let(catchingUp::add)
        endHold(now)
    }

    fun attachPlayer(player: PartyPlayer, media: PartyMedia) {
        val now = clock()
        this.player = player
        this.playerMedia = media
        currentSpeed = 1f
        settle = null
        bufferingSince = null
        selfStalled = false
        aligned = false
        lastSpeedOk = player.speedAllowed
        resetBaseline(player, now)
        if (status == PartyStatus.IDLE) return
        log("player attached ${describe(media)} at ${player.positionMs} speedOk=${player.speedAllowed}")
        when (role) {
            PartyRole.HOST -> {
                adopted?.let { room ->
                    adopted = null
                    val position = room.positionAt(now)
                    if (media.sameTitle(hostMedia) && abs(player.positionMs - position) > ADOPT_SEEK_MS) {
                        log("new host starts from the room position $position")
                        commandSeek(player, position, now, moving = false)
                    }
                    if (room.playing) commandPlay(player, now) else commandPause(player, now)
                }
                val hadMedia = hostMedia != null
                val changed = !hadMedia || !media.sameFile(hostMedia)
                if (changed) {
                    if (hadMedia) epoch++
                    hostMedia = media
                    everSynced = true
                    catchingUp.clear()
                    handledStalls.clear()
                    members.values.forEach { it.ready = false; it.stalled = false }
                    sendMedia()
                    if (otherMembers().isNotEmpty() && hadMedia) {
                        beginHold(PartyHoldReason.LOADING, null, now + LOADING_HOLD_MS, now)
                    } else {
                        broadcastState()
                    }
                } else {
                    hostMedia = media
                    sendMedia()
                    broadcastState()
                }
            }
            PartyRole.GUEST -> {
                everSynced = false
                selfPaused = false
                pendingCommand = null
                resyncRequested = true
                sendStatus(now)
            }
            null -> Unit
        }
        publish()
    }

    fun detachPlayer(player: PartyPlayer) {
        if (this.player !== player) return
        lastHostPosition = runCatching { player.positionMs }.getOrDefault(lastHostPosition)
        applySpeed(player, 1f)
        this.player = null
        this.playerMedia = null
        settle = null
        expectedIntent = null
        selfStalled = false
        aligned = false
        driftMs = null
        pendingPlayAt = null
        lastSpeedOk = null
        if (status == PartyStatus.IDLE) return
        log("player detached at $lastHostPosition")
        if (role == PartyRole.HOST) {
            hold = null
            broadcastState()
        } else {
            sendStatus(clock())
        }
        publish()
    }

    val attachedMedia: PartyMedia? get() = playerMedia

    /** A host with company hands the room over while away; a guest is left out of the waiting and lines up on return. */
    private fun updateAway(now: Long) {
        val p = player ?: return
        val value = p.isAway
        if (away == value) return
        away = value
        log(if (value) "away" else "back")
        if (value) {
            pendingCommand = null
            settle = null
            bufferingSince = null
            selfStalled = false
            aligned = false
            driftMs = null
            expectedIntent = p.wantsToPlay
            if (role == PartyRole.HOST && someoneElseWatching()) stepDown(now)
            else if (role == PartyRole.GUEST) sendStatus(now)
        } else {
            resetBaseline(p, now)
            intentGraceUntil = now + BACK_GRACE_MS
            selfPaused = false
            resyncRequested = true
            heldMediaRequest?.let { media ->
                heldMediaRequest = null
                if (!media.sameTitle(playerMedia)) _mediaRequest.value = media
            }
            if (role == PartyRole.GUEST && steppedDown && hostId == null) {
                takeOver(now)
            } else if (role == PartyRole.GUEST) {
                sendStatus(now)
            }
        }
    }

    /**
     * The person at this device is about to move the position. Only announced jumps are shared with the room;
     * the player's own recovery seeks are left to the normal drift handling.
     */
    fun noteLocalSeek() {
        seekIntentUntil = clock() + SEEK_INTENT_WINDOW_MS
    }

    fun tick() {
        if (status == PartyStatus.IDLE) return
        val now = clock()
        player?.speedAllowed?.let { ok ->
            if (ok != lastSpeedOk) {
                log("speedOk=$ok")
                lastSpeedOk = ok
            }
        }
        updateAway(now)
        friendAsked.entries.removeAll { now - it.value > FRIEND_ASK_TIMEOUT_MS }
        if (away && role == PartyRole.HOST && someoneElseWatching()) stepDown(now)
        pruneMembers(now)
        when (role) {
            PartyRole.HOST -> hostTick(now)
            PartyRole.GUEST -> guestTick(now)
            null -> Unit
        }
        publish()
    }

    private fun start(newCode: String, newRole: PartyRole) {
        if (status != PartyStatus.IDLE) {
            transport?.send(PartyCodec.encode(PartyMessage.Bye))
            shutDown(endedByHost = false)
        }
        val now = clock()
        code = newCode
        role = newRole
        status = PartyStatus.CONNECTING
        term = if (newRole == PartyRole.HOST) 1 else 0
        epoch = 0
        guestsControl = true
        shareLink = false
        endedByHost = false
        problem = null
        val created = transportFactory()
        transport = created
        localId = created.localId
        startedAt = now
        log("start ${newRole.name.lowercase()} id=${short(localId)}")
        if (newRole == PartyRole.HOST) {
            hostId = localId
            sawHost = true
            roster = listOf(localId)
            hostMedia = playerMedia
            everSynced = true
        }
        player?.let { resetBaseline(it, now) }
        created.open(newCode, Listener(created))
        publish()
    }

    private fun shutDown(endedByHost: Boolean) {
        log("stop endedByHost=$endedByHost")
        player?.let { applySpeed(it, 1f) }
        transport?.close()
        transport = null
        status = PartyStatus.IDLE
        role = null
        code = null
        term = 0
        epoch = 0
        hostId = null
        hostLeft = false
        sawHost = false
        steppedDown = false
        away = false
        adopted = null
        heldMediaRequest = null
        friendAsked.clear()
        friendRequests.clear()
        _friendRequest.value = null
        helloProof = null
        parkedUntil = null
        recoverUntil = Long.MIN_VALUE / 2
        resyncAfterRecovery = false
        members.clear()
        roster = emptyList()
        catchingUp.clear()
        handledStalls.clear()
        hostMedia = null
        room = null
        hostClock.reset()
        pendingPings.clear()
        pingsSent = 0
        hold = null
        lastBufferHoldAt = null
        pendingPlayAt = null
        pendingCommand = null
        selfPaused = false
        selfStalled = false
        aligned = false
        everSynced = false
        resyncRequested = false
        driftMs = null
        problem = null
        this.endedByHost = endedByHost
        _mediaRequest.value = null
        publish()
    }

    private inner class Listener(private val owner: PartyTransport) : PartyTransport.Listener {
        private val current: Boolean get() = transport === owner

        override fun onConnected() {
            if (!current) return
            val now = clock()
            log("connected after ${now - startedAt} ms")
            status = PartyStatus.ACTIVE
            problem = null
            sendHello(now)
            if (role == PartyRole.HOST) {
                sendMedia()
                broadcastState()
            } else {
                pingsSent = 0
                nextPingAt = now
            }
            publish()
        }

        override fun onDisconnected() {
            if (!current) return
            log("disconnected, reconnecting")
            status = PartyStatus.RECONNECTING
            publish()
        }

        override fun onMessage(senderId: String, payload: String) {
            if (!current || senderId == localId) return
            val message = PartyCodec.decode(payload) ?: return
            handle(senderId, message, clock())
            publish()
        }

        override fun onProblem(problem: PartyTransportProblem) {
            if (!current) return
            log("problem $problem")
            this@PartySession.problem = problem
            publish()
        }
    }

    private fun handle(senderId: String, message: PartyMessage, now: Long) {
        val known = members.containsKey(senderId)
        val member = members.getOrPut(senderId) { Member(senderId) }
        member.lastSeenAt = now
        when (message) {
            is PartyMessage.Hello -> {
                if (!known || member.name != message.name) {
                    log("member ${short(senderId)} name=${message.name} host=${message.host} term=${message.term} speedOk=${message.speedOk}")
                }
                member.name = message.name
                member.speedOk = message.speedOk
                member.avatar = message.avatar
                member.colour = message.colour
                val offered = message.friendKey
                if (offered != null && offered != member.friendKey && message.friendProof != null &&
                    PartyInvites.checkProof(offered, senderId, PartyInvites.ANYONE, message.friendProof)
                ) {
                    member.friendKey = offered
                }
                if (message.host) acceptHost(senderId, message.term, now)
                if (role == PartyRole.HOST) {
                    if (senderId !in roster) roster = roster + senderId
                    sendHello(now, force = !known)
                    sendMedia()
                    broadcastState()
                }
            }
            is PartyMessage.Media -> {
                if (!acceptHost(senderId, message.term, now)) return
                val from = "$senderId/${message.term}"
                if (from == mediaFrom && message.epoch < epoch) return
                mediaFrom = from
                val newEpoch = message.epoch != epoch || hostMedia == null
                epoch = message.epoch
                hostMedia = message.media
                if (newEpoch) {
                    log("media epoch=${message.epoch} ${describe(message.media)} sameTitle=${message.media.sameTitle(playerMedia)} link=${message.media.sharedUrl != null}")
                    everSynced = false
                    selfPaused = false
                    pendingCommand = null
                    resyncRequested = true
                }
                if (!message.media.sameTitle(playerMedia)) {
                    if (away) heldMediaRequest = message.media else _mediaRequest.value = message.media
                }
            }
            is PartyMessage.State -> {
                if (!acceptHost(senderId, message.term, now)) return
                val from = "$senderId/${message.term}"
                if (from != stateFrom) {
                    stateFrom = from
                    newestHostState = Long.MIN_VALUE
                }
                if (message.sentAt < newestHostState) return
                newestHostState = message.sentAt
                hostClock.addRoughHint(message.sentAt, now)
                roster = message.roster
                guestsControl = message.guestsControl
                catchingUp.clear()
                catchingUp.addAll(message.catchingUp)
                if (message.epoch != epoch || hostMedia == null) {
                    sendHello(now)
                    return
                }
                room = Room(message.epoch, message.hostClock, message.positionMs, message.playing, message.hold)
                pendingCommand?.let { pending ->
                    val reflected = when (pending.action) {
                        PartyAction.PLAY -> message.playing
                        PartyAction.PAUSE -> !message.playing
                        PartyAction.SEEK -> abs(message.positionMs - pending.positionMs) < COMMAND_SEEK_MATCH_MS
                    }
                    if (reflected) pendingCommand = null
                }
                if (role == PartyRole.GUEST) guestSync(now)
            }
            is PartyMessage.Command -> {
                log("command ${message.action} at ${message.positionMs} from ${short(senderId)} guestsControl=$guestsControl")
                if (role == PartyRole.HOST && guestsControl && message.epoch == epoch) applyGuestCommand(message, now)
            }
            is PartyMessage.Status -> {
                if (message.name.isNotBlank()) member.name = message.name
                member.speedOk = message.speedOk
                member.positionMs = message.positionMs
                member.away = message.away
                message.avatar?.let { member.avatar = it }
                message.colour?.let { member.colour = it }
                val current = message.epoch == epoch
                member.stalled = current && message.stalled
                val ready = current && message.ready
                if (ready) member.readyAt = now
                if (role == PartyRole.HOST) {
                    log("status ${short(senderId)} at ${message.positionMs} drift=${message.driftMs} ready=$ready stalled=${member.stalled} speedOk=${message.speedOk} away=${message.away}")
                }
                member.ready = ready
                if (!member.stalled) handledStalls.remove(senderId)
                if (role == PartyRole.HOST && senderId !in roster) roster = roster + senderId
            }
            is PartyMessage.Ping -> {
                if (role == PartyRole.HOST) send(PartyMessage.Pong(to = senderId, t0 = message.t0, t1 = now))
            }
            is PartyMessage.Pong -> {
                if (message.to == localId && senderId == hostId && pendingPings.remove(message.t0)) {
                    hostClock.addSample(message.t0, message.t1, now)
                    log("clock rtt=${now - message.t0} best=${hostClock.roundTripMs} offset=${hostClock.offsetMs}")
                }
            }
            is PartyMessage.End -> {
                if (senderId == hostId) shutDown(endedByHost = true)
            }
            is PartyMessage.FriendRequest -> {
                if (message.to != localId || !PartyInvites.checkProof(message.key, senderId, localId, message.proof)) return
                member.friendKey = message.key
                val request = PartyFriendRequest(
                    memberId = senderId,
                    key = message.key,
                    name = message.name.ifBlank { member.name },
                    avatarUrl = message.avatar,
                    colour = message.colour,
                )
                log("friend request from ${short(senderId)} known=${isFriend(message.key)}")
                if (isFriend(message.key) || senderId in friendAsked) befriend(request) else queueFriendRequest(request)
            }
            is PartyMessage.FriendAccept -> {
                if (message.to != localId || senderId !in friendAsked) return
                if (!PartyInvites.checkProof(message.key, senderId, localId, message.proof)) return
                friendAsked.remove(senderId)
                member.friendKey = message.key
                log("friend accepted by ${short(senderId)}")
                onFriendAdded(
                    PartyFriendRequest(
                        memberId = senderId,
                        key = message.key,
                        name = message.name.ifBlank { member.name },
                        avatarUrl = message.avatar,
                        colour = message.colour,
                    )
                )
            }
            PartyMessage.Bye -> {
                log("member ${short(senderId)} left")
                forgetFriendRequest(senderId)
                members.remove(senderId)
                catchingUp.remove(senderId)
                handledStalls.remove(senderId)
                if (role == PartyRole.HOST) {
                    roster = roster - senderId
                    broadcastState()
                } else if (senderId == hostId) {
                    hostLeft = true
                }
            }
        }
    }

    /** True when [senderId] is the host we follow after seeing a host message with [senderTerm]. */
    private fun acceptHost(senderId: String, senderTerm: Int, now: Long): Boolean {
        if (role == PartyRole.HOST) {
            val outranked = senderTerm > term || (senderTerm == term && senderId < localId)
            if (!outranked) {
                if (now - lastStateSentAt > REASSERT_INTERVAL_MS) broadcastState()
                return false
            }
            log("host ${short(senderId)} outranks us, now guest")
            hostChanges++
            role = PartyRole.GUEST
            adopted = null
            hold = null
            pendingPlayAt = null
            hostClock.reset()
            pendingPings.clear()
            pingsSent = 0
            nextPingAt = now
            everSynced = false
            resyncRequested = true
        } else {
            val current = hostId
            val accept = current == null || senderTerm > term ||
                (senderTerm == term && (senderId == current || senderId < current))
            if (!accept) return false
            if (current != senderId) {
                log("host is ${short(senderId)} term=$senderTerm")
                if (current != null || steppedDown) hostChanges++
                lastStatusSentAt = Long.MIN_VALUE / 2
                hostClock.reset()
                pendingPings.clear()
                pingsSent = 0
                nextPingAt = now
            }
        }
        hostId = senderId
        term = senderTerm
        hostLeft = false
        sawHost = true
        steppedDown = false
        return true
    }

    // ---- Host ----

    private fun hostTick(now: Long) {
        val p = player
        if (p != null && !away) {
            val action = detectLocalAction(p, now)
            trackOwnStall(p, now)
            if (action != null) onHostLocalAction(action, p, now)
            pendingPlayAt?.let { at ->
                if (now >= at) {
                    pendingPlayAt = null
                    commandPlay(p, now)
                }
            }
            evaluateHold(p, now)
        }
        if (otherMembers().isNotEmpty() && now - lastStateSentAt >= STATE_INTERVAL_MS) broadcastState()
    }

    private fun onHostLocalAction(action: PartyAction, p: PartyPlayer, now: Long) {
        log("local $action at ${p.positionMs}")
        when (action) {
            PartyAction.PLAY -> {
                val current = hold
                if (current != null) {
                    if (current.reason == PartyHoldReason.BUFFERING) {
                        current.memberId?.takeIf { it != localId }?.let(catchingUp::add)
                    }
                    hold = null
                    if (current.reason == PartyHoldReason.BUFFERING) lastBufferHoldAt = now
                }
                pendingPlayAt = null
                broadcastState()
            }
            PartyAction.PAUSE -> {
                pendingPlayAt = null
                broadcastState()
            }
            PartyAction.SEEK -> {
                if (expectedIntent == true && otherMembers().isNotEmpty()) {
                    settle = Settle(p.positionMs, now, moving = false)
                    beginHold(PartyHoldReason.LOADING, null, now + SEEK_HOLD_MS, now)
                } else {
                    broadcastState()
                }
            }
        }
    }

    private fun applyGuestCommand(message: PartyMessage.Command, now: Long) {
        val p = player ?: return
        when (message.action) {
            PartyAction.PLAY -> {
                val current = hold
                if (current != null) {
                    continueWithoutWaiting()
                } else if (expectedIntent != true && pendingPlayAt == null) {
                    pendingPlayAt = now + RESUME_LEAD_MS
                    broadcastState()
                }
            }
            PartyAction.PAUSE -> {
                pendingPlayAt = null
                if (hold == null) {
                    commandPause(p, now)
                    broadcastState()
                }
            }
            PartyAction.SEEK -> {
                val wasPlaying = expectedIntent == true || pendingPlayAt != null
                val target = message.positionMs.coerceAtLeast(0L)
                commandSeek(p, target, now, moving = false)
                if (wasPlaying && hold == null) {
                    beginHold(PartyHoldReason.LOADING, null, now + SEEK_HOLD_MS, now, position = target)
                } else {
                    broadcastState()
                }
            }
        }
    }

    private fun evaluateHold(p: PartyPlayer, now: Long) {
        val current = hold
        if (current != null) {
            val done = when (current.reason) {
                PartyHoldReason.BUFFERING ->
                    if (current.memberId == localId) !selfStalled && !p.isBuffering
                    else members[current.memberId]?.stalled != true
                PartyHoldReason.LOADING -> {
                    val hostReady = settle == null && !p.isBuffering
                    hostReady && otherMembers().all { member ->
                        member.id in catchingUp || member.away ||
                            (member.ready && member.readyAt >= current.startedAt &&
                                abs(member.positionMs - current.positionMs) <= HOLD_READY_WINDOW_MS)
                    }
                }
            }
            if (done) {
                endHold(now)
            } else if (now >= current.until) {
                log("hold ${current.reason} ran out after ${now - current.startedAt} ms")
                if (current.reason == PartyHoldReason.BUFFERING) {
                    current.memberId?.takeIf { it != localId }?.let(catchingUp::add)
                }
                if (current.memberId != localId || !selfStalled) endHold(now)
            }
            return
        }
        if (expectedIntent != true && pendingPlayAt == null) return
        if (selfStalled && handledStalls.add(localId)) {
            beginHold(PartyHoldReason.BUFFERING, localId, now + HOST_STALL_HOLD_MS, now)
            return
        }
        for (member in otherMembers()) {
            if (!member.stalled || member.id in catchingUp || !handledStalls.add(member.id)) continue
            val repeat = member.lastHoldAt?.let { now - it < REPEAT_STALL_WINDOW_MS } == true
            if (repeat) {
                log("member ${short(member.id)} stalled again, catches up alone")
                catchingUp += member.id
                broadcastState()
                continue
            }
            val cooling = lastBufferHoldAt?.let { now - it < HOLD_COOLDOWN_MS } == true
            if (cooling) continue
            member.lastHoldAt = now
            val wait = if (otherMembers().size >= 2) BUFFER_HOLD_MS else BUFFER_HOLD_PAIR_MS
            beginHold(PartyHoldReason.BUFFERING, member.id, now + wait, now)
            return
        }
    }

    private fun beginHold(reason: PartyHoldReason, memberId: String?, until: Long, now: Long, position: Long? = null) {
        val p = player ?: return
        pendingPlayAt = null
        val at = position ?: settle?.target ?: p.positionMs
        hold = Hold(reason, memberId, now, until, at)
        log("hold $reason member=${memberId?.let(::short)} at $at for up to ${until - now} ms")
        commandPause(p, now)
        broadcastState()
    }

    private fun endHold(now: Long) {
        val current = hold ?: return
        log("hold ${current.reason} ended after ${now - current.startedAt} ms")
        hold = null
        if (current.reason == PartyHoldReason.BUFFERING) lastBufferHoldAt = now
        if (player != null) pendingPlayAt = now + RESUME_LEAD_MS
        broadcastState()
    }

    private fun sendMedia() {
        val media = hostMedia ?: return
        if (role != PartyRole.HOST) return
        send(PartyMessage.Media(term, epoch, if (shareLink) media else media.copy(sharedUrl = null)))
    }

    /** Host only: sends the media again, for example after the share-link switch changed. */
    fun resendMedia() = sendMedia()

    private fun broadcastState() {
        if (role != PartyRole.HOST || status == PartyStatus.IDLE) return
        val now = clock()
        val p = player
        val current = hold
        val scheduled = pendingPlayAt
        val clockless = adopted.takeIf { p == null && current == null }
        val position = when {
            current != null -> current.positionMs
            p != null -> settle?.target ?: p.positionMs
            clockless != null -> clockless.positionMs
            else -> lastHostPosition
        }
        val playing = clockless?.playing ?: (current == null && p != null && (scheduled != null || expectedIntent == true))
        lastStateSentAt = now
        send(
            PartyMessage.State(
                term = term,
                epoch = epoch,
                sentAt = now,
                hostClock = clockless?.at ?: scheduled ?: now,
                positionMs = position,
                playing = playing,
                hold = current?.let { PartyHoldInfo(it.reason, it.memberId, it.until) },
                roster = roster,
                guestsControl = guestsControl,
                catchingUp = catchingUp.toList(),
            )
        )
    }

    // ---- Guest ----

    private fun guestTick(now: Long) {
        if (status == PartyStatus.ACTIVE && hostId != null && now >= nextPingAt) {
            pingsSent++
            nextPingAt = now + if (pingsSent < FAST_PINGS) FAST_PING_INTERVAL_MS else PING_INTERVAL_MS
            pendingPings += now
            while (pendingPings.size > MAX_PENDING_PINGS) pendingPings.remove(pendingPings.first())
            send(PartyMessage.Ping(now))
        }
        if (electIfHostGone(now)) return
        val p = player
        if (away) {
            aligned = false
            driftMs = null
        } else if (p != null && playerMedia?.sameTitle(hostMedia) == true) {
            val action = detectLocalAction(p, now)
            if (action != null) onGuestLocalAction(action, p, now)
            trackOwnStall(p, now)
            guestSync(now)
        } else {
            aligned = false
            driftMs = null
        }
        val ready = aligned && !selfStalled
        if (selfStalled != lastStatusStalled || ready != lastStatusReady || now - lastStatusSentAt >= STATUS_INTERVAL_MS) {
            sendStatus(now)
        }
    }

    private fun electIfHostGone(now: Long): Boolean {
        val current = hostId ?: return false
        if (!sawHost || status != PartyStatus.ACTIVE) return false
        val seen = members[current]?.lastSeenAt
        val gone = hostLeft || seen == null || now - seen > HOST_TIMEOUT_MS
        if (!gone) return false
        val candidates = if (localId in roster) roster else roster + localId
        val successor = candidates.firstOrNull { id ->
            id != current && if (id == localId) {
                !away
            } else {
                members[id]?.let { !it.away && now - it.lastSeenAt <= MEMBER_FRESH_MS } == true
            }
        }
        if (successor != localId) return false
        log("host ${short(current)} gone (left=$hostLeft, silent ${seen?.let { now - it }} ms), taking over")
        members.remove(current)
        roster = roster.filter { it != current }
        takeOver(now)
        return true
    }

    private fun takeOver(now: Long) {
        val previous = room
        val wasHeld = previous?.hold != null
        adopted = if (player == null && previous != null) {
            val hostNow = hostClock.hostNow(now)
            val position = if (previous.playing && hostNow != null) {
                previous.positionMs + max(0L, hostNow - previous.hostClock)
            } else {
                previous.positionMs
            }
            Adopted(position, now, previous.playing || wasHeld)
        } else {
            null
        }
        role = PartyRole.HOST
        hostChanges++
        if (term < Int.MAX_VALUE) term += 1
        hostId = localId
        hostLeft = false
        sawHost = true
        roster = listOf(localId) + roster.filter { it != localId }
        hold = null
        pendingPlayAt = null
        pendingCommand = null
        selfPaused = false
        catchingUp.remove(localId)
        everSynced = true
        playerMedia?.let { hostMedia = it }
        player?.let { applySpeed(it, 1f) }
        if (wasHeld && player != null && !away) pendingPlayAt = now + RESUME_LEAD_MS
        sendHello(now, force = true)
        sendMedia()
        broadcastState()
    }

    /** Host only: leaves the room to the others while this device is away, and follows on return. */
    private fun stepDown(now: Long) {
        log("handing the room over while away")
        send(PartyMessage.Bye)
        steppedDown = true
        role = PartyRole.GUEST
        hostId = null
        sawHost = false
        hostLeft = false
        hold = null
        pendingPlayAt = null
        room = null
        roster = roster.filter { it != localId }
        hostClock.reset()
        pendingPings.clear()
        pingsSent = 0
        nextPingAt = now
        everSynced = false
        resyncRequested = true
    }

    private fun onGuestLocalAction(action: PartyAction, p: PartyPlayer, now: Long) {
        val position = p.positionMs
        log("local $action at $position sentToHost=$guestsControl")
        if (guestsControl) {
            if (action == PartyAction.PLAY) selfPaused = false
            pendingCommand = PendingCommand(action, position, now + COMMAND_GRACE_MS)
            send(PartyMessage.Command(epoch, action, position))
            return
        }
        when (action) {
            PartyAction.PAUSE -> selfPaused = true
            PartyAction.PLAY -> {
                selfPaused = false
                resyncRequested = true
            }
            PartyAction.SEEK -> resyncRequested = true
        }
    }

    private fun guestSync(now: Long) {
        if (away) return
        val p = player ?: return
        val current = room ?: return
        if (playerMedia?.sameTitle(hostMedia) != true || current.epoch != epoch) return
        pendingCommand?.let { if (now < it.until) return else pendingCommand = null }
        val hostNow = hostClock.hostNow(now) ?: return
        val shouldPlay = current.playing && hostNow >= current.hostClock
        var target = if (current.playing) current.positionMs + max(0L, hostNow - current.hostClock) else current.positionMs
        val duration = p.durationMs
        if (duration > 0) target = target.coerceAtMost(duration)

        parkedUntil?.let { at ->
            when {
                !shouldPlay || now - parkedSince > PARK_TIMEOUT_MS -> parkedUntil = null
                settle != null || p.isBuffering -> {
                    driftMs = null
                    return
                }
                target - at > PARK_MISS_MS && parkAttempts < MAX_PARK_ATTEMPTS -> {
                    log("park missed by ${target - at} ms, again with lead=$seekLeadMs")
                    park(p, target, now, again = true)
                    return
                }
                target >= at - PARK_RELEASE_EARLY_MS -> {
                    parkedUntil = null
                    log("start from park at $at, room at $target, parked ${now - parkedSince} ms")
                    commandPlay(p, now)
                    return
                }
                else -> {
                    driftMs = null
                    return
                }
            }
        }

        if (!shouldPlay) {
            applySpeed(p, 1f)
            if (p.wantsToPlay && now >= intentGraceUntil) commandPause(p, now)
            val gap = abs(p.positionMs - target)
            driftMs = null
            if (settle == null && gap > PAUSED_ALIGN_MS) {
                if (pausedAlignKey != target) {
                    pausedAlignKey = target
                    pausedAlignAttempts = 0
                }
                if (pausedAlignAttempts < MAX_PAUSED_ALIGN_ATTEMPTS) {
                    pausedAlignAttempts++
                    log("align while paused gap=${p.positionMs - target} attempt=$pausedAlignAttempts")
                    commandSeek(p, target, now, moving = false)
                }
            }
            aligned = settle == null && !p.isBuffering && abs(p.positionMs - target) <= PAUSED_READY_MS
            if (aligned) everSynced = true
            return
        }
        if (selfPaused) {
            aligned = false
            driftMs = target - p.positionMs
            return
        }
        if (!p.wantsToPlay && now >= intentGraceUntil) {
            val gap = target - p.positionMs
            log("resume drift=$gap ${if (abs(gap) > RESUME_ALIGN_MS) "seek lead=$seekLeadMs" else "no seek"}")
            if (abs(gap) > RESUME_ALIGN_MS && !p.speedAllowed) {
                park(p, target, now)
                return
            }
            if (abs(gap) > RESUME_ALIGN_MS) commandSeek(p, target + seekLeadMs, now, moving = true)
            commandPlay(p, now)
            return
        }
        if (settle != null || p.isBuffering) return
        val drift = target - p.positionMs
        driftMs = drift
        if (now - lastDriftLogAt >= DRIFT_LOG_INTERVAL_MS) {
            lastDriftLogAt = now
            log("drift=$drift speed=${speedText(currentSpeed)} speedOk=${p.speedAllowed} rtt=${hostClock.roundTripMs}")
        }
        val deadBand = if (p.speedAllowed) PartySyncPolicy.SPEED_DEAD_BAND_MS else PartySyncPolicy.FIXED_DEAD_BAND_MS
        if (resyncAfterRecovery && now >= recoverUntil) {
            resyncAfterRecovery = false
            resyncRequested = true
        }
        val decided = PartySyncPolicy.decide(
            driftMs = drift,
            speedAllowed = p.speedAllowed,
            nudging = currentSpeed != 1f,
            msSinceLastSeek = now - lastSeekAt,
            resyncRequested = resyncRequested,
        )
        val rebuilding = now < recoverUntil && abs(drift) < RECOVERY_SEEK_ABOVE_MS
        when (val action = if (decided == PartySyncAction.Seek && rebuilding) PartySyncAction.Hold else decided) {
            PartySyncAction.Hold -> applySpeed(p, 1f)
            is PartySyncAction.Speed -> applySpeed(p, action.speed)
            PartySyncAction.Seek -> {
                log("correct by seek drift=$drift lead=$seekLeadMs speedOk=${p.speedAllowed} resync=$resyncRequested sinceLastSeek=${now - lastSeekAt}")
                applySpeed(p, 1f)
                resyncRequested = false
                if (p.speedAllowed) commandSeek(p, target + seekLeadMs, now, moving = true) else park(p, target, now)
                return
            }
        }
        aligned = abs(drift) <= deadBand
        if (aligned) {
            everSynced = true
            resyncRequested = false
        }
    }

    private fun sendStatus(now: Long) {
        if (status != PartyStatus.ACTIVE || role != PartyRole.GUEST) return
        val p = player
        val ready = aligned && !selfStalled
        lastStatusSentAt = now
        lastStatusStalled = selfStalled
        lastStatusReady = ready
        val card = profile()
        send(
            PartyMessage.Status(
                epoch = epoch,
                name = deviceName(),
                positionMs = p?.positionMs ?: 0L,
                stalled = selfStalled,
                ready = ready,
                speedOk = p?.speedAllowed ?: true,
                driftMs = driftMs,
                away = away,
                avatar = card.avatarUrl,
                colour = card.colour,
            )
        )
    }

    // ---- Shared player handling ----

    private fun detectLocalAction(p: PartyPlayer, now: Long): PartyAction? {
        val intent = p.wantsToPlay
        val position = p.positionMs
        val elapsed = now - lastSampleAt
        var action: PartyAction? = null
        val expected = expectedIntent
        if (expected != null && intent != expected) {
            if (now < intentGraceUntil) return null
            action = if (intent) PartyAction.PLAY else PartyAction.PAUSE
        }
        val settling = settle
        if (settling != null) {
            val waited = now - settling.startedAt
            val arrived = if (settling.moving) {
                position >= settling.target + SETTLE_ADVANCE_MS && position - settling.target < SETTLE_WINDOW_MS
            } else {
                abs(position - settling.target) <= SETTLE_WINDOW_MS
            }
            if (!p.isBuffering && arrived && waited >= SETTLE_MIN_MS) {
                if (settling.learn) {
                    seekLeadMs = ((seekLeadMs + waited) / 2).coerceIn(MIN_SEEK_LEAD_MS, MAX_SEEK_LEAD_MS)
                }
                log("seek settled in $waited ms at $position target=${settling.target} moving=${settling.moving} lead=$seekLeadMs")
                settle = null
            } else if (waited > SETTLE_TIMEOUT_MS) {
                log("seek did not settle in $waited ms at $position target=${settling.target}")
                settle = null
            }
        } else if (action == null && expected != null) {
            val advanced = position - lastPosition
            val jumped = advanced < -SEEK_DETECT_MS || advanced > elapsed * MAX_ADVANCE_FACTOR + SEEK_DETECT_MS
            if (jumped && now < seekIntentUntil) {
                seekIntentUntil = 0L
                action = PartyAction.SEEK
            }
        }
        lastPosition = position
        lastSampleAt = now
        expectedIntent = intent
        return action
    }

    private fun trackOwnStall(p: PartyPlayer, now: Long) {
        val roomPlaying = if (role == PartyRole.HOST) hold == null else room?.playing == true
        val stalling = p.isBuffering && expectedIntent == true && roomPlaying && everSynced && settle == null
        if (stalling) {
            val since = bufferingSince ?: now.also { bufferingSince = it }
            if (!selfStalled && now - since >= STALL_AFTER_MS) {
                log("stalled at ${p.positionMs}")
                selfStalled = true
            }
        } else if (!p.isBuffering) {
            val since = bufferingSince
            bufferingSince = null
            if (selfStalled) {
                log("stall over after ${since?.let { now - it }} ms")
                selfStalled = false
                handledStalls.remove(localId)
                recoverUntil = now + STALL_RECOVERY_MS
                resyncAfterRecovery = p.speedAllowed
            }
        }
    }

    private fun commandPlay(p: PartyPlayer, now: Long) {
        log("play at ${p.positionMs}")
        expectedIntent = true
        intentGraceUntil = now + INTENT_GRACE_MS
        p.play()
    }

    private fun commandPause(p: PartyPlayer, now: Long) {
        log("pause at ${p.positionMs}")
        expectedIntent = false
        intentGraceUntil = now + INTENT_GRACE_MS
        p.pause()
    }

    private fun commandSeek(p: PartyPlayer, target: Long, now: Long, moving: Boolean, learn: Boolean = moving) {
        val bounded = target.coerceAtLeast(0L)
        p.seekTo(bounded)
        settle = Settle(bounded, now, moving, learn)
        lastSeekAt = now
        lastPosition = bounded
        lastSampleAt = now
    }

    /** Lands a little ahead of the room, waits paused until the room reaches that point, then plays. */
    private fun park(p: PartyPlayer, target: Long, now: Long, again: Boolean = false) {
        parkAttempts = if (again) parkAttempts + 1 else 1
        val at = target + max(seekLeadMs, PARK_MIN_LEAD_MS) + PARK_MARGIN_MS
        log("park at $at, room at $target, lead=$seekLeadMs")
        if (p.wantsToPlay) commandPause(p, now)
        commandSeek(p, at, now, moving = false, learn = true)
        parkedUntil = at
        parkedSince = now
    }

    private fun applySpeed(p: PartyPlayer, speed: Float) {
        val wanted = if (p.speedAllowed) speed else 1f
        if (wanted == currentSpeed) return
        if (!p.speedAllowed && currentSpeed == 1f) return
        log("speed ${speedText(wanted)} drift=$driftMs")
        currentSpeed = wanted
        p.setSpeed(wanted)
    }

    private fun resetBaseline(p: PartyPlayer, now: Long) {
        lastPosition = p.positionMs
        lastSampleAt = now
        expectedIntent = p.wantsToPlay
        intentGraceUntil = 0L
    }

    // ---- Membership and view ----

    private fun otherMembers(): List<Member> = members.values.filter { it.id != localId }

    private fun someoneElseWatching(): Boolean = otherMembers().any { !it.away }

    private fun pruneMembers(now: Long) {
        val expired = members.values.filter { it.id != hostId && now - it.lastSeenAt > MEMBER_TIMEOUT_MS }
        if (expired.isEmpty()) return
        expired.forEach { member ->
            log("member ${short(member.id)} timed out")
            forgetFriendRequest(member.id)
            members.remove(member.id)
            catchingUp.remove(member.id)
            handledStalls.remove(member.id)
        }
        if (role == PartyRole.HOST) {
            roster = roster.filter { id -> id == localId || members.containsKey(id) }
            broadcastState()
        }
    }

    private fun sendHello(now: Long, force: Boolean = false) {
        if (!force && now - lastHelloAt < HELLO_INTERVAL_MS) return
        lastHelloAt = now
        val card = profile()
        val friendKey = friendIdentity(false)?.let { me ->
            helloProof?.takeIf { it.first == localId && it.second == me.publicKeyHex }
                ?: Triple(localId, me.publicKeyHex, PartyInvites.proof(me, localId, PartyInvites.ANYONE, random)).also { helloProof = it }
        }
        send(
            PartyMessage.Hello(
                name = deviceName(),
                host = role == PartyRole.HOST,
                term = term,
                speedOk = player?.speedAllowed ?: true,
                avatar = card.avatarUrl,
                colour = card.colour,
                friendKey = friendKey?.second,
                friendProof = friendKey?.third,
            )
        )
    }

    private fun send(message: PartyMessage) {
        transport?.send(PartyCodec.encode(message))
    }

    private fun publish() {
        val now = clock()
        val isHost = role == PartyRole.HOST
        val card = profile()
        val views = buildList {
            if (status != PartyStatus.IDLE) {
                add(
                    PartyMemberView(
                        id = localId,
                        name = deviceName(),
                        isHost = isHost,
                        isSelf = true,
                        status = when {
                            away -> PartyMemberStatus.AWAY
                            selfStalled -> PartyMemberStatus.BUFFERING
                            localId in catchingUp -> PartyMemberStatus.CATCHING_UP
                            isHost || aligned -> PartyMemberStatus.IN_SYNC
                            else -> PartyMemberStatus.SYNCING
                        },
                        avatarUrl = card.avatarUrl,
                        colour = card.colour,
                        friendKey = friendIdentity(false)?.publicKeyHex,
                    )
                )
                otherMembers().forEach { member ->
                    val memberIsHost = member.id == hostId
                    add(
                        PartyMemberView(
                            id = member.id,
                            name = member.name,
                            isHost = memberIsHost,
                            isSelf = false,
                            status = when {
                                member.away -> PartyMemberStatus.AWAY
                                member.stalled -> PartyMemberStatus.BUFFERING
                                member.id in catchingUp -> PartyMemberStatus.CATCHING_UP
                                memberIsHost || member.ready -> PartyMemberStatus.IN_SYNC
                                else -> PartyMemberStatus.SYNCING
                            },
                            avatarUrl = member.avatar,
                            colour = member.colour,
                            friendKey = member.friendKey,
                        )
                    )
                }
            }
        }
        val holdView = if (isHost) {
            hold?.let { holdView(it.reason, it.memberId, it.until, now) }
        } else {
            room?.hold?.let { info ->
                val hostNow = hostClock.hostNow(now)
                val until = if (hostNow != null) now + (info.untilHostClock - hostNow) else now
                holdView(info.reason, info.memberId, until, now)
            }
        }
        val hostSeen = hostId?.let { id -> id == localId || members[id]?.let { now - it.lastSeenAt <= HOST_TIMEOUT_MS } == true } == true
        val next = PartyState(
            status = status,
            role = role,
            code = code,
            members = views,
            hostPresent = hostSeen && !hostLeft,
            guestsControl = guestsControl,
            shareLink = shareLink,
            hold = holdView,
            driftMs = if (isHost) null else driftMs,
            selfPaused = selfPaused,
            catchingUp = localId in catchingUp,
            playingPartyTitle = player != null && (isHost || playerMedia?.sameTitle(hostMedia) == true),
            durationMismatchMs = if (isHost) null else PartyStreamMatcher.durationMismatchMs(
                hostMedia?.fingerprint?.durationMs,
                player?.durationMs?.takeIf { playerMedia?.sameTitle(hostMedia) == true },
            ),
            problem = problem,
            endedByHost = endedByHost,
            hostChanges = hostChanges,
            friendAsked = friendAsked.keys.toSet(),
        )
        if (next != _state.value) _state.value = next
    }

    private fun holdView(reason: PartyHoldReason, memberId: String?, until: Long, now: Long): PartyHoldView {
        val name = when (memberId) {
            null -> null
            localId -> deviceName()
            else -> members[memberId]?.name
        }
        return PartyHoldView(
            reason = reason,
            memberName = name,
            isSelf = memberId == localId,
            secondsLeft = (((until - now) + 999) / 1000).coerceAtLeast(0).toInt(),
        )
    }

    private fun short(id: String): String = id.take(6)

    private fun describe(media: PartyMedia): String = buildString {
        append(media.videoId ?: media.contentId)
        if (media.season != null || media.episode != null) append(" s${media.season}e${media.episode}")
        media.fingerprint.durationMs?.let { append(" duration=$it") }
    }

    private fun speedText(speed: Float): String = String.format(Locale.ROOT, "%.3f", speed)

    companion object {
        const val STATE_INTERVAL_MS = 10_000L
        const val STATUS_INTERVAL_MS = 15_000L
        const val HOST_TIMEOUT_MS = 25_000L
        const val MEMBER_FRESH_MS = 30_000L
        const val MEMBER_TIMEOUT_MS = 40_000L
        const val STALL_AFTER_MS = 2_000L
        const val BUFFER_HOLD_MS = 10_000L
        const val BUFFER_HOLD_PAIR_MS = 45_000L
        const val HOST_STALL_HOLD_MS = 60_000L
        const val LOADING_HOLD_MS = 15_000L
        const val SEEK_HOLD_MS = 10_000L
        const val HOLD_COOLDOWN_MS = 60_000L
        const val REPEAT_STALL_WINDOW_MS = 300_000L
        const val RESUME_LEAD_MS = 700L
        private const val HOLD_READY_WINDOW_MS = 1_500L
        private const val PAUSED_ALIGN_MS = 100L
        private const val PAUSED_READY_MS = 500L
        private const val MAX_PAUSED_ALIGN_ATTEMPTS = 3
        private const val RESUME_ALIGN_MS = 300L
        private const val SEEK_DETECT_MS = 1_000L
        private const val SEEK_INTENT_WINDOW_MS = 2_500L
        private const val MAX_ADVANCE_FACTOR = 1.25
        private const val SETTLE_ADVANCE_MS = 200L
        private const val SETTLE_WINDOW_MS = 5_000L
        private const val SETTLE_MIN_MS = 300L
        private const val SETTLE_TIMEOUT_MS = 20_000L
        private const val DEFAULT_SEEK_LEAD_MS = 500L
        private const val MIN_SEEK_LEAD_MS = 200L
        private const val MAX_SEEK_LEAD_MS = 3_000L
        private const val STALL_RECOVERY_MS = 10_000L
        private const val RECOVERY_SEEK_ABOVE_MS = 15_000L
        private const val ADOPT_SEEK_MS = 2_000L
        private const val BACK_GRACE_MS = 3_000L
        private const val PARK_MARGIN_MS = 400L
        private const val PARK_RELEASE_EARLY_MS = 60L
        private const val PARK_TIMEOUT_MS = 20_000L
        private const val PARK_MIN_LEAD_MS = 1_200L
        private const val PARK_MISS_MS = 150L
        private const val MAX_PARK_ATTEMPTS = 2
        private const val INTENT_GRACE_MS = 600L
        private const val COMMAND_GRACE_MS = 3_000L
        private const val COMMAND_SEEK_MATCH_MS = 2_000L
        private const val HELLO_INTERVAL_MS = 3_000L
        private const val REASSERT_INTERVAL_MS = 2_000L
        private const val FAST_PINGS = 3
        private const val FAST_PING_INTERVAL_MS = 1_500L
        private const val PING_INTERVAL_MS = 60_000L
        private const val MAX_PENDING_PINGS = 8
        private const val DRIFT_LOG_INTERVAL_MS = 10_000L
        private const val FRIEND_ASK_TIMEOUT_MS = 120_000L
    }
}
