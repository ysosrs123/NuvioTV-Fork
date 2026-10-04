package com.nuvio.tv.core.party

import com.nuvio.tv.core.profile.LocalProfileAvatars
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.provider.Settings
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileLockStateDataStore
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the one party this device can be in. Everything public here is called on the main thread. */
@Singleton
class PartyRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
    private val profileManager: ProfileManager,
    private val avatarRepository: AvatarRepository,
    profileLocks: ProfileLockStateDataStore,
    private val localAvatars: LocalProfileAvatars,
    private val friendStore: PartyFriendStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "party-relays").apply { isDaemon = true }
    }
    private val deliver = Executor { mainHandler.post(it) }
    private val relayClient: OkHttpClient = okHttpClient.newBuilder()
        .cache(null)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private val preferences = context.getSharedPreferences("watch_party", Context.MODE_PRIVATE)
    private var transport: NostrPartyTransport? = null

    private val pinLocked: StateFlow<Map<Int, Boolean>> = profileLocks.pinEnabled.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val random = SecureRandom()
    private val inbox = PartyInbox(
        connector = OkHttpRelayConnector(relayClient),
        relays = { relays },
        worker = worker,
        deliver = Executor { it.run() },
        wallClockSeconds = { System.currentTimeMillis() / 1000 },
        monotonicMs = SystemClock::elapsedRealtime,
        random = random,
        log = ::log,
    )
    private val appVisible = MutableStateFlow(false)
    private var avatarCatalog: List<AvatarCatalogItem> = emptyList()
    private val _avatarCatalogLoads = MutableStateFlow(0)

    /** Goes up when Nuvio's avatar catalogue has loaded, so pictures that depend on it can be looked up again. */
    val avatarCatalogLoads: StateFlow<Int> = _avatarCatalogLoads.asStateFlow()
    private val seenInvites = friendStore.seenInvites()
    @Volatile private var inboxProfiles: Map<String, Pair<Int, PartyIdentity>> = emptyMap()
    private val heldInvites = mutableListOf<PartyInviteCard>()

    private val activeId: Int get() = profileManager.activeProfileId.value

    /** Friends of the profile in use, most recent first. */
    val friends: StateFlow<List<PartyFriend>> = combine(friendStore.friends, profileManager.activeProfileId) { all, id -> all[id].orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, friendStore.friendsOf(profileManager.activeProfileId.value))

    private val invites = ArrayDeque<PartyInviteCard>()
    private val _invite = MutableStateFlow<PartyInviteCard?>(null)

    /** An invite from a friend of one of this box's profiles, shown anywhere in the app until joined or dismissed. */
    val invite: StateFlow<PartyInviteCard?> = _invite.asStateFlow()

    private val _invited = MutableStateFlow<Map<String, String>>(emptyMap())
    private val invitedBy = HashMap<String, Int>()

    /** Friend key to the party code they were last invited to from this device. */
    val invited: StateFlow<Map<String, String>> = _invited.asStateFlow()

    private val _friendNotice = MutableStateFlow<PartyFriendNotice?>(null)

    /** Who just became a friend, for a short note. */
    val friendNotice: StateFlow<PartyFriendNotice?> = _friendNotice.asStateFlow()

    val session = PartySession(
        transportFactory = {
            NostrPartyTransport(
                connector = OkHttpRelayConnector(relayClient),
                relays = relays,
                identity = PartyIdentity.random(),
                worker = worker,
                deliver = deliver,
                wallClockSeconds = { System.currentTimeMillis() / 1000 },
                monotonicMs = SystemClock::elapsedRealtime,
                log = ::log,
            ).also { transport = it }
        },
        clock = SystemClock::elapsedRealtime,
        deviceName = ::memberName,
        log = ::log,
        profile = ::currentProfile,
        friendIdentity = { create -> friendStore.identity(activeId, create) },
        isFriend = { key -> friendStore.isFriend(activeId, key) },
        onFriendAdded = ::onFriendAdded,
    )

    val state: StateFlow<PartyState> get() = session.state

    val relays: List<String> get() = PartyRelays.DEFAULT

    /** The party's title when this device is a guest and [videoId] is what the party is watching, else null. */
    fun guestMediaFor(videoId: String, season: Int?, episode: Int?): PartyMedia? {
        val current = state.value
        if (!current.isActive || current.role != PartyRole.GUEST) return null
        val media = session.partyMedia ?: return null
        return media.takeIf { (it.videoId ?: it.contentId) == videoId && it.season == season && it.episode == episode }
    }

    init {
        scope.launch {
            session.state.map { it.isActive }.distinctUntilChanged().collectLatest { active ->
                if (!active) return@collectLatest
                var ticks = 0
                while (true) {
                    session.tick()
                    if (++ticks % TRANSPORT_TICK_EVERY == 0) transport?.tick()
                    if (ticks % TOGETHER_EVERY == TRANSPORT_TICK_EVERY) noteTogether()
                    delay(TICK_MS)
                }
            }
        }
        scope.launch {
            profileManager.activeProfileReady.first { it }
            friendStore.adoptDeviceData(profileManager.activeProfileId.value, preferences.getString(KEY_NAME, null))
            preferences.edit().remove(KEY_NAME).remove(KEY_RELAYS).apply()
            var known = profileManager.profiles.value.map { it.id }.toSet()
            profileManager.profiles.collect { list ->
                val now = list.map { it.id }.toSet()
                (known - now).forEach { id ->
                    friendStore.clearProfile(id)
                    localAvatars.set(id, null)
                }
                known = now
            }
        }
        scope.launch {
            localAvatars.avatars.map { it[activeId] }.distinctUntilChanged().drop(1).collect { session.announceName() }
        }
        scope.launch {
            profileManager.activeProfileId.drop(1).collect { id ->
                session.announceName()
                heldInvites.filter { it.profileId == id }.forEach { held ->
                    heldInvites.remove(held)
                    invites.addLast(held)
                }
                if (_invite.value == null) _invite.value = invites.firstOrNull()
            }
        }
        scope.launch {
            for (attempt in 0 until CATALOGUE_ATTEMPTS) {
                avatarCatalog = runCatching { avatarRepository.getAvatarCatalog() }.getOrDefault(emptyList())
                if (avatarCatalog.isNotEmpty()) break
                delay(CATALOGUE_RETRY_MS)
            }
            _avatarCatalogLoads.value++
            session.announceName()
        }
        scope.launch {
            state.map { it.code }.distinctUntilChanged().collect { code -> cancelInvitesNotFor(code) }
        }
        scope.launch {
            combine(appVisible, friendStore.friends, profileManager.profiles) { visible, all, profiles ->
                if (visible) all.keys.intersect(profiles.map { it.id }.toSet()) else emptySet()
            }
                .distinctUntilChanged()
                .collect { profileIds ->
                    val tags = profileIds.mapNotNull { id ->
                        friendStore.identity(id, create = false)?.let { PartyInvites.inboxTag(it.publicKeyHex) to (id to it) }
                    }.toMap()
                    inboxProfiles = tags
                    inbox.listen(tags.keys) { event -> onInviteEvent(event) }
                }
        }
        scope.launch {
            appVisible.collectLatest { visible ->
                if (!visible) {
                    delay(INBOX_HIDDEN_TICK_MS)
                    inbox.tick()
                }
                while (visible) {
                    inbox.tick()
                    delay(INBOX_TICK_MS)
                }
            }
        }
    }

    /** The inbox only listens while the app is on screen. */
    fun setAppVisible(visible: Boolean) {
        appVisible.value = visible
    }

    /**
     * Sends [friend] an invite to this device's party, starting one first when there is none.
     * The invite waits on the relays for two hours if their app is closed.
     */
    fun inviteFriend(friend: PartyFriend) {
        val profileId = activeId
        val me = friendStore.identity(profileId, create = true) ?: return
        val current = state.value
        val code = (if (current.isActive) current.code else null) ?: session.createParty()
        val media = session.partyMedia ?: session.attachedMedia
        val card = currentProfile()
        val host = state.value.members.firstOrNull { it.isHost }?.name?.ifBlank { null }
        val invite = PartyInvite(
            id = "",
            from = me.publicKeyHex,
            name = memberName(),
            host = host ?: memberName(),
            code = code,
            title = media?.title?.ifBlank { null },
            poster = PartyInvites.cleanImage(media?.poster),
            watching = state.value.members.size.coerceAtLeast(1),
            avatarUrl = card.avatarUrl,
            colour = card.colour,
            sentAt = 0L,
        )
        _invited.value = _invited.value + (friend.key to code)
        invitedBy[friend.key] = profileId
        log("invite to ${friend.key.take(6)}")
        worker.execute {
            val event = PartyInvites.seal(me, friend.key, invite, System.currentTimeMillis() / 1000, random) ?: return@execute
            inbox.publish(event) { accepted, _ ->
                if (accepted == 0) mainHandler.post {
                    if (_invited.value[friend.key] == code) {
                        _invited.value = _invited.value - friend.key
                        invitedBy.remove(friend.key)
                    }
                }
            }
        }
    }

    /** Takes back the invite [friend] got to this device's current party. */
    fun cancelInvite(friend: PartyFriend) {
        val code = _invited.value[friend.key] ?: return
        _invited.value = _invited.value - friend.key
        sendCancel(friend.key, code)
    }

    private fun cancelInvitesNotFor(code: String?) {
        val stale = _invited.value.filterValues { it != code }
        if (stale.isEmpty()) return
        _invited.value = _invited.value - stale.keys
        stale.forEach { (key, oldCode) -> sendCancel(key, oldCode) }
    }

    private fun sendCancel(friendKey: String, code: String) {
        val profileId = invitedBy.remove(friendKey) ?: activeId
        val me = friendStore.identity(profileId, create = false) ?: return
        log("invite to ${friendKey.take(6)} cancelled")
        worker.execute {
            val event = PartyInvites.sealCancel(me, friendKey, code, System.currentTimeMillis() / 1000, random) ?: return@execute
            inbox.publish(event) { _, _ -> }
        }
    }

    /** The avatar others see: the one chosen for parties, else the profile's own picture or catalogue avatar. */
    private fun currentProfile(): PartyProfile = PartyProfile(
        avatarUrl = partyAvatarChoice ?: profileAvatar(),
        colour = PartyInvites.cleanColour(profileManager.activeProfile?.avatarColorHex),
    )

    /** The picture of the profile in use, as others can load it: one from the built-in set, or an https picture. */
    fun profileAvatar(): String? {
        val active = profileManager.activeProfile
        active?.let { localAvatars.get(it.id) }?.let { return it }
        val picked = active?.avatarId?.let { id -> runCatching { avatarRepository.getAvatarImageUrl(id, avatarCatalog) }.getOrNull() }
        return PartyInvites.cleanImage(active?.avatarUrl) ?: PartyInvites.cleanImage(picked)
    }

    /** Nuvio's own avatars that every box can load. */
    fun nuvioAvatars(): List<String> = avatarCatalog.mapNotNull { PartyInvites.cleanImage(it.imageUrl) }.distinct()

    /** The avatar chosen for parties by the profile in use; null means the profile's own. */
    val partyAvatarChoice: String? get() = PartyInvites.cleanAvatar(friendStore.partyAvatar(activeId))

    fun setPartyAvatar(ref: String?) {
        friendStore.setPartyAvatar(activeId, PartyInvites.cleanAvatar(ref))
        session.announceName()
    }

    val profileName: String get() = profileManager.activeProfile?.name.orEmpty()

    val activeProfileId: StateFlow<Int> get() = profileManager.activeProfileId

    val profileColour: String? get() = PartyInvites.cleanColour(profileManager.activeProfile?.avatarColorHex)

    /** True when the invite is for another profile that has a PIN: it waits until that profile is in use. */
    fun needsProfileUnlock(card: PartyInviteCard): Boolean = card.profileId != activeId && pinLocked.value[card.profileId] == true

    fun joinInvite() {
        val current = _invite.value ?: return
        if (needsProfileUnlock(current)) {
            invites.removeFirstOrNull()
            heldInvites += current
            _invite.value = invites.firstOrNull()
            return
        }
        invites.clear()
        _invite.value = null
        if (current.profileId == activeId) {
            session.joinParty(current.invite.code)
            return
        }
        scope.launch {
            val switched = runCatching {
                profileManager.setActiveProfile(current.profileId)
                withTimeoutOrNull(PROFILE_SWITCH_WAIT_MS) { profileManager.activeProfileId.first { it == current.profileId } } != null
            }.getOrDefault(false)
            if (switched) session.joinParty(current.invite.code) else log("profile switch for an invite failed")
        }
    }

    fun dismissInvite() {
        invites.removeFirstOrNull()
        _invite.value = invites.firstOrNull()
    }

    fun removeFriend(key: String) {
        val code = _invited.value[key]
        if (code != null) {
            _invited.value = _invited.value - key
            sendCancel(key, code)
        }
        friendStore.remove(activeId, key)
        invites.removeAll { it.invite.from == key && it.profileId == activeId }
        heldInvites.removeAll { it.invite.from == key && it.profileId == activeId }
        if (_invite.value?.let { it.invite.from == key && it.profileId == activeId } == true) _invite.value = invites.firstOrNull()
    }

    fun acknowledgeFriendNotice() {
        _friendNotice.value = null
    }

    private fun onFriendAdded(request: PartyFriendRequest) {
        val now = System.currentTimeMillis()
        val profileId = activeId
        val known = friendStore.friend(profileId, request.key)
        friendStore.put(
            profileId,
            PartyFriend(
                key = request.key,
                name = request.name.ifBlank { known?.name.orEmpty() },
                avatarUrl = request.avatarUrl,
                colour = request.colour,
                addedAt = known?.addedAt ?: now,
                lastTogetherAt = now,
                lastTitle = (session.partyMedia ?: session.attachedMedia)?.title?.ifBlank { null } ?: known?.lastTitle,
            )
        )
        if (known == null) _friendNotice.value = PartyFriendNotice(request.name, now)
    }

    /** Runs on the relay worker: opening an invite takes a few key operations. */
    private fun onInviteEvent(event: NostrEvent) {
        val (profileId, me) = event.tags.firstNotNullOfOrNull { tag ->
            if (tag.size >= 2 && tag[0] == Nostr.ROOM_TAG) inboxProfiles[tag[1]] else null
        } ?: return
        val nowSeconds = System.currentTimeMillis() / 1000
        val opened = PartyInvites.open(event, me, { key -> friendStore.friendsOf(profileId).any { it.key == key } }, nowSeconds) ?: return
        mainHandler.post {
            if (seenInvites.containsKey(opened.id)) return@post
            seenInvites[opened.id] = opened.sentAt
            seenInvites.entries.removeAll { nowSeconds - it.value > PartyInvites.LIFETIME_SECONDS * 2 }
            val cancelKey = "cancel:" + opened.from + ":" + opened.code
            if (opened.cancelled) {
                seenInvites[cancelKey] = opened.sentAt
                friendStore.saveSeenInvites(seenInvites)
                log("invite from ${opened.from.take(6)} taken back")
                invites.removeAll { it.invite.from == opened.from && it.invite.code == opened.code }
                heldInvites.removeAll { it.invite.from == opened.from && it.invite.code == opened.code }
                if (_invite.value?.let { it.invite.from == opened.from && it.invite.code == opened.code } == true) {
                    _invite.value = invites.firstOrNull()
                }
                return@post
            }
            friendStore.saveSeenInvites(seenInvites)
            if ((seenInvites[cancelKey] ?: Long.MIN_VALUE) > opened.sentAt) return@post
            val current = state.value
            if (current.isActive && current.code == opened.code) return@post
            log("invite from ${opened.from.take(6)} for profile $profileId sent ${nowSeconds - opened.sentAt} s ago")
            friendStore.update(profileId, opened.from) {
                it.copy(name = opened.name.ifBlank { it.name }, avatarUrl = opened.avatarUrl ?: it.avatarUrl, colour = opened.colour ?: it.colour)
            }
            val card = PartyInviteCard(
                invite = opened,
                profileId = profileId,
                profileName = profileManager.profiles.value.firstOrNull { it.id == profileId }?.name.orEmpty(),
            )
            invites.removeAll { it.invite.from == opened.from && it.invite.sentAt <= opened.sentAt && it !== invites.firstOrNull() }
            invites.addLast(card)
            if (_invite.value == null) _invite.value = invites.first()
        }
    }

    /** Friends in the room get the date and the title as their "last together". */
    private fun noteTogether() {
        val current = state.value
        if (current.status != PartyStatus.ACTIVE) return
        val title = (session.partyMedia ?: session.attachedMedia)?.title?.ifBlank { null }
        val now = System.currentTimeMillis()
        val profileId = activeId
        current.members.filter { !it.isSelf && it.friendKey != null }.forEach { member ->
            friendStore.update(profileId, member.friendKey!!) { friend ->
                friend.copy(
                    name = member.name.ifBlank { friend.name },
                    avatarUrl = member.avatarUrl ?: friend.avatarUrl,
                    colour = member.colour ?: friend.colour,
                    lastTogetherAt = if (now - friend.lastTogetherAt > TOGETHER_REFRESH_MS || title != friend.lastTitle) now else friend.lastTogetherAt,
                    lastTitle = title ?: friend.lastTitle,
                )
            }
        }
    }

    /** The name the profile in use shows in parties. Empty means the profile name. */
    var displayName: String
        get() = friendStore.partyName(activeId)
        set(value) {
            friendStore.setPartyName(activeId, value.trim().take(MAX_NAME_LENGTH))
        }

    val deviceName: String
        get() = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL.orEmpty().ifBlank { "TV" }

    /** What the others see when no party name is set. */
    val defaultName: String get() = profileName.ifBlank { deviceName }

    private fun memberName(): String = displayName.ifEmpty { defaultName }

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    companion object {
        const val MAX_NAME_LENGTH = 24
        private const val TAG = "WatchParty"
        private const val KEY_RELAYS = "relays"
        private const val KEY_NAME = "name"
        private const val TICK_MS = 100L
        private const val TRANSPORT_TICK_EVERY = 10
        private const val TOGETHER_EVERY = 300
        private const val TOGETHER_REFRESH_MS = 10 * 60_000L
        private const val INBOX_TICK_MS = 1_000L
        private const val INBOX_HIDDEN_TICK_MS = 15_000L
        private const val PROFILE_SWITCH_WAIT_MS = 5_000L
        private const val CATALOGUE_ATTEMPTS = 5
        private const val CATALOGUE_RETRY_MS = 60_000L
    }
}

/** An invite and the profile on this box it is for; [profileName] is shown when that is not the profile in use. */
data class PartyInviteCard(val invite: PartyInvite, val profileId: Int, val profileName: String)

data class PartyFriendNotice(val name: String, val at: Long)

internal class OkHttpRelayConnector(private val client: OkHttpClient) : RelayConnector {
    override fun connect(url: String, callbacks: RelayCallbacks): RelayConnection {
        val closed = AtomicBoolean(false)
        fun notifyClosed() {
            if (closed.compareAndSet(false, true)) callbacks.onClosed()
        }
        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = callbacks.onOpen()

                override fun onMessage(webSocket: WebSocket, text: String) = callbacks.onText(text)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSURE, null)
                    notifyClosed()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = notifyClosed()

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = notifyClosed()
            },
        )
        return object : RelayConnection {
            override fun send(text: String): Boolean = !closed.get() && socket.send(text)

            override fun close() {
                closed.set(true)
                socket.close(NORMAL_CLOSURE, null)
            }
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
